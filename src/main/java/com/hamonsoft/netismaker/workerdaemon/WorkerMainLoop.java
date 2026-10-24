package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

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
 *   IMPLEMENTATION 흐름 (구현 방식은 claim의 implementation 블록 — 스펙 2026-10-15, 참조 repo — 스펙 2026-10-24 §6):
 *     1) git fetch/clone (베이스 브랜치 최신화)
 *     2) 참조 repo가 있으면 입력 순서대로 고정 커밋의 참조 worktree(_refs/task-{id}/{카탈로그 id}, 대상 밖) — 하나라도
 *        실패하면 만든 참조를 지우고 구현실패(대상 worktree·claude 없음). 준비에 쓴 시간은 claude 상한에서 뺀다
 *     3) WorktreeService.create → 새 브랜치 worktree (재시도는 -rN) + 워커 임시 파일 info/exclude
 *     4) 방식 준비(계획·설계 파일 — 계획 파일 머리에 참조 절, 대신 진행 판정) → 방식별 구현 prompt(끝에 참조 절)
 *        + claude -p exec (worktree에서, --add-dir 없음)
 *        → claude가 어떻게 끝나든 참조 폴더 변경 검사(경고는 PR 본문·결과 보고, 실패면 사유 끝)
 *        → 실패하면 남은 변경을 WIP 커밋으로 push(부분 작업 보존)
 *        → 성공하면 리뷰 메모 회수, .netis/·목업 삭제, 배포 env 목록(.netis-deploy-env.json) 회수·삭제
 *     5) GitOpsService.commitAndPush(에이전트 커밋 수용, 대상만) → headSha
 *     6) GitOpsService.createDraftPr → prUrl/prNumber (본문에 방식 줄·참조 절·리뷰 메모)
 *     7) POST result (status=PR_CREATED 또는 IMPLEMENTATION_FAILED, 시도 번호·참조 경고 포함)
 *     8) 참조 worktree는 모든 경로에서 지운다(대상 worktree는 지금처럼 성공이면 지우고 실패면 남긴다)
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
    private final ResultReporter reporter;
    private final SilentLossTracker silentLossTracker;
    private final DesignResultHarvester designHarvester;
    private final TaskCloser closer;
    private final SuperpowersPlugin superpowers;

    /** 기동 알림이 성공(또는 구 API의 404)한 뒤에만 claim한다. 스케줄러 스레드와 테스트가 함께 읽는다. */
    private volatile boolean startupReported;

    /** 참조 repo 준비 시간을 재는 시계(나노초) — 테스트가 바꾼다. */
    LongSupplier nanoClock = System::nanoTime;

    public WorkerMainLoop(WorkerProperties props,
                          WorkerHttpClient http,
                          GitRepoCache repos,
                          ClaudeExecAdapter claude,
                          PromptResultParser parser,
                          WorkerMcpSupport mcps,
                          WorktreeService worktrees,
                          GitOpsService gitOps,
                          DeployService deployService,
                          ResultReporter reporter,
                          SilentLossTracker silentLossTracker,
                          DesignResultHarvester designHarvester,
                          TaskCloser closer,
                          SuperpowersPlugin superpowers) {
        this.props = props;
        this.http = http;
        this.repos = repos;
        this.claude = claude;
        this.parser = parser;
        this.mcps = mcps;
        this.worktrees = worktrees;
        this.gitOps = gitOps;
        this.deployService = deployService;
        this.reporter = reporter;
        this.silentLossTracker = silentLossTracker;
        this.designHarvester = designHarvester;
        this.closer = closer;
        this.superpowers = superpowers;
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.heartbeat-interval-seconds:10} * 1000}")
    public void sendHeartbeat() {
        try {
            http.heartbeat(new WorkerHeartbeatRequest(
                    props.id(), hostname(), props.version(), null, null, mcps.getServerNames(),
                    silentLossTracker.currentCount()));
        } catch (RestClientException e) {
            log.warn("heartbeat 실패: {}", e.getMessage());
        }
    }

    /**
     * 첫 claim 전에 "방금 기동했다"를 API에 알린다(스펙 2026-10-23 §3.5). 성공할 때까지 claim하지 않는다.
     * dead-letter에 보고 대기 중인 작업 id는 함께 보내 API가 회수하지 않게 한다(곧 재전송된다).
     */
    boolean ensureStartupReported() {
        if (startupReported) return true;
        List<Long> pendingIds = silentLossTracker.snapshotPending().stream()
                .map(SilentLossTracker.PendingEntry::taskId)
                .distinct()
                .toList();
        try {
            Optional<WorkerStartupResponse> res = http.startup(pendingIds);
            if (res.isPresent()) {
                WorkerStartupResponse body = res.get();
                log.info("기동 정합 완료: 회수 {}, 건너뜀 {}",
                        body.recovered() == null ? List.of() : body.recovered(),
                        body.skipped() == null ? List.of() : body.skipped());
            } else {
                log.warn("API가 /worker/startup을 모른다 — 기동 정합 건너뜀");
            }
            startupReported = true;
            return true;
        } catch (RestClientException e) {
            log.warn("기동 알림 실패(다음 주기에 재시도): {}", e.getMessage());
            return false;
        }
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.poll-interval-seconds:5} * 1000}")
    public void pollAndProcess() {
        if (!ensureStartupReported()) return;
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
                case DESIGN -> processDesign(task);
                case CLOSE -> closer.process(task);
                default -> processAnalysis(task);
            }
        } catch (Throwable t) {
            // Throwable을 그대로 넘기면 slf4j가 메시지+cause 체인을 마스킹 없이 찍는다
            // (git/gh 실패 메시지에 인증 URL이 실려 있을 수 있음) → 스택트레이스를 문자열로 떠서
            // 마스킹한 뒤 찍는다. 프레임/cause 체인은 그대로 남아 진단성 손실이 없다.
            log.error("작업 처리 중 예외 task={}: {}", task.id(), safeReason(stackTrace(t)));
            switch (task.kind()) {
                case IMPLEMENTATION -> safePostImplementationFailure(task.id(), implementationAttemptOf(task),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        null, null, null);
                case DEPLOY, UNDEPLOY -> safePostDeployFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
                case DESIGN -> safePostDesignFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
                case CLOSE -> closer.reportFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                default -> safePostAnalysisFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private void processAnalysis(WorkerTaskResponse task) throws Exception {
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.repoRef(), task.githubBranch());
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
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(),
                    false, task.model(), task.effort());
        } catch (Exception e) {
            safePostAnalysisFailure(task.id(), "claude exec 실패: " + e.getMessage());
            return;
        }
        if (exec.exitCode() != 0) {
            String tail = exec.stdout() == null ? "" : exec.stdout();
            if (tail.length() > 2000) tail = "…" + tail.substring(tail.length() - 2000);
            safePostAnalysisFailure(task.id(), "claude exit=" + exec.exitCode() + "\n" + tail, usageOf(exec));
            return;
        }

        PromptResultParser.ParseResult parsed;
        try {
            parsed = parser.parse(exec.stdout());
        } catch (PromptResultParser.ParseException e) {
            safePostAnalysisFailure(task.id(), "파싱 실패: " + e.getMessage(), usageOf(exec));
            return;
        }

        reporter.reportTerminal(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.COMPLETED,
                parsed.markdown(),
                parsed.subtasksJson(),
                exec.stdout(),
                exec.durationMs(),
                null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                usageOf(exec)
        ));
        log.info("분석 완료: id={} duration={}ms warnings={}",
                task.id(), exec.durationMs(), parsed.warnings());
    }

    private void processImplementation(WorkerTaskResponse task) throws Exception {
        WorkerTaskResponse.ImplementationSpec spec = task.implementation();
        ImplementationMode requested = ImplementationMode.fromWireOrDirect(spec == null ? null : spec.mode());
        int attempt = implementationAttemptOf(task);

        // 1. 베이스 브랜치 최신화 (worktree add 시 origin/{base} 참조)
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.repoRef(), task.githubBranch());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "레포 fetch 실패: " + e.getMessage(), null, null, null);
            return;
        }

        // 2. 참조 repo 준비 — 입력 순서대로 하나씩, 대상 worktree보다 먼저 (스펙 2026-10-24 §6.1~§6.3).
        //    참조가 없으면 아무것도 부르지 않고 claude 상한도 그대로다
        List<WorkerTaskResponse.TaskRefRepo> refs = refsOf(task);
        List<WorkerTaskResponse.TaskRefRepo> attempted = new ArrayList<>();
        List<WorktreeService.RefWorktree> ready = new ArrayList<>();
        Duration limit = implementationTimeoutOf(task, props.implementationTimeout());
        Duration claudeLimit = limit;
        if (!refs.isEmpty()) {
            long started = nanoClock.getAsLong();
            long last = started;
            for (WorkerTaskResponse.TaskRefRepo ref : refs) {
                attempted.add(ref);   // 실패한 참조도 남은 폴더를 지운다
                try {
                    ready.add(worktrees.createRefWorktree(task.id(), ref));
                } catch (Exception e) {
                    // 참조를 다 준비하지 못하면 claude를 실행하지 않는다 — 대상 worktree도 만들지 않는다
                    discardRefWorktrees(task.id(), attempted);
                    safePostImplementationFailure(task.id(), attempt,
                            RefRepoPrompts.prepFailureReason(ref, e), null, null, null);
                    return;
                }
                long now = nanoClock.getAsLong();
                log.info("참조 repo 준비: task={} {}({})@{} {}ms", task.id(), ref.alias(), ref.branch(),
                        RefRepoPrompts.sha7(ref.sha()), (now - last) / 1_000_000);
                last = now;
            }
            Duration prep = Duration.ofNanos(last - started);
            claudeLimit = limit.minus(prep);
            if (claudeLimit.isZero() || claudeLimit.isNegative()) {
                discardRefWorktrees(task.id(), attempted);
                safePostImplementationFailure(task.id(), attempt, "시간 초과: 참조 repo 준비에 " + prep.toSeconds()
                        + "초를 써 구현 상한(" + limit.toMinutes() + "분)이 남지 않았습니다", null, null, null);
                return;
            }
        }

        // 3~7. 참조 폴더는 어떤 경로로 끝나든 지운다 — 예외도 바깥 catch의 보고보다 먼저 정리된다
        try {
            implementInWorktree(task, requested, attempt, repo, ready, claudeLimit);
        } finally {
            discardRefWorktrees(task.id(), attempted);
        }
    }

    /** 대상 worktree에서 구현 → 커밋·push → PR → 결과 보고. ready는 준비된 참조(없으면 빈 목록), claudeLimit은 claude 상한. */
    private void implementInWorktree(WorkerTaskResponse task, ImplementationMode requested, int attempt,
                                     GitRepoCache.CheckedOutRepo repo, List<WorktreeService.RefWorktree> ready,
                                     Duration claudeLimit) throws Exception {
        // 3. worktree + 새 브랜치 생성 — 재시도(2회째부터)는 -rN 브랜치 (스펙 2026-10-15 §6.1)
        WorktreeService.CreatedWorktree wt;
        try {
            wt = worktrees.create(repo.dir(), GitRemotes.localKey(task.repoRef()),
                    task.githubBranch(), task.id(), task.title(), attempt);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "worktree 생성 실패: " + e.getMessage(), null, null, null);
            return;
        }
        Path wtPath = wt.dir().toPath();

        // 승인된 디자인이 있으면 목업을 워크트리에 배치 (참조용 — 커밋 전 삭제)
        File designDir = null;
        if (task.mockupFilesJson() != null && !task.mockupFilesJson().isBlank()) {
            try {
                designDir = new File(wt.dir(), ".design");
                writeMockupFiles(designDir, task.mockupFilesJson());
                java.nio.file.Files.writeString(new File(designDir, "DESIGN.md").toPath(),
                        nvl(task.designMarkdown(), ""));
            } catch (Exception e) {
                log.warn("목업 배치 실패 (디자인 없이 진행): {}", e.getMessage());
                designDir = null;
            }
        }

        // 4. 구현 방식 준비 — 임시 파일 exclude, 계획·설계 파일, 대신 진행 판정 (스펙 2026-10-15 §6.1·§6.5)
        boolean excludesReady = worktrees.ensureWorkerExcludes(wt.dir());
        boolean hasPlan = task.planMarkdown() != null && !task.planMarkdown().isBlank();
        String planText = requested == ImplementationMode.SDD && hasPlan
                ? ImplementationScratch.normalizeTaskHeadings(task.planMarkdown())
                : task.planMarkdown();
        boolean planWritten = false;
        boolean designWritten = false;
        if (requested != ImplementationMode.DIRECT && hasPlan) {
            // 참조 절은 파일 머리에만 — 태스크 수 판정(countTaskHeadings)에는 계획 본문만 넘긴다 (스펙 2026-10-24 §6.5)
            planWritten = writeScratch(wtPath, ImplementationScratch.planPath(task.id()),
                    RefRepoPrompts.planFileHeader(ready) + planText);
            if (requested == ImplementationMode.SDD
                    && task.analysisMarkdown() != null && !task.analysisMarkdown().isBlank()) {
                designWritten = writeScratch(wtPath, ImplementationScratch.designPath(task.id()),
                        task.analysisMarkdown());
            }
        }
        // 플러그인은 이 구현에서 한 번만 확인해 판정과 --plugin-dir이 같은 답을 쓴다(그 사이 폴더가 지워져도 orElseThrow가 안 터진다)
        Optional<Path> sddPlugin = superpowers.sddReadyPath();
        ImplementationModeResolver.Resolution mode = ImplementationModeResolver.resolve(requested,
                new ImplementationModeResolver.Inputs(sddPlugin.isPresent(), excludesReady,
                        hasPlan, planWritten, hasPlan ? ImplementationScratch.countTaskHeadings(planText) : 0));
        if (mode.fellBack()) {
            log.warn("구현 방식 대신 진행: task={} {} → {} ({})",
                    task.id(), requested, mode.effective(), mode.fallbackReason());
        }
        String modeLine = ImplementationPrompts.modeLine(requested, mode.effective(), attempt, mode.fallbackReason());
        // 실패 사유는 화면에 평문으로 보인다 — 같은 내용을 마크다운 기호(`- **`) 없이 붙인다
        String fallbackNote = mode.fellBack()
                ? "\n" + ImplementationPrompts.modeNote(requested, mode.effective(), attempt, mode.fallbackReason())
                : "";

        // 4. 구현 prompt + claude exec (worktree 디렉토리에서)
        //    비대화식 모드에서 Write/Edit/Bash 차단 회피 위해 권한 우회 활성.
        //    worktree는 격리 환경이라 워커 머신 다른 곳에 영향 없음.
        //    참조 절은 템플릿 밖 DB 절 뒤에 붙는다(운영자 템플릿을 바꿔도 빠지지 않게). 참조 폴더는 절대 경로로 읽으므로
        //    --add-dir를 주지 않는다 — 참조 repo의 .claude 설정이 이 세션에 붙으면 안 된다 (스펙 2026-10-24 §6.4·§6.7)
        String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName(),
                ImplementationPrompts.planSection(task, mode.effective(), repo.commitSha(), designWritten,
                        !ready.isEmpty()),
                ImplementationPrompts.commitRule(mode.effective()))
                + DbMcpWorkerSupport.promptSection(task.dbMcp())
                + RefRepoPrompts.promptSection(ready);
        List<String> extraArgs = mode.effective() == ImplementationMode.SDD
                ? List.of("--plugin-dir", sddPlugin.orElseThrow().toString())   // SDD는 sddPlugin이 있을 때만 나온다
                : List.of();
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt.dir(), claudeLimit,
                    DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl()),
                    true, task.model(), task.effort(),
                    new ClaudeExecAdapter.ExecOptions(extraArgs,
                            gitAuthorEnv(props.gitUserName(), props.gitUserEmail())));
        } catch (Exception e) {
            String refNote = RefRepoPrompts.failureSuffix(refChangeWarnings(task.id(), ready));
            String why = e.getMessage() != null && e.getMessage().startsWith("claude timeout") ? "시간 초과" : "실행 오류";
            PartialOutcome kept = preservePartialWork(task, wt, repo.commitSha(), why);
            safePostImplementationFailure(task.id(), attempt,
                    "claude exec 실패: " + e.getMessage() + kept.reasonSuffix() + fallbackNote + refNote,
                    wt.branchName(), kept.headSha(), null);
            return;
        }
        // 참조 변경 검사 — claude가 끝나면 한 번(위 실패 경로도 같다). 경고는 PR 본문·결과 보고, 실패면 사유 끝 (스펙 §6.6)
        List<String> refWarnings = refChangeWarnings(task.id(), ready);
        String refNote = RefRepoPrompts.failureSuffix(refWarnings);
        if (exec.exitCode() != 0) {
            PartialOutcome kept = preservePartialWork(task, wt, repo.commitSha(), "비정상 종료");
            String tail = tail(exec.stdout(), 4000);
            safePostImplementationFailure(task.id(), attempt,
                    "claude exit=" + exec.exitCode() + "\n" + tail + kept.reasonSuffix() + fallbackNote + refNote,
                    wt.branchName(), kept.headSha(), exec.stdout(), usageOf(exec));
            return;
        }

        // 리뷰 메모는 PR 본문으로 회수하고 .netis/(계획·설계·메모)는 커밋 전에 지운다 — exclude가 실패했어도 안 섞이게
        Optional<String> reviewNotes = ImplementationScratch.readReviewNotes(wtPath);
        ImplementationScratch.deleteRoot(wtPath);

        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteDesignDir(designDir.toPath());

        // 배포 env 목록(.netis-deploy-env.json)도 커밋 전에 회수·삭제 — 없거나 틀려도 구현은 계속 (스펙 2026-10-07 §4.3)
        List<EnvTemplateItem> envTemplate = DeployEnvManifest.harvest(wtPath);

        // 5. 변경 commit + push(대상만) — 에이전트가 만든 커밋(SDD 태스크 커밋)도 받아들인다
        String headSha;
        try {
            String commitMsg = "feat: " + task.title()
                    + "\n\n" + "task #" + task.id()
                    + "\n\nCo-Authored-By: netisMaker <" + props.gitUserEmail() + ">";
            headSha = gitOps.commitAndPush(wt.dir(), task.repoRef(), wt.branchName(), commitMsg, repo.commitSha());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "commit/push 실패: " + e.getMessage() + fallbackNote + refNote,
                    wt.branchName(), null, exec.stdout(), usageOf(exec));
            return;
        }

        // 6. Draft PR/MR
        GitOpsService.PrInfo pr;
        try {
            String body = renderPrBody(task, exec.durationMs(), headSha, modeLine, reviewNotes.orElse(null),
                    refWarnings);
            pr = gitOps.createDraftPr(wt.dir(), task.repoRef(),
                    task.githubBranch(), wt.branchName(),
                    task.title(), body);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "PR/MR 생성 실패: " + e.getMessage() + fallbackNote + refNote,
                    wt.branchName(), headSha, exec.stdout(), usageOf(exec));
            return;
        }

        // 7. 성공 보고 — 시도 번호로 이전 시도의 늦은 보고와 구분 (스펙 2026-10-15 §5.6), 참조 변경 경고는 이력 행으로
        //    (스펙 2026-10-24 §4.8 — 가림·원소당 상한은 ResultReporter가 보내기 직전 masked()가 맡는다)
        reporter.reportTerminal(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec.durationMs(), null,
                pr.url(), pr.number(), wt.branchName(), headSha, exec.stdout(),
                null, null, null, null, null,
                null, null, null, null,
                usageOf(exec),
                envTemplate,
                attempt,
                refWarnings.isEmpty() ? null : refWarnings));
        log.info("구현 완료 + PR 생성: task={} pr=#{} {} (방식 {}, {}회째, 배포 env 템플릿 {}개)",
                task.id(), pr.number(), pr.url(), mode.effective(), attempt, envTemplate.size());
        // 산출물은 원격 브랜치·PR에 있다 — worktree는 더 쓰지 않으므로 바로 정리(실패해도 결과 무관, 주기 정리가 재시도)
        worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.TASK, task.id());
    }

    /** claim의 시도 번호 — 없거나(옛 API) 1보다 작으면 1. */
    static int implementationAttemptOf(WorkerTaskResponse task) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s == null || s.attempt() == null || s.attempt() < 1 ? 1 : s.attempt();
    }

    /** claim의 상한(분) — 없으면(옛 API) 워커 설정 IMPLEMENTATION_TIMEOUT. */
    static Duration implementationTimeoutOf(WorkerTaskResponse task, Duration fallback) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s != null && s.timeoutMinutes() != null && s.timeoutMinutes() > 0
                ? Duration.ofMinutes(s.timeoutMinutes())
                : fallback;
    }

    /** claim의 참조 repo — 없으면(구버전 API·다른 kind) 빈 목록. */
    static List<WorkerTaskResponse.TaskRefRepo> refsOf(WorkerTaskResponse task) {
        return task.refRepos() == null ? List.of() : task.refRepos();
    }

    /** 준비된 참조 폴더마다 변경 경고(없는 것은 건너뜀). 참조가 없으면 아무것도 부르지 않고 빈 목록. */
    private List<String> refChangeWarnings(long taskId, List<WorktreeService.RefWorktree> ready) {
        List<String> warnings = new ArrayList<>();
        for (WorktreeService.RefWorktree rw : ready) {
            worktrees.refChangeWarning(rw).ifPresent(warnings::add);
        }
        if (!warnings.isEmpty()) log.warn("참조 repo 변경 감지(PR에는 포함 안 됨): task={} {}", taskId, warnings);
        return List.copyOf(warnings);
    }

    /** 참조 폴더 정리(best-effort — discardRefWorktree는 실패를 경고로만 남긴다). */
    private void discardRefWorktrees(long taskId, List<WorkerTaskResponse.TaskRefRepo> refs) {
        for (WorkerTaskResponse.TaskRefRepo ref : refs) {
            worktrees.discardRefWorktree(taskId, ref);
        }
    }

    /**
     * 에이전트 커밋(SDD 태스크 커밋·실수 커밋)도 워커와 같은 작성자로 — 이 PC엔 전역 git 작성자가 없다 (스펙 §6.1).
     * 비었거나 없는 값은 넣지 않는다: ExecOptions가 Map.copyOf라 null 값이 하나라도 있으면 NPE로 구현이 죽는다
     * (운영에선 WorkerProperties가 기본값을 채우지만, 레코드를 목으로 만든 테스트는 null을 돌려준다).
     */
    static Map<String, String> gitAuthorEnv(String name, String email) {
        Map<String, String> env = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) {
            env.put("GIT_AUTHOR_NAME", name);
            env.put("GIT_COMMITTER_NAME", name);
        }
        if (email != null && !email.isBlank()) {
            env.put("GIT_AUTHOR_EMAIL", email);
            env.put("GIT_COMMITTER_EMAIL", email);
        }
        return env;
    }

    private static boolean writeScratch(Path worktree, String relPath, String content) {
        try {
            ImplementationScratch.write(worktree, relPath, content);
            return true;
        } catch (Exception e) {
            log.warn("구현 임시 파일 쓰기 실패 {}: {}", relPath, e.getMessage());
            return false;
        }
    }

    /** claude가 중간에 끝났을 때 — 임시 파일을 지우고 남은 변경을 WIP 커밋으로 push (스펙 2026-10-15 §6.6). */
    private PartialOutcome preservePartialWork(WorkerTaskResponse task, WorktreeService.CreatedWorktree wt,
                                               String baseSha, String why) {
        // git add -A가 .netis/·.design/·배포 env 목록을 WIP 커밋에 담지 않도록 먼저 지운다
        ImplementationScratch.deleteAll(wt.dir().toPath());
        GitOpsService.PartialWork w = gitOps.preservePartialWork(wt.dir(), task.repoRef(), wt.branchName(), baseSha,
                "wip: 구현 중단(" + why + ") — task #" + task.id());
        return PartialOutcome.of(w, wt.branchName());
    }

    /**
     * 실패 사유에 붙일 문구와 보고할 head SHA. head SHA는 push한 때만 — API가 head_sha 있음 = 원격에 있음으로 읽는다
     * (재시도 이력의 "이전 브랜치" 문구, 스펙 §5.5).
     */
    record PartialOutcome(String reasonSuffix, String headSha) {
        static PartialOutcome of(GitOpsService.PartialWork w, String branch) {
            if (w == null) return new PartialOutcome("", null);
            if (w.pushed()) {
                // WIP 커밋이 실패했으면 에이전트 커밋만 올라갔다 — 남은 변경이 이 PC에만 있다는 것도 말한다
                return new PartialOutcome(" — 지금까지 작업(커밋 " + w.commits() + "개)은 브랜치 `" + branch
                        + "`에 올려 둠"
                        + (w.wipError() == null ? ""
                            : " (WIP 커밋 실패로 커밋 안 된 변경은 worktree에만 남음: " + w.wipError() + ")"),
                        w.headSha());
            }
            if (w.error() != null) {
                return new PartialOutcome((w.commits() > 0
                        ? " — 부분 작업 push 실패(worktree에만 남음): "
                        : " — 부분 작업 보존 실패(worktree에만 남음): ") + w.error()
                        + (w.wipError() == null ? "" : " (WIP 커밋도 실패: " + w.wipError() + ")"), null);
            }
            return new PartialOutcome("", null);
        }
    }

    private void processDesign(WorkerTaskResponse task) throws Exception {
        // 순수 설정 전제조건 — fetch/worktree 생성 전에 검사해 낭비·워크트리 잔존 없이
        // NPE 대신 명확한 사유로 디자인실패 보고 (설정 실수 진단성)
        if (props.designPromptTemplate() == null || props.designPromptTemplate().isBlank()) {
            safePostDesignFailure(task.id(),
                    "design-prompt-template 미설정 — application-worker.yml의 "
                            + "netis-maker.worker.design-prompt-template을 확인하세요", null);
            return;
        }

        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.repoRef(), task.githubBranch());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "레포 fetch 실패: " + e.getMessage(), null);
            return;
        }

        File wt;
        try {
            wt = worktrees.createForDesign(repo.dir(), GitRemotes.localKey(task.repoRef()),
                    task.githubBranch(), task.id());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "design worktree 생성 실패: " + e.getMessage(), null);
            return;
        }

        String prompt = renderDesignPrompt(task) + DbMcpWorkerSupport.promptSection(task.dbMcp());
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt, props.designTimeout(),
                    DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl()),
                    true, task.model(), task.effort());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "claude exec 실패: " + e.getMessage(), null);
            return;
        }
        if (exec.exitCode() != 0) {
            safePostDesignFailure(task.id(),
                    "claude exit=" + exec.exitCode() + "\n" + tail(exec.stdout(), 4000),
                    exec.stdout(), usageOf(exec));
            return;
        }

        DesignResultHarvester.HarvestResult harvest;
        try {
            harvest = designHarvester.harvest(wt);
        } catch (DesignResultHarvester.HarvestException e) {
            safePostDesignFailure(task.id(), e.getMessage(), exec.stdout(), usageOf(exec));
            return;
        }

        reporter.reportTerminal(task.id(), WorkerResultRequest.designReview(
                props.id(), harvest.designMarkdown(), harvest.mockupFilesJson(),
                harvest.designProjectId(), harvest.designUrl(),
                exec.stdout(), exec.durationMs(), usageOf(exec)));
        // 수확 완료 후 design worktree는 best-effort 정리 (산출물은 DB로 감 — 보존 불필요)
        worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.DESIGN, task.id());
        log.info("디자인 생성 완료: task={} screens 포함, url={}", task.id(), harvest.designUrl());
    }

    private String renderDesignPrompt(WorkerTaskResponse task) {
        String tpl = props.designPromptTemplate();
        return tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{task_id}", String.valueOf(task.id()))
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", nvl(task.analysisMarkdown(), ""))
                .replace("{subtasks_json}", nvl(task.subtasksJson(), "[]"))
                .replace("{previous_design_markdown}", nvl(task.designMarkdown(), "(없음 — 첫 생성)"))
                .replace("{feedback_history}", nvl(task.feedbackHistoryJson(), "[]"))
                .replace("{design_system_project_id}", nvl(task.designSystemProjectId(), "없음"))
                .replace("{design_output_project_id}", nvl(task.designOutputProjectId(), "없음"));
    }

    private static String nvl(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private void safePostDesignFailure(Long taskId, String reason, String log_) {
        safePostDesignFailure(taskId, reason, log_, null);
    }

    private void safePostDesignFailure(Long taskId, String reason, String log_,
                                       WorkerResultRequest.UsageReport usage) {
        reporter.reportTerminal(taskId, WorkerResultRequest.designFailed(props.id(), safeReason(reason), log_, usage));
    }

    private void processDeploy(WorkerTaskResponse task) {
        long start = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Consumer<String> sink =
                line -> http.postDeployLog(task.id(), seq.getAndIncrement(), line + "\n");
        com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.DeployResult result;
        try {
            result = deployService.deploy(task, sink);
        } catch (DeployService.DeployException e) {
            safePostDeployFailure(task.id(), e.getMessage(), e.getDeployLog());
            return;
        }
        long durationMs = System.currentTimeMillis() - start;
        reporter.reportTerminal(task.id(), WorkerResultRequest.deployed(
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
        reporter.reportTerminal(task.id(), WorkerResultRequest.undeployed(
                props.id(), "container netis-task-" + task.id() + " 중지/제거"));
        log.info("배포 중지 완료: task={}", task.id());
    }

    private void safePostDeployFailure(Long taskId, String reason, String deployLog) {
        reporter.reportTerminal(taskId, WorkerResultRequest.deployFailed(props.id(), safeReason(reason), deployLog));
    }

    private String renderImplementationPrompt(WorkerTaskResponse task, String baseSha, String branchName,
                                              String planSection, String commitRule) {
        String tpl = props.implementationPromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = defaultImplementationPrompt();
        }
        return fillImplementationPrompt(tpl, task, baseSha, branchName, planSection, commitRule);
    }

    /**
     * 구현 프롬프트 끝에 항상 붙는 배포 env 목록 지시 (스펙 2026-10-07 §4.1). yml·기본 템플릿 공통이고 운영자가
     * 템플릿을 바꿔도 빠지지 않는다. 파일 형식은 DeployEnvManifest가 읽는 계약과 같아야 한다.
     */
    static final String DEPLOY_ENV_INSTRUCTION = """

            ## 배포 환경변수 목록 (마지막 단계 — 필수)
            구현을 마친 뒤, 이 프로젝트를 컨테이너로 실행할 때 외부에서 넣어 줘야 하는 환경변수의 **이름**을
            현재 디렉터리(레포 루트)의 `.netis-deploy-env.json`에 아래 형식으로 쓰세요.
            이 파일은 워커가 읽은 뒤 지우며 커밋되지 않습니다.

            {"vars":[{"key":"SPRING_DATASOURCE_URL","description":"DB 접속 JDBC URL","secret":false,"required":true}]}

            - 값은 절대 쓰지 마세요(이름·설명만). 레포에 있는 실제 비밀번호·토큰을 옮겨 적지 마세요.
            - 컨테이너는 production용 Dockerfile(레포에 없으면 배포 때 생성)로 빌드·실행됩니다. description과 required는
              운영 실행 기준으로 쓰고, 로컬 개발 기본값(예: "기본값 dev")은 안내하지 마세요.
            - 실행 프로파일을 고르는 변수(SPRING_PROFILES_ACTIVE 등)는 Dockerfile이 정하므로 목록에 넣지 마세요.
              꼭 필요하면 그 프로파일의 설정 파일이 빌드 산출물(jar·war)에 실제로 들어가는지(build.gradle의 exclude 등)
              확인하고, 들어가는 프로파일만 안내하세요.
            - 대상: DB 접속(URL·계정·암호), 시크릿(JWT 키 등), 외부 API 주소·키처럼 배포 환경마다 달라지는 값.
            - 코드에 안전한 기본값이 있어 비워 둬도 실행되면 required=false.
            - Spring Boot는 설정 키를 relaxed binding 환경변수 이름으로 쓰세요(spring.datasource.url → SPRING_DATASOURCE_URL).
            - 암호·토큰·키는 secret=true.
            - description은 한국어 한 줄.
            - 필요한 환경변수가 없으면 {"vars":[]}를 쓰세요.
            """;

    /** 직접 방식 조각으로 채운다 — 기존 호출부·테스트용. */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName) {
        return fillImplementationPrompt(tpl, task, baseSha, branchName,
                renderPlanSection(task), ImplementationPrompts.DIRECT_COMMIT_RULE);
    }

    /**
     * 구현 프롬프트 템플릿의 placeholder를 채운다(yml 템플릿·기본 템플릿 공용). planSection·commitRule은 방식별
     * (ImplementationPrompts, 스펙 2026-10-15 §6). 끝에 배포 env 목록 지시를 붙인다.
     * {commit_rule}은 계획·디자인 본문을 넣기 전에 치환한다(본문 안의 같은 글자를 건드리지 않게).
     */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName,
                                           String planSection, String commitRule) {
        return tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{github_branch}", task.githubBranch())
                .replace("{commit_sha}", baseSha)
                .replace("{branch_name}", branchName)
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", task.analysisMarkdown() == null ? "" : task.analysisMarkdown())
                .replace("{subtasks_json}", task.subtasksJson() == null ? "[]" : task.subtasksJson())
                .replace("{commit_rule}", commitRule)
                .replace("{design_section}", renderDesignSection(task))
                .replace("{plan_section}", planSection)
                + DEPLOY_ENV_INSTRUCTION;
    }

    /** 승인된 디자인이 있으면 구현 프롬프트에 삽입할 블록, 없으면 빈 문자열 (기존 작업 불변). */
    private static String renderDesignSection(WorkerTaskResponse task) {
        if (task.designMarkdown() == null || task.designMarkdown().isBlank()) return "";
        return """

                ## 확정된 디자인 (반드시 이 디자인 기준으로 구현)
                아래 디자인 문서와 `.design/` 디렉토리의 화면 목업 HTML이 승인된 확정 디자인입니다.
                화면 구현 시 목업의 레이아웃·색·타이포·컴포넌트 구조를 그대로 따르세요.
                `.design/`은 참조용이며 커밋하지 마세요.

                """ + task.designMarkdown();
    }

    /**
     * 직접 방식의 {plan_section} — 인터뷰에서 확정된 플랜이 있으면 구현 프롬프트에 삽입할 블록, 없으면 빈 문자열
     * (인터뷰 없이 온 작업 불변). writing-plans 플랜에는 "git commit" 단계가 들어 있는데, 직접·리뷰 방식은 워커가 마지막에
     * 한 번에 커밋하므로 커밋 단계는 건너뛰라고 지시한다. 플랜 머리의 "REQUIRED SUB-SKILL: subagent-driven-development"는
     * 따르지 말라고 한다 — 서브에이전트 방식은 관리자가 고르는 별도 방식(SDD)이고, 그때는 ImplementationPrompts가
     * 플러그인(--plugin-dir)과 SDD 전용 지시를 준다 (스펙 2026-10-15 §6.2~§6.4).
     */
    static String renderPlanSection(WorkerTaskResponse task) {
        return renderPlanSection(task,
                "계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) 없이 이 세션에서 직접 구현하세요.");
    }

    /**
     * 플랜 절의 공통 틀 — 머리·소개·커밋/검증 지시 두 줄은 직접·리뷰 방식이 같고, 마지막 줄(서브에이전트 사용 지시)만
     * 방식이 정한다(리뷰 방식은 ImplementationPrompts가 자기 줄을 넘긴다). lastBullet은 앞의 "- " 없이 문장만.
     * 플랜이 없으면 빈 문자열.
     */
    static String renderPlanSection(WorkerTaskResponse task, String lastBullet) {
        if (task.planMarkdown() == null || task.planMarkdown().isBlank()) return "";
        return """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 이 계획을 기준으로 구현)
                아래는 요청자와의 인터뷰로 확정된 구현 계획입니다. 작업 순서·범위·파일 위치는 이 계획을 따르세요.
                - 계획 안의 git commit / push 단계는 실행하지 마세요 — 커밋은 워커가 마지막에 한 번에 처리합니다.
                - 계획의 확인·검증 단계(테스트, grep 등)는 가능하면 수행하고 결과를 출력에 남기세요.
                - %s

                """.formatted(lastBullet) + task.planMarkdown() + "\n";
    }

    static String defaultImplementationPrompt() {
        return """
                당신은 코드 구현 전문가입니다. 현재 디렉토리는 새 git worktree이며
                브랜치 '{branch_name}'가 체크아웃되어 있습니다. (베이스: {github_branch} @ {commit_sha})

                ## 작업
                {title}

                ## 요구사항
                {description}
                {design_section}
                {plan_section}
                ## 사전 분석 결과
                {analysis_markdown}

                ## 분석된 subtasks (JSON)
                {subtasks_json}

                ## 지시
                위 분석에서 제시한 우선순위(H → M → L) 순으로 실제 코드를 수정/추가하세요.
                - 현재 디렉토리에서 직접 파일을 편집·생성하세요 (cd 금지)
                - {commit_rule}
                - 빌드/테스트는 가능하면 수행하되 실패 시 명확한 사유를 남기세요
                - 종료 직전에 `git status`로 변경 요약을 출력하세요
                """;
    }

    /** PR/MR 본문 전체 상한(자) — GitHub 본문 한도 65,536자보다 낮게 잡아 머리·꼬리가 조금 달라져도 넘지 않게 한다. */
    static final int PR_BODY_MAX_CHARS = 60_000;
    /** 줄인 절의 끝 표시 — 리뷰 메모 상한(ImplementationScratch.readReviewNotes)과 같은 문구. */
    static final String PR_BODY_CUT_MARK = "\n\n…(이하 생략)";

    /** 참조 변경 경고 없는 PR/MR 본문 — 기존 호출부·테스트용. */
    static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha,
                               String modeLine, String reviewNotes) {
        return renderPrBody(task, durationMs, headSha, modeLine, reviewNotes, List.of());
    }

    /**
     * PR/MR 본문. 전체가 PR_BODY_MAX_CHARS를 넘으면 사전 분석(설계 문서)을 먼저 줄이고, 그래도 넘으면 리뷰 메모를 줄인다 —
     * 줄인 절은 끝에 `…(이하 생략)`. 머리 줄(방식 줄 포함)·참조 절과 꼬리는 그대로 둔다. 본문이 길면 이미 push한 구현이
     * "PR/MR 생성 실패"로 끝나기 때문이다(GitHub 65,536자 한도). 참조 절(고정 커밋·변경 경고, 스펙 2026-10-24 §6.6)은
     * 머리 다음에 오고, 참조가 없으면 비어 본문이 예전과 같다.
     */
    static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha,
                               String modeLine, String reviewNotes, List<String> refWarnings) {
        boolean gitlab = task.repoRef().isGitlab();
        boolean hasDesign = task.designMarkdown() != null && !task.designMarkdown().isBlank();
        // 마크다운 링크를 깨는 값(공백, ')', 개행 등) 방어 — 형태가 이상하면 링크만 생략
        boolean hasDesignUrl = task.designUrl() != null
                && task.designUrl().matches("https?://[^\\s)]+");
        String designLine = !hasDesign ? ""
                : "- **디자인**: 확정 디자인 기반 구현"
                + (hasDesignUrl ? " — [Claude Design 목업](" + task.designUrl() + ")" : "")
                + " (작업 상세의 디자인 카드 참고)\n";
        String head = "## netisMaker 자동 생성 " + (gitlab ? "MR" : "PR") + "\n\n"
                + "- **Task**: #" + task.id() + " " + task.title() + "\n"
                + "- **베이스**: `" + task.githubBranch() + "`\n"
                + "- **구현 SHA**: `" + headSha.substring(0, Math.min(7, headSha.length())) + "`\n"
                + (modeLine == null ? "" : modeLine)
                + designLine
                + "- **소요시간**: " + (durationMs / 1000) + "초\n\n";
        String tail = "---\n"
                + "🤖 Generated with netisMaker by Claude Code.\n"
                + (gitlab ? "리뷰 후 Draft 표시를 해제하세요.\n" : "리뷰 후 Ready for review로 전환하세요.\n");
        String refSection = RefRepoPrompts.prSection(refsOf(task), refWarnings);
        String analysisTitle = "## 사전 분석\n";
        String notesTitle = "## 리뷰 메모\n";

        String analysis = task.analysisMarkdown() == null ? "" : task.analysisMarkdown();
        String notes = reviewNotes;
        // 절 제목·구분 줄바꿈·머리·참조 절·꼬리를 뺀, 분석과 메모가 나눠 쓸 수 있는 글자 수
        int room = PR_BODY_MAX_CHARS - head.length() - refSection.length() - tail.length()
                - analysisTitle.length() - 2
                - (notes == null ? 0 : notesTitle.length() + 2);
        int notesLength = notes == null ? 0 : notes.length();
        if (analysis.length() + notesLength > room) {
            analysis = cutForPrBody(analysis, room - notesLength);
            if (notes != null && analysis.length() + notes.length() > room) {
                notes = cutForPrBody(notes, room - analysis.length());
            }
        }
        return head
                + refSection
                + analysisTitle + analysis + "\n\n"
                + (notes == null ? "" : notesTitle + notes + "\n\n")
                + tail;
    }

    /**
     * text를 max자 이내로 줄이고 끝에 표시를 붙인다. 표시 하나는 항상 들어가게 max를 그 길이 아래로 내리지 않는다
     * (남은 자리가 없어도 절이 아무 말 없이 비지는 않게). 이모지 같은 서로게이트 쌍은 가운데서 자르지 않는다.
     */
    private static String cutForPrBody(String text, int max) {
        int limit = Math.max(max, PR_BODY_CUT_MARK.length());
        if (text.length() <= limit) return text;
        int keep = limit - PR_BODY_CUT_MARK.length();
        if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) keep--;
        return text.substring(0, keep) + PR_BODY_CUT_MARK;
    }

    private void safePostAnalysisFailure(Long taskId, String reason) {
        safePostAnalysisFailure(taskId, reason, null);
    }

    private void safePostAnalysisFailure(Long taskId, String reason, WorkerResultRequest.UsageReport usage) {
        reporter.reportTerminal(taskId, new WorkerResultRequest(
                props.id(), TaskStatus.FAILED,
                null, null, null, null, safeReason(reason),
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, usage));
    }

    private void safePostImplementationFailure(Long taskId, int attempt, String reason,
                                               String headBranch, String headSha, String log_) {
        safePostImplementationFailure(taskId, attempt, reason, headBranch, headSha, log_, null);
    }

    private void safePostImplementationFailure(Long taskId, int attempt, String reason,
                                               String headBranch, String headSha, String log_,
                                               WorkerResultRequest.UsageReport usage) {
        reporter.reportTerminal(taskId, new WorkerResultRequest(
                props.id(), TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, safeReason(reason),
                null, null, headBranch, headSha, log_,
                null, null, null, null, null,
                null, null, null, null, usage).withImplementationAttempt(attempt));
    }

    /** 실패 사유를 보고/로그로 내보내기 직전 경계에서 자격증명을 마스킹한다(F2). */
    static String safeReason(String reason) {
        return GitRemotes.mask(reason);
    }

    /**
     * 스택트레이스를 문자열로. slf4j에 Throwable을 직접 넘기지 않고 이 문자열을 마스킹해 찍기 위한 것 —
     * 직접 넘기면 메시지·cause 메시지가 마스킹 없이 나간다.
     */
    static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    /** ExecResult → 보고 usage. envelope 파싱 실패(usage null)면 null — 수집 생략. */
    private static WorkerResultRequest.UsageReport usageOf(ClaudeExecAdapter.ExecResult exec) {
        ClaudeExecAdapter.Usage u = exec == null ? null : exec.usage();
        if (u == null) return null;
        return new WorkerResultRequest.UsageReport(u.costUsd(),
                u.inputTokens(), u.outputTokens(), u.cacheCreationTokens(), u.cacheReadTokens());
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    /** mockup_files JSON([{path,title,html}])을 dir 아래 파일로 풀어놓는다. path는 dir 내부로 강제. */
    private void writeMockupFiles(File dir, String mockupFilesJson) throws java.io.IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(mockupFilesJson);
        java.nio.file.Path base = dir.toPath().toAbsolutePath().normalize();
        for (var node : root) {
            String rel = node.path("path").asText("");
            String html = node.path("html").asText("");
            if (rel.isBlank()) continue;
            java.nio.file.Path target = base.resolve(rel).normalize();
            if (!target.startsWith(base)) continue; // path traversal 방어
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, html);
        }
    }

    /**
     * 커밋 전 목업 참조 폴더(.design) 제거. 심볼릭 링크·Windows 정션은 따라가지 않고 링크만 지운다
     * (claude가 권한 우회로 그 안에 정션을 만들었으면 따라가는 순간 worktree 밖이 지워진다).
     * 실패해도 예외 없이 경고만 — 기존 동작 유지.
     */
    static void deleteDesignDir(java.nio.file.Path dir) {
        RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
        if (d.failures() > 0) {
            log.warn("목업 참조 폴더 삭제 미완료: {} ({}개 항목 실패)", dir, d.failures());
        }
    }

    private String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
