package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

/**
 *  워커 메인 루프.
 *
 *   매 5초:  백엔드 폴링 → 작업이 있으면 kind에 따라 분기 처리
 *   매 10초: heartbeat
 *
 *   ANALYSIS 흐름:
 *     1) git fetch/clone (GitRepoCache)
 *     2) 분석 prompt 치환 + claude -p exec
 *     3) 결과 파싱 → POST result (status=COMPLETED|FAILED)
 *
 *   IMPLEMENTATION 흐름:
 *     1) git fetch/clone (베이스 브랜치 최신화)
 *     2) WorktreeService.create → 새 브랜치 worktree
 *     3) 구현 prompt 치환 (분석 markdown/subtasks 포함) + claude -p exec (worktree에서)
 *     4) GitOpsService.commitAndPush → headSha
 *     5) GitOpsService.createDraftPr → prUrl/prNumber
 *     6) POST result (status=PR_CREATED 또는 IMPLEMENTATION_FAILED)
 */
@Component
@Profile("worker")
@Slf4j
public class WorkerMainLoop {

    private final WorkerProperties props;
    private final WorkerHttpClient http;
    private final GitRepoCache repos;
    private final ClaudeExecAdapter claude;
    private final PromptResultParser parser;
    private final WorkerMcpSupport mcps;
    private final WorktreeService worktrees;
    private final GitOpsService gitOps;
    private final DeployService deployService;

    public WorkerMainLoop(WorkerProperties props,
                          WorkerHttpClient http,
                          GitRepoCache repos,
                          ClaudeExecAdapter claude,
                          PromptResultParser parser,
                          WorkerMcpSupport mcps,
                          WorktreeService worktrees,
                          GitOpsService gitOps,
                          DeployService deployService) {
        this.props = props;
        this.http = http;
        this.repos = repos;
        this.claude = claude;
        this.parser = parser;
        this.mcps = mcps;
        this.worktrees = worktrees;
        this.gitOps = gitOps;
        this.deployService = deployService;
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.heartbeat-interval-seconds:10} * 1000}")
    public void sendHeartbeat() {
        try {
            http.heartbeat(new WorkerHeartbeatRequest(
                    props.id(), hostname(), props.version(), null, null, mcps.getServerNames()));
        } catch (RestClientException e) {
            log.warn("heartbeat 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.poll-interval-seconds:5} * 1000}")
    public void pollAndProcess() {
        Optional<WorkerTaskResponse> maybeTask;
        try {
            maybeTask = http.nextTask();
        } catch (RestClientException e) {
            log.warn("next-task 폴링 실패: {}", e.getMessage());
            return;
        }
        if (maybeTask.isEmpty()) return;

        WorkerTaskResponse task = maybeTask.get();
        log.info("작업 claim: id={} kind={} repo={}/{}", task.id(), task.kind(),
                task.githubRepo(), task.githubBranch());
        try {
            switch (task.kind()) {
                case IMPLEMENTATION -> processImplementation(task);
                case DEPLOY -> processDeploy(task);
                case UNDEPLOY -> processUndeploy(task);
                default -> processAnalysis(task);
            }
        } catch (Throwable t) {
            log.error("작업 처리 중 예외 task={}", task.id(), t);
            switch (task.kind()) {
                case IMPLEMENTATION -> safePostImplementationFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        null, null, null);
                case DEPLOY, UNDEPLOY -> safePostDeployFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
                default -> safePostAnalysisFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private void processAnalysis(WorkerTaskResponse task) throws Exception {
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.githubRepo(), task.githubBranch());
        } catch (Exception e) {
            safePostAnalysisFailure(task.id(), "레포 fetch 실패: " + e.getMessage());
            return;
        }

        String prompt = props.promptTemplate()
                .replace("{github_repo}", task.githubRepo())
                .replace("{github_branch}", task.githubBranch())
                .replace("{commit_sha}", repo.commitSha())
                .replace("{title}", task.title())
                .replace("{description}", task.description());

        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, repo.dir(), props.analysisTimeout(),
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra());
        } catch (Exception e) {
            safePostAnalysisFailure(task.id(), "claude exec 실패: " + e.getMessage());
            return;
        }
        if (exec.exitCode() != 0) {
            String tail = exec.stdout() == null ? "" : exec.stdout();
            if (tail.length() > 2000) tail = "…" + tail.substring(tail.length() - 2000);
            safePostAnalysisFailure(task.id(), "claude exit=" + exec.exitCode() + "\n" + tail);
            return;
        }

        PromptResultParser.ParseResult parsed;
        try {
            parsed = parser.parse(exec.stdout());
        } catch (PromptResultParser.ParseException e) {
            safePostAnalysisFailure(task.id(), "파싱 실패: " + e.getMessage());
            return;
        }

        http.postResult(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.COMPLETED,
                parsed.markdown(),
                parsed.subtasksJson(),
                exec.stdout(),
                exec.durationMs(),
                null,
                null, null, null, null, null,
                null, null, null, null, null
        ));
        log.info("분석 완료: id={} duration={}ms warnings={}",
                task.id(), exec.durationMs(), parsed.warnings());
    }

    private void processImplementation(WorkerTaskResponse task) throws Exception {
        // 1. 베이스 브랜치 최신화 (worktree add 시 origin/{base} 참조)
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.githubRepo(), task.githubBranch());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(),
                    "레포 fetch 실패: " + e.getMessage(), null, null, null);
            return;
        }

        // 2. worktree + 새 브랜치 생성
        WorktreeService.CreatedWorktree wt;
        try {
            wt = worktrees.create(repo.dir(), task.githubRepo(),
                    task.githubBranch(), task.id(), task.title());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(),
                    "worktree 생성 실패: " + e.getMessage(), null, null, null);
            return;
        }

        // 3. 구현 prompt + claude exec (worktree 디렉토리에서)
        //    비대화식 모드에서 Write/Edit/Bash 차단 회피 위해 권한 우회 활성.
        //    worktree는 격리 환경이라 워커 머신 다른 곳에 영향 없음.
        String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName());
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt.dir(), props.implementationTimeout(),
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(),
                    true);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(),
                    "claude exec 실패: " + e.getMessage(),
                    wt.branchName(), null, null);
            return;
        }
        if (exec.exitCode() != 0) {
            String tail = tail(exec.stdout(), 4000);
            safePostImplementationFailure(task.id(),
                    "claude exit=" + exec.exitCode() + "\n" + tail,
                    wt.branchName(), null, exec.stdout());
            return;
        }

        // 4. 변경 commit + push
        String headSha;
        try {
            String commitMsg = "feat: " + task.title()
                    + "\n\n" + "task #" + task.id()
                    + "\n\nCo-Authored-By: netisMaker <" + props.gitUserEmail() + ">";
            headSha = gitOps.commitAndPush(wt.dir(), wt.branchName(), commitMsg);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(),
                    "commit/push 실패: " + e.getMessage(),
                    wt.branchName(), null, exec.stdout());
            return;
        }

        // 5. gh PR create
        GitOpsService.PrInfo pr;
        try {
            String body = renderPrBody(task, exec.durationMs(), headSha);
            pr = gitOps.createDraftPr(wt.dir(), task.githubRepo(),
                    task.githubBranch(), wt.branchName(),
                    task.title(), body);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(),
                    "gh pr create 실패: " + e.getMessage(),
                    wt.branchName(), headSha, exec.stdout());
            return;
        }

        // 6. 성공 보고
        http.postResult(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec.durationMs(), null,
                pr.url(), pr.number(), wt.branchName(), headSha, exec.stdout(),
                null, null, null, null, null
        ));
        log.info("구현 완료 + PR 생성: task={} pr=#{} {}", task.id(), pr.number(), pr.url());
    }

    private void processDeploy(WorkerTaskResponse task) {
        long start = System.currentTimeMillis();
        com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.DeployResult result;
        try {
            result = deployService.deploy(task);
        } catch (DeployService.DeployException e) {
            safePostDeployFailure(task.id(), e.getMessage(), e.getDeployLog());
            return;
        }
        long durationMs = System.currentTimeMillis() - start;
        http.postResult(task.id(), WorkerResultRequest.deployed(
                props.id(), result.url(), result.containerId(), result.hostPort(),
                result.image(), durationMs, result.log()));
        log.info("배포 완료: task={} url={}", task.id(), result.url());
    }

    private void processUndeploy(WorkerTaskResponse task) {
        try {
            deployService.undeploy(task.id());
        } catch (DeployService.DeployException e) {
            safePostDeployFailure(task.id(), "중지 실패: " + e.getMessage(), e.getDeployLog());
            return;
        }
        http.postResult(task.id(), WorkerResultRequest.undeployed(
                props.id(), "container netis-task-" + task.id() + " 중지/제거"));
        log.info("배포 중지 완료: task={}", task.id());
    }

    private void safePostDeployFailure(Long taskId, String reason, String deployLog) {
        try {
            http.postResult(taskId, WorkerResultRequest.deployFailed(props.id(), reason, deployLog));
        } catch (RestClientException e) {
            log.error("배포 실패 보고도 실패함 task={} reason={}", taskId, reason, e);
        }
    }

    private String renderImplementationPrompt(WorkerTaskResponse task, String baseSha, String branchName) {
        String tpl = props.implementationPromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = defaultImplementationPrompt();
        }
        return tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{github_branch}", task.githubBranch())
                .replace("{commit_sha}", baseSha)
                .replace("{branch_name}", branchName)
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", task.analysisMarkdown() == null ? "" : task.analysisMarkdown())
                .replace("{subtasks_json}", task.subtasksJson() == null ? "[]" : task.subtasksJson());
    }

    private static String defaultImplementationPrompt() {
        return """
                당신은 코드 구현 전문가입니다. 현재 디렉토리는 새 git worktree이며
                브랜치 '{branch_name}'가 체크아웃되어 있습니다. (베이스: {github_branch} @ {commit_sha})

                ## 작업
                {title}

                ## 요구사항
                {description}

                ## 사전 분석 결과
                {analysis_markdown}

                ## 분석된 subtasks (JSON)
                {subtasks_json}

                ## 지시
                위 분석에서 제시한 우선순위(H → M → L) 순으로 실제 코드를 수정/추가하세요.
                - 현재 디렉토리에서 직접 파일을 편집·생성하세요 (cd 금지)
                - 각 subtask 완료마다 의미 있는 변경을 누적하세요
                - 빌드/테스트는 가능하면 수행하되 실패 시 명확한 사유를 남기세요
                - 종료 직전에 `git status`로 변경 요약을 출력하세요
                """;
    }

    private String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha) {
        return "## netisMaker 자동 생성 PR\n\n"
                + "- **Task**: #" + task.id() + " " + task.title() + "\n"
                + "- **베이스**: `" + task.githubBranch() + "`\n"
                + "- **구현 SHA**: `" + headSha.substring(0, Math.min(7, headSha.length())) + "`\n"
                + "- **소요시간**: " + (durationMs / 1000) + "초\n\n"
                + "## 사전 분석\n"
                + (task.analysisMarkdown() == null ? "" : task.analysisMarkdown())
                + "\n\n---\n"
                + "🤖 Generated with [netisMaker](https://github.com/) by Claude Code.\n"
                + "리뷰 후 Ready for review로 전환하세요.\n";
    }

    private void safePostAnalysisFailure(Long taskId, String reason) {
        try {
            http.postResult(taskId, new WorkerResultRequest(
                    props.id(), TaskStatus.FAILED,
                    null, null, null, null, reason,
                    null, null, null, null, null,
                    null, null, null, null, null
            ));
        } catch (RestClientException e) {
            log.error("분석 실패 보고도 실패함 task={} reason={}", taskId, reason, e);
        }
    }

    private void safePostImplementationFailure(Long taskId, String reason,
                                               String headBranch, String headSha, String log_) {
        try {
            http.postResult(taskId, new WorkerResultRequest(
                    props.id(), TaskStatus.IMPLEMENTATION_FAILED,
                    null, null, null, null, reason,
                    null, null, headBranch, headSha, log_,
                    null, null, null, null, null
            ));
        } catch (RestClientException e) {
            log.error("구현 실패 보고도 실패함 task={} reason={}", taskId, reason, e);
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
