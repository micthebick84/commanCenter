package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 *   매 10초: heartbeat (claude 로그인 판정은 ClaudeAuthProbe가 5분마다 — 하트비트는 그 값을 싣는다)
 *
 *   ANALYSIS 흐름:
 *     1) git fetch/clone (GitRepoCache)
 *     2) 분석 prompt 치환 + claude -p exec
 *     3) 결과 파싱 → POST result (status=COMPLETED|FAILED)
 *
 *   IMPLEMENTATION 흐름 (구현 방식은 claim의 implementation 블록 — 스펙 2026-10-15, 참조 repo — 스펙 2026-10-24 §6):
 *     1) 참조 repo가 있으면 입력 순서대로 고정 커밋의 참조 worktree(_refs/task-{id}/{카탈로그 id}, 대상 밖) — 하나라도
 *        실패하면 만든 참조를 지우고 구현실패(대상 fetch·worktree·claude 없음). 준비에 쓴 시간은 claude 상한에서 뺀다.
 *        대상 fetch보다 먼저 한다 — 대상 fetch와 worktree 생성 사이가 짧아야 worktree 기준과 baseSha가 같다
 *     2) git fetch/clone (베이스 브랜치 최신화) — 실패하면 만든 참조를 지우고 구현실패
 *     3) WorktreeService.create → 새 브랜치 worktree (재시도는 -rN) + 워커 임시 파일 info/exclude
 *        → 쓰기 도구가 있거나 이전 쓰기 기록이 있으면(자동 마이그레이션 레포 제외) 이 작업의 DB 변경 기록을 사전 조회 — 실패하면
 *        claude 없이 구현실패(스펙 2026-11-03 §5.2). 수정 회차도 같다
 *     4) 인터뷰 목업 파일(.netis/mockups — 설계 2026-11-03 §7) 배치 — 못 두면 claude 없이 구현실패
 *        → 방식 준비(계획·설계 파일 — 계획 파일 머리에 참조 절·목업 절, 대신 진행 판정) → 방식별 구현 prompt(템플릿 바로 뒤 목업 절,
 *        끝에 참조 절) + claude -p exec (worktree에서, --add-dir 없음)
 *        → claude가 어떻게 끝나든 참조 폴더 변경 검사(경고는 PR 본문·결과 보고, 실패면 사유 끝)
 *        → 실패하면 남은 변경을 WIP 커밋으로 push(부분 작업 보존 — DB 기록이 있으면 누적 SQL 스크립트 파일도 넣는다)
 *        → 성공하면 리뷰 메모 회수, .netis/·목업 삭제, 배포 env 목록(.netis-deploy-env.json) 회수·삭제
 *     5) 커밋 직전 준비(prepareCommit) — 세션이 쓴 스크립트 파일 강제 추가, DB 기록이 필요한 구현은 이번 시도의 코드 변경을 구한 뒤
 *        기록을 다시 받아 성공 판정(DB 작업은 누적 파일만으로도 성공)·누적 SQL 스크립트 파일 쓰기·강제 추가(스펙 2026-11-03 §6.6)
 *        → GitOpsService.commitAndPush(에이전트 커밋 수용, 대상만) → headSha. 기록을 못 받으면 wip 보존·구현실패
 *     6) GitOpsService.createDraftPr → prUrl/prNumber (본문에 방식 줄·참조 절·DB 변경 절·리뷰 메모)
 *     7) POST result (status=PR_CREATED 또는 IMPLEMENTATION_FAILED, 시도 번호·참조 경고 포함)
 *     8) 참조 worktree는 모든 경로에서 지운다(대상 worktree는 지금처럼 성공이면 지우고 실패면 남긴다)
 *
 *   수정 회차(claim의 implementation.fix — 스펙 2026-11-03 §7)는 1) 참조 준비 뒤 갈라진다(implementFixRound):
 *     회차 브랜치가 비었거나 베이스 브랜치면 fetch·claude 없이 구현실패 → 회차 브랜치만 fetch(fetchOnly) → 그 끝에 detached worktree(createOnBranch, 새 브랜치 없음) → 끝 커밋이 기록된 head_sha와 다르면
 *     (사람이 MR에 push) 그 위에서 고치고 프롬프트·구현 로그에 남김 → 회차 프롬프트(직접·리뷰, SDD는 리뷰로) + claude
 *     → 실패면 wip 커밋을 같은 브랜치에 push / 성공이면 같은 브랜치에 fast-forward push(PR/MR은 새로 만들지 않음)
 *     → 보고는 성공·실패 모두 회차 표지(작업 시도 번호·회차 안 시도 번호·요청 id)와 회차 브랜치를 싣는다
 *   회차가 아닌 구현 보고에는 회차 표지를 싣지 않는다 — 실으면 API가 닫힌 회차의 늦은 보고로 무시한다.
 *
 *   기준 브랜치 반영 회차(claim의 implementation.fix.kind=BASE_SYNC)는 수정 회차와 달리 참조 repo 준비보다 앞에서 갈라진다(syncBaseRound):
 *     반영 회차는 참조가 필요 없고, 참조 준비 실패나 준비 시간만큼 깎이는 Claude 상한이 이 회차에 영향을 주면 안 된다.
 *     브랜치 확인 → 작업·기준 브랜치 fetch(공통 조상 확보) → 작업 브랜치 끝에 detached worktree → 기준 브랜치 병합(BaseSyncMerger)
 *     → 이미 반영됨·충돌 없음이면 Claude 없이 / 텍스트 충돌이면 Claude가 충돌 파일만 풀고 워커가 풀렸는지·범위 밖 수정이 없는지 검증
 *     → 병합 커밋 → 같은 브랜치에 fast-forward push → 수정 회차와 같은 표지로 성공 보고(PR 없음). 실패하면 worktree를 남기고 wip 보존은 하지 않는다.
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
    private final ClaudeAuthProbe claudeAuth;
    private final BaseSyncMerger merger;

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
                          SuperpowersPlugin superpowers,
                          ClaudeAuthProbe claudeAuth,
                          BaseSyncMerger merger) {
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
        this.claudeAuth = claudeAuth;
        this.merger = merger;
    }

    @Scheduled(fixedRateString = "#{${netis-maker.worker.heartbeat-interval-seconds:10} * 1000}")
    public void sendHeartbeat() {
        // claude 로그인 판정은 ClaudeAuthProbe가 따로(기본 5분) 해 두고 여기서는 읽기만 한다 (스펙 2026-11-02 §3)
        ClaudeAuthProbe.Status auth = claudeAuth.current();
        try {
            http.heartbeat(new WorkerHeartbeatRequest(
                    props.id(), hostname(), props.version(), auth.ok(), null, mcps.getServerNames(),
                    silentLossTracker.currentCount(), auth.methodName(), auth.expiresAtUtc(),
                    silentLossTracker.deadCount()));
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
                case IMPLEMENTATION -> safePostImplementationFailure(task, implementationAttemptOf(task),
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

        // 기준 브랜치 반영 회차 — 참조 repo 준비(아래)보다 먼저 갈라진다: 참조가 필요 없고, 참조 준비 실패나 그 시간만큼 깎인 상한이 이 회차에 영향을 주면 안 된다
        WorkerTaskResponse.FixRoundSpec fix = fixOf(task);
        if (BaseSyncPrompts.isBaseSync(fix)) {
            syncBaseRound(task, fix, attempt, implementationTimeoutOf(task, props.implementationTimeout()));
            return;
        }

        // 1. 참조 repo 준비 — 입력 순서대로 하나씩(레포 락도 한 번에 하나), 대상 fetch보다 먼저 (스펙 2026-10-24 §6.1~§6.3).
        //    대상 fetch와 대상 worktree 생성 사이에 두면 그 틈이 분 단위로 벌어진다 — worktree는 생성 시점의
        //    origin/{base}에서 분기하고 프롬프트·리뷰·커밋 판정의 baseSha는 fetch 때의 commitSha라, 그 틈에 같은 PC의
        //    다른 워커가 같은 브랜치를 최신화하면 둘이 어긋난다. 참조가 없으면 아무것도 부르지 않고 claude 상한도 그대로다
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
                    // 참조를 다 준비하지 못하면 claude를 실행하지 않는다 — 대상은 fetch도 worktree도 하지 않는다
                    discardRefWorktrees(task.id(), attempted);
                    safePostImplementationFailure(task, attempt,
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
                safePostImplementationFailure(task, attempt, "시간 초과: 참조 repo 준비에 " + prep.toSeconds()
                        + "초를 써 구현 상한(" + limit.toMinutes() + "분)이 남지 않았습니다", null, null, null);
                return;
            }
        }

        // 수정 회차 — 베이스가 아니라 회차 브랜치 끝에서 이어 고친다 (스펙 2026-11-03 §7). 참조는 위와 같고 어떤 경로로 끝나든 지운다
        if (fix != null) {
            try {
                implementFixRound(task, fix, requested, attempt, ready, claudeLimit);
            } finally {
                discardRefWorktrees(task.id(), attempted);
            }
            return;
        }

        // 2. 베이스 브랜치 최신화 — 대상 worktree 생성 바로 앞 (worktree add 시 origin/{base} 참조).
        //    실패하면 준비한 참조를 지우고 보고한다(참조가 없으면 아무것도 부르지 않는다)
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.repoRef(), task.githubBranch());
        } catch (Exception e) {
            discardRefWorktrees(task.id(), attempted);
            safePostImplementationFailure(task, attempt,
                    "레포 fetch 실패: " + e.getMessage(), null, null, null);
            return;
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
            safePostImplementationFailure(task, attempt,
                    "worktree 생성 실패: " + e.getMessage(), null, null, null);
            return;
        }
        Path wtPath = wt.dir().toPath();

        // 이 작업의 DB 변경 기록 사전 조회 — worktree 생성 뒤·프롬프트 조립 전, 쓰기 도구가 있거나 이전 쓰기 기록이 있을 때만 (스펙 2026-11-03 §5.2).
        // 못 읽으면 claude를 돌리지 않는다 — 이미 적용한 SQL을 모르고 같은 문장을 공용 DB에 다시 실행하게 된다
        DbLog dbLog;
        try {
            dbLog = fetchDbLog(task);
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, dbLogFailureReason(e), wt.branchName(), null, null);
            return;
        }

        // 승인된 디자인이 있으면 목업을 워크트리에 배치 (참조용 — 커밋 전 삭제)
        File designDir = placeMockups(task, wt.dir());

        // 4. 구현 방식 준비 — 임시 파일 exclude, 계획·설계 파일, 대신 진행 판정 (스펙 2026-10-15 §6.1·§6.5)
        boolean excludesReady = worktrees.ensureWorkerExcludes(wt.dir());

        // 인터뷰 목업 파일 배치(.netis/mockups — exclude 뒤, claude 전). 못 두면 claude 없이 구현실패 — 경고만 남기고 진행하면
        // 관리자가 고른 목업 없이 구현된다(설계 2026-11-03 §10 반목표, 계획 "설계와 다름 ①"). 목업이 없으면 아무것도 하지 않는다
        List<WorkerTaskResponse.InterviewMockupFile> ms = task.interviewMockups();
        List<Integer> excludedMockups = task.excludedMockupNumbers();
        boolean approvedDesign = ImplementationPrompts.hasApprovedDesign(task);
        try {
            placeInterviewMockups(task, wtPath);
        } catch (IOException e) {
            safePostImplementationFailure(task, attempt, MOCKUP_PLACE_FAILED + messageOf(e),
                    wt.branchName(), null, null);
            return;
        }

        boolean hasPlan = task.planMarkdown() != null && !task.planMarkdown().isBlank();
        String planText = requested == ImplementationMode.SDD && hasPlan
                ? ImplementationScratch.normalizeTaskHeadings(task.planMarkdown())
                : task.planMarkdown();
        boolean planWritten = false;
        boolean designWritten = false;
        if (requested != ImplementationMode.DIRECT && hasPlan) {
            // 참조 절·인터뷰 목업 절은 파일 머리에만 — 태스크 수 판정(countTaskHeadings)에는 계획 본문만 넘긴다 (스펙 2026-10-24 §6.5).
            // 목업 머리는 SDD 서브에이전트·리뷰어가 본 프롬프트 없이 읽으므로 구현 절과 같은 강도다
            planWritten = writeScratch(wtPath, ImplementationScratch.planPath(task.id()),
                    RefRepoPrompts.planFileHeader(ready)
                            + InterviewMockupPrompts.planFileHeader(ms, excludedMockups, approvedDesign)
                            + planText);
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
        //    작업 타입 절은 템플릿 안({plan_section} 바로 뒤) — DB 작업의 세 갈래는 쓰기 도구 유무(execute로 한 문장씩 / 파일에 적기)와
        //    자동 마이그레이션 레포(마이그레이션 관례대로)가 정한다. 파일에 적는 경우 경로는 이전 쓰기 기록이 있으면 -manual
        //    (워커의 누적 기록 파일을 덮어쓰지 않게 — 계획 "설계와 다름 ②"). DB 쪽은 DB 절 → 자동 마이그레이션 절 → 이미 적용된 SQL 절 순서
        //    인터뷰 목업 절은 템플릿 밖 맨 앞(템플릿 바로 뒤) — 목업도 제외 번호도 없으면 ""라 프롬프트가 예전과 같다 (설계 2026-11-03 §7)
        TaskType taskType = taskTypeOf(task);
        WorkerTaskResponse.DbWriteSpec db = dbSpecOf(task);
        boolean writeTools = DbMcpWorkerSupport.anyWritable(task.dbMcp());
        String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName(),
                ImplementationPrompts.planSection(task, mode.effective(), repo.commitSha(), designWritten,
                        !ready.isEmpty(), !ms.isEmpty()),
                ImplementationPrompts.commitRule(mode.effective()),
                ImplementationPrompts.taskTypeSection(taskType, writeTools, db.migrationManaged(),
                        ImplementationPrompts.sessionScriptPath(db, writeTools)))
                + InterviewMockupPrompts.implementationSection(ms, excludedMockups, approvedDesign)
                + DbMcpWorkerSupport.promptSection(task.dbMcp(), true)
                + DbMcpWorkerSupport.migrationManagedSection(db)
                + DbMcpWorkerSupport.appliedSqlSection(dbLog.prior())
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
            safePostImplementationFailure(task, attempt,
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
            safePostImplementationFailure(task, attempt,
                    "claude exit=" + exec.exitCode() + "\n" + tail + kept.reasonSuffix() + fallbackNote + refNote,
                    wt.branchName(), kept.headSha(), exec.stdout(), usageOf(exec));
            return;
        }

        // 리뷰 메모는 PR 본문으로 회수하고 .netis/(계획·설계·메모)는 커밋 전에 지운다 — exclude가 실패했어도 안 섞이게.
        // DB 메모(세션이 PR 본문을 못 쓰니 .netis/db-notes.md로 남긴 것)는 리뷰 메모 뒤에 빈 줄로 이어 같은 절로 옮긴다
        Optional<String> reviewNotes = joinNotes(
                ImplementationScratch.readReviewNotes(wtPath), ImplementationScratch.readDbNotes(wtPath));
        ImplementationScratch.deleteRoot(wtPath);

        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteDesignDir(designDir.toPath());

        // 배포 env 목록(.netis-deploy-env.json)도 커밋 전에 회수·삭제 — 없거나 틀려도 구현은 계속 (스펙 2026-10-07 §4.3)
        List<EnvTemplateItem> envTemplate = DeployEnvManifest.harvest(wtPath);

        // 5. 변경 commit + push(대상만) — 에이전트가 만든 커밋(SDD 태스크 커밋)도 받아들인다.
        //    커밋 앞머리는 작업 타입을 따른다(타입 없음 feat). 커밋 직전 준비(prepareCommit) — 세션이 쓴 스크립트 파일은 무시 규칙이 있어도
        //    먼저 index에 올리고, DB 기록이 필요한 구현은 기록을 다시 받아 성공 판정과 누적 SQL 스크립트 파일을 만든다(스펙 2026-11-03 §6.6)
        String headSha;
        List<DbChangeView> dbChanges;
        try {
            dbChanges = prepareCommit(task, wt.dir(), repo.commitSha(), dbLog.baselineId());
            String commitMsg = TaskType.commitPrefixOf(taskType) + ": " + task.title()
                    + "\n\n" + "task #" + task.id()
                    + "\n\nCo-Authored-By: netisMaker <" + props.gitUserEmail() + ">";
            headSha = gitOps.commitAndPush(wt.dir(), task.repoRef(), wt.branchName(), commitMsg, repo.commitSha());
        } catch (DbLogUnavailable e) {
            // 실행한 SQL이 스크립트 파일에서 빠진 PR을 만들지 않는다 — 지금까지 작업은 wip으로 남기고 구현실패(재시도 가능, 계획 "설계와 다름")
            PartialOutcome kept = preservePartialWork(task, wt, repo.commitSha(), "구현", DB_LOG_WIP_WHY, false);
            safePostImplementationFailure(task, attempt,
                    postDbLogFailureReason(e) + kept.reasonSuffix() + fallbackNote + refNote,
                    wt.branchName(), kept.headSha(), exec.stdout(), usageOf(exec));
            return;
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt,
                    "commit/push 실패: " + e.getMessage() + fallbackNote + refNote,
                    wt.branchName(), null, exec.stdout(), usageOf(exec));
            return;
        }

        // 6. Draft PR/MR — 본문의 "DB 변경" 절은 커밋 직전에 받은 기록으로(스펙 2026-11-03 §6.5)
        GitOpsService.PrInfo pr;
        try {
            String body = renderPrBody(task, exec.durationMs(), headSha, modeLine, reviewNotes.orElse(null),
                    refWarnings, dbChangeSection(dbChanges, db.scriptPath(),
                            ImplementationPrompts.sessionScriptPath(db, writeTools), writeTools, db.migrationManaged(),
                            taskType));
            pr = gitOps.createDraftPr(wt.dir(), task.repoRef(),
                    task.githubBranch(), wt.branchName(),
                    TaskType.prTitle(taskType, task.title()), body);
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt,
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

    /**
     * 수정 회차 구현 (스펙 2026-11-03 §7) — 회차 브랜치 끝에서 이어 고치고 같은 브랜치에 fast-forward push만 한다.
     * PR/MR은 새로 만들지 않는다(기존 PR/MR이 같은 브랜치를 가리킨다). 대상 worktree는 성공이면 지우고 실패면 남긴다(지금 구현과 같다).
     *  - 끝 커밋(tip)이 기록된 head_sha와 다르면(사람이 MR에 push) 그 끝 위에서 고친다 — 프롬프트·구현 로그·실패 사유에 남긴다.
     *    commit/push·wip 보존의 기준 커밋도 tip이다(에이전트 커밋 수용·"변경 없음" 판정).
     *  - 실패 보고의 headSha는 원격 브랜치 끝 — wip을 올렸으면 그 커밋, 아니면 tip. API는 회차 브랜치일 때만 이 값으로 head_sha를 맞춘다
     *    (수정 중단 뒤 배포·종료 병합의 기준, §7.1). 원격 끝을 읽기 전 실패는 null.
     */
    private void implementFixRound(WorkerTaskResponse task, WorkerTaskResponse.FixRoundSpec fix,
                                   ImplementationMode requested, int attempt,
                                   List<WorktreeService.RefWorktree> ready, Duration claudeLimit) throws Exception {
        String branch = fix.headBranch();
        String label = FixRoundPrompts.label(fix);
        String repoKey = GitRemotes.localKey(task.repoRef());

        // 0. 이어 고칠 브랜치 확인 — 비었거나 베이스 브랜치면 fetch·claude 없이 구현실패(베이스에 직접 push하면 안 된다).
        //    API가 요청 때 막지만 claim 값은 믿지 않는다(깨진 데이터·구버전 API)
        if (!isUsableFixBranch(branch, task.githubBranch())) {
            String shown = branch == null || branch.isBlank() ? "없음" : branch.strip();
            safePostImplementationFailure(task, attempt,
                    label + ": 이어 고칠 브랜치가 비었거나 베이스 브랜치와 같음(" + shown + ") — 진행하지 않음",
                    branch, null, null);
            return;
        }

        // 1. 회차 브랜치만 fetch(캐시 HEAD·베이스는 그대로) → 그 끝에 detached worktree(구현 자리 task-{id}, 새 브랜치 없음)
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.fetchOnly(task.repoRef(), branch);
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, "레포 fetch 실패: " + e.getMessage(), branch, null, null);
            return;
        }
        WorktreeService.CreatedWorktree wt;
        try {
            wt = worktrees.createOnBranch(repo.dir(), repoKey, branch, task.id());
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, "worktree 생성 실패: " + e.getMessage(), branch, null, null);
            return;
        }
        String tip;
        try {
            tip = gitOps.headSha(wt.dir());
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, "브랜치 끝 커밋 확인 실패: " + e.getMessage(), branch, null, null);
            return;
        }
        Path wtPath = wt.dir().toPath();

        // 2. 원격 끝이 기록과 다르면 그 위에서 고친다 (Review Focus 1) / 방식은 직접·리뷰만 — SDD는 원래 플랜 전체를 다시 구현해 리뷰로
        boolean remoteAhead = FixRoundPrompts.remoteAhead(tip, fix.headSha());
        List<String> notes = new ArrayList<>();
        if (remoteAhead) notes.add(FixRoundPrompts.remoteAheadNote(tip, fix.headSha()));
        ImplementationMode mode = requested == ImplementationMode.SDD ? ImplementationMode.REVIEW : requested;
        if (requested == ImplementationMode.SDD) notes.add("SDD 요청 — 수정 회차는 직접·리뷰만 되어 리뷰로 진행");
        for (String note : notes) log.warn("{}: task={} {}", label, task.id(), note);
        String logHead = notes.isEmpty() ? "" : "[" + label + "] " + String.join("\n[" + label + "] ", notes) + "\n\n";
        String reasonTail = remoteAhead ? "\n" + notes.get(0) : "";

        // 이 작업의 DB 변경 기록 사전 조회 — 일반 구현과 같다(쓰기 도구가 있거나 이전 쓰기 기록이 있을 때만). 못 읽으면 claude 없이 구현실패.
        // 원격 브랜치 끝은 그대로(tip)라 보고에 싣는다
        DbLog dbLog;
        try {
            dbLog = fetchDbLog(task);
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, dbLogFailureReason(e) + reasonTail, branch, tip, null);
            return;
        }

        // 승인된 디자인 목업은 지금 구현처럼 참조용으로 둔다(커밋 전 삭제). 임시 파일 exclude는 실패해도 아래에서 지우고 커밋한다
        File designDir = placeMockups(task, wt.dir());
        worktrees.ensureWorkerExcludes(wt.dir());

        // 인터뷰 목업 파일 — 일반 구현과 같이 exclude 뒤에 두고, 못 두면 claude 없이 회차 구현실패(사전 조회 실패와 같은 모양).
        // 프롬프트에는 참고용 H3로만 들어간다(FixRoundPrompts)
        try {
            placeInterviewMockups(task, wtPath);
        } catch (IOException e) {
            safePostImplementationFailure(task, attempt, MOCKUP_PLACE_FAILED + messageOf(e) + reasonTail,
                    branch, tip, null);
            return;
        }

        // 3. 회차 프롬프트 + claude exec (참조 절은 끝에, --add-dir 없음 — 지금 구현과 같다). 타입 절은 없고 DB 쪽은 DB 절(쓰기 연결이면 쓰기 절)에
        //    자동 마이그레이션 절·이미 적용된 SQL 절을 dbExtra로 붙인다
        String dbExtra = DbMcpWorkerSupport.migrationManagedSection(dbSpecOf(task))
                + DbMcpWorkerSupport.appliedSqlSection(dbLog.prior());
        String prompt = FixRoundPrompts.render(task, fix, tip, remoteAhead, mode,
                ImplementationPrompts.commitRule(mode), ready, dbExtra);
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt.dir(), claudeLimit,
                    DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl()),
                    true, task.model(), task.effort(),
                    new ClaudeExecAdapter.ExecOptions(List.of(),
                            gitAuthorEnv(props.gitUserName(), props.gitUserEmail())));
        } catch (Exception e) {
            String refNote = RefRepoPrompts.failureSuffix(refChangeWarnings(task.id(), ready));
            String why = e.getMessage() != null && e.getMessage().startsWith("claude timeout") ? "시간 초과" : "실행 오류";
            PartialOutcome kept = preservePartialWork(task, wt, tip, label, why);
            safePostImplementationFailure(task, attempt,
                    "claude exec 실패: " + e.getMessage() + kept.reasonSuffix() + reasonTail + refNote,
                    branch, remoteEnd(kept, tip), logHead.isEmpty() ? null : logHead);
            return;
        }
        List<String> refWarnings = refChangeWarnings(task.id(), ready);
        String refNote = RefRepoPrompts.failureSuffix(refWarnings);
        if (exec.exitCode() != 0) {
            PartialOutcome kept = preservePartialWork(task, wt, tip, label, "비정상 종료");
            safePostImplementationFailure(task, attempt,
                    "claude exit=" + exec.exitCode() + "\n" + tail(exec.stdout(), 4000) + kept.reasonSuffix()
                            + reasonTail + refNote,
                    branch, remoteEnd(kept, tip), logHead + nvl(exec.stdout(), ""), usageOf(exec));
            return;
        }

        // 4. 리뷰 메모는 구현 로그로(PR 본문을 고치는 경로가 없다) — DB 메모도 그 뒤에 이어 같은 곳으로. 임시 파일은 커밋 전에 지운다
        Optional<String> reviewNotes = joinNotes(
                ImplementationScratch.readReviewNotes(wtPath), ImplementationScratch.readDbNotes(wtPath));
        ImplementationScratch.deleteRoot(wtPath);
        if (designDir != null) deleteDesignDir(designDir.toPath());
        List<EnvTemplateItem> envTemplate = DeployEnvManifest.harvest(wtPath);
        String implementationLog = logHead
                + reviewNotes.map(n -> "## 리뷰 메모\n" + n + "\n\n").orElse("")
                + nvl(exec.stdout(), "");

        // 5. 같은 브랜치에 fast-forward push(force 없음 — 그 사이 원격이 앞섰으면 거절된다). 에이전트 커밋도 tip 이후면 받아들인다
        String headSha;
        try {
            // 커밋 앞머리는 늘 fix — 작업 타입과 무관(스펙 2026-11-03 §4.1). 커밋 직전 준비는 일반 구현과 같고 기준 커밋만 tip이다 —
            // 누적 SQL 스크립트 파일을 회차의 기록까지 넣어 다시 만든다(PR/MR을 새로 만들지 않으니 본문 DB 절은 없다)
            prepareCommit(task, wt.dir(), tip, dbLog.baselineId());
            String commitMsg = "fix: " + label + " — " + task.title()
                    + "\n\n" + "task #" + task.id()
                    + "\n\nCo-Authored-By: netisMaker <" + props.gitUserEmail() + ">";
            headSha = gitOps.commitAndPush(wt.dir(), task.repoRef(), branch, commitMsg, tip);
        } catch (DbLogUnavailable e) {
            PartialOutcome kept = preservePartialWork(task, wt, tip, label, DB_LOG_WIP_WHY, false);
            safePostImplementationFailure(task, attempt,
                    postDbLogFailureReason(e) + kept.reasonSuffix() + reasonTail + refNote,
                    branch, remoteEnd(kept, tip), implementationLog, usageOf(exec));
            return;
        } catch (Exception e) {
            // 고칠 게 없다며 아무것도 바꾸지 않았으면 사유를 분명히 — 관리자가 수정 중단으로 빠져나온다 (Review Focus 2)
            String reason = isNothingToCommit(e)
                    ? label + ": 변경 없음 — Claude가 고칠 곳을 찾지 못함"
                    : "commit/push 실패: " + e.getMessage() + (isPushRejected(e) ? PUSH_REJECTED_NOTE : "");
            safePostImplementationFailure(task, attempt, reason + reasonTail + refNote,
                    branch, tip, implementationLog, usageOf(exec));
            return;
        }

        // 6. 성공 보고 — PR 없이 같은 브랜치의 새 끝. API가 head_sha를 바꾸고 저장된 env로 자동 재배포한다
        reporter.reportTerminal(task.id(), withFixMarks(new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec.durationMs(), null,
                null, null, branch, headSha, implementationLog,
                null, null, null, null, null,
                null, null, null, null,
                usageOf(exec),
                envTemplate,
                attempt,
                refWarnings.isEmpty() ? null : refWarnings), fix));
        log.info("{} 구현 완료: task={} {}@{} (방식 {}, 회차 안 {}회째{})", label, task.id(), branch,
                RefRepoPrompts.sha7(headSha), mode, fix.attempt(), remoteAhead ? ", 원격 끝 위에서" : "");
        worktrees.discard(repoKey, WorktreeKind.TASK, task.id());
    }

    /**
     * 기준 브랜치 반영 회차 — 작업 브랜치에 기준 브랜치를 병합해 같은 브랜치에 fast-forward push한다(수정 회차와 같은 표지로 보고, PR 없음).
     * 충돌이 없으면 Claude 없이 끝나고, 텍스트 충돌이면 Claude가 충돌 파일만 푼다. 모든 실패 사유는 회차 이름으로 시작하고
     * 보고의 headBranch는 작업 브랜치이며 wip 보존({@code preservePartialWork})은 하지 않는다 — 실패하면 worktree를 남긴다.
     */
    private void syncBaseRound(WorkerTaskResponse task, WorkerTaskResponse.FixRoundSpec fix, int attempt,
                               Duration claudeLimit) throws Exception {
        String branch = fix.headBranch();
        String base = task.githubBranch();
        String label = BaseSyncPrompts.label(fix);
        String repoKey = GitRemotes.localKey(task.repoRef());

        // 1. 반영할 브랜치 확인 — 비었거나 기준 브랜치와 같으면 병합할 것이 없다(기준 브랜치에 직접 push하면 안 된다)
        if (!isUsableFixBranch(branch, base)) {
            String shown = branch == null || branch.isBlank() ? "없음" : branch.strip();
            safePostImplementationFailure(task, attempt,
                    label + ": 반영할 브랜치가 비었거나 기준 브랜치와 같음(" + shown + ") — 진행하지 않음",
                    branch, null, null);
            return;
        }

        // 2. 작업 브랜치와 기준 브랜치 fetch(공통 조상까지)
        GitRepoCache.BaseSyncRepo repo;
        try {
            repo = repos.fetchForBaseSync(task.repoRef(), branch, base);
        } catch (GitRepoCache.NoCommonAncestorException e) {
            safePostImplementationFailure(task, attempt,
                    label + ": 공통 조상을 찾지 못함(이력 500커밋 초과) — GitLab에서 직접 해결하세요", branch, null, null);
            return;
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": 레포 fetch 실패: " + messageOf(e), branch, null, null);
            return;
        }

        // 3. 작업 브랜치 끝에 detached worktree
        WorktreeService.CreatedWorktree wt;
        try {
            wt = worktrees.createOnBranch(repo.dir(), repoKey, branch, task.id());
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": worktree 생성 실패: " + messageOf(e), branch, null, null);
            return;
        }
        String tip;
        try {
            tip = gitOps.headSha(wt.dir());
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": 브랜치 끝 커밋 확인 실패: " + messageOf(e), branch, null, null);
            return;
        }
        worktrees.ensureWorkerExcludes(wt.dir());
        String logHead = "";
        if (FixRoundPrompts.remoteAhead(tip, fix.headSha())) {
            String note = FixRoundPrompts.remoteAheadNote(tip, fix.headSha());
            log.warn("{}: task={} {}", label, task.id(), note);
            logHead = "[" + label + "] " + note + "\n\n";
        }
        String headLog = logHead.isEmpty() ? null : logHead;
        String message = BaseSyncPrompts.commitMessage(task, props.gitUserEmail());

        // 4. 기준 브랜치 병합 시작
        BaseSyncMerger.Started started;
        try {
            started = merger.start(wt.dir(), repo.baseRef());
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": 병합 시작 실패: " + messageOf(e), branch, tip, headLog);
            return;
        }
        if (started.outcome() == BaseSyncMerger.Outcome.UP_TO_DATE) {
            reportBaseSyncSuccess(task, fix, attempt, branch, tip,
                    logHead + "기준 브랜치 " + base + "가 이미 반영돼 있습니다.", null, repoKey);
            return;
        }
        if (started.outcome() == BaseSyncMerger.Outcome.CLEAN) {
            String pushed;
            try {
                pushed = commitAndPushMerge(task, wt.dir(), branch, List.of(), message, tip, repo.baseRef());
            } catch (BaseSyncMerger.NotMergingException e) {
                safePostImplementationFailure(task, attempt, label + ": " + e.getMessage(), branch, tip, headLog);
                return;
            } catch (Exception e) {
                safePostImplementationFailure(task, attempt, mergePushFailure(label, e), branch, tip, headLog);
                return;
            }
            reportBaseSyncSuccess(task, fix, attempt, branch, pushed, logHead + "충돌 없이 병합했습니다.", null, repoKey);
            return;
        }

        List<String> conflicted = started.conflicted();
        if (!started.binaryConflicted().isEmpty()) {
            safePostImplementationFailure(task, attempt,
                    label + ": 바이너리 파일 충돌 — " + String.join(", ", started.binaryConflicted())
                            + " — GitLab에서 직접 해결하세요", branch, tip, headLog);
            return;
        }
        // 표식이 없는 충돌(한쪽 삭제·이름 변경 등)은 표식 검사로 풀렸는지 알 수 없다 — Claude 없이 사람에게 넘긴다
        if (!started.markerless().isEmpty()) {
            safePostImplementationFailure(task, attempt,
                    label + ": 표식 없는 충돌(한쪽에서 삭제·이름 변경 등) — " + String.join(", ", started.markerless())
                            + " — GitLab에서 직접 해결하세요", branch, tip, headLog);
            return;
        }

        // 5. Claude가 충돌 파일만 푼다 — 작업별 MCP·DB 서버는 넘기지 않는다
        String prompt = BaseSyncPrompts.render(task, fix, tip, conflicted);
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt.dir(), claudeLimit, List.of(), true, task.model(), task.effort(),
                    new ClaudeExecAdapter.ExecOptions(List.of(),
                            gitAuthorEnv(props.gitUserName(), props.gitUserEmail())));
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": claude exec 실패: " + messageOf(e), branch, tip, headLog);
            return;
        }
        String claudeLog = logHead + "[" + label + "] 충돌 파일: " + String.join(", ", conflicted) + "\n\n"
                + nvl(exec.stdout(), "");
        if (exec.exitCode() != 0) {
            safePostImplementationFailure(task, attempt,
                    label + ": claude exit=" + exec.exitCode() + "\n" + tail(exec.stdout(), 4000),
                    branch, tip, claudeLog, usageOf(exec));
            return;
        }

        // 6. 검증 — 충돌이 다 풀렸는지, 충돌 파일 밖을 건드리지 않았는지
        try {
            List<String> unresolved = merger.unresolved(wt.dir(), conflicted);
            if (!unresolved.isEmpty()) {
                safePostImplementationFailure(task, attempt,
                        label + ": 충돌 해결 실패 — 남은 충돌 파일: " + String.join(", ", unresolved),
                        branch, tip, claudeLog, usageOf(exec));
                return;
            }
            List<String> stray = merger.strayChanges(wt.dir(), started);
            if (!stray.isEmpty()) {
                safePostImplementationFailure(task, attempt,
                        label + ": 충돌 파일 밖을 수정함 — " + String.join(", ", stray),
                        branch, tip, claudeLog, usageOf(exec));
                return;
            }
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, label + ": 충돌 해결 검증 실패: " + messageOf(e),
                    branch, tip, claudeLog, usageOf(exec));
            return;
        }

        // 7. 병합 커밋 → 같은 브랜치에 fast-forward push
        String pushed;
        try {
            pushed = commitAndPushMerge(task, wt.dir(), branch, conflicted, message, tip, repo.baseRef());
        } catch (BaseSyncMerger.NotMergingException e) {
            safePostImplementationFailure(task, attempt, label + ": " + e.getMessage(), branch, tip, claudeLog, usageOf(exec));
            return;
        } catch (Exception e) {
            safePostImplementationFailure(task, attempt, mergePushFailure(label, e), branch, tip, claudeLog, usageOf(exec));
            return;
        }
        reportBaseSyncSuccess(task, fix, attempt, branch, pushed, claudeLog, exec, repoKey);
    }

    /** 병합 커밋을 만들고 작업 브랜치에 fast-forward push — 새 원격 끝. */
    private String commitAndPushMerge(WorkerTaskResponse task, File dir, String branch, List<String> conflicted,
                                      String message, String tip, String baseRef) throws Exception {
        merger.commitMerge(dir, conflicted, message, tip, baseRef);
        return gitOps.pushHead(dir, task.repoRef(), branch);
    }

    private static String mergePushFailure(String label, Exception e) {
        // push 거절은 원격이 앞섰다는 뜻 — 구현 재시도가 새 원격 끝에서 다시 병합한다
        if (isPushRejected(e)) return label + ": push 실패: " + messageOf(e) + PUSH_REJECTED_NOTE;
        return label + ": 병합 커밋/push 실패: " + messageOf(e);
    }

    /** 기준 브랜치 반영 성공 보고 — PR 없이 같은 브랜치의 새 끝(수정 회차 표지). 보고 뒤 worktree를 정리한다. */
    private void reportBaseSyncSuccess(WorkerTaskResponse task, WorkerTaskResponse.FixRoundSpec fix, int attempt,
                                       String branch, String headSha, String implementationLog,
                                       ClaudeExecAdapter.ExecResult exec, String repoKey) {
        reporter.reportTerminal(task.id(), withFixMarks(new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec == null ? null : exec.durationMs(), null,
                null, null, branch, headSha, implementationLog,
                null, null, null, null, null,
                null, null, null, null,
                usageOf(exec),
                null,
                attempt,
                null), fix));
        log.info("{} 완료: task={} {}@{}", BaseSyncPrompts.label(fix), task.id(), branch, RefRepoPrompts.sha7(headSha));
        worktrees.discard(repoKey, WorktreeKind.TASK, task.id());
    }

    /** 회차가 이어 push할 브랜치로 쓸 수 있다 — 비어 있지 않고 베이스 브랜치와 다르다(앞뒤 공백 무시). */
    static boolean isUsableFixBranch(String headBranch, String baseBranch) {
        if (headBranch == null || headBranch.isBlank()) return false;
        return baseBranch == null || !headBranch.strip().equals(baseBranch.strip());
    }

    /** 실패 보고에 실을 원격 브랜치 끝 — wip을 올렸으면 그 커밋, 아니면 처음 받은 끝(tip). */
    private static String remoteEnd(PartialOutcome kept, String tip) {
        return kept.headSha() != null ? kept.headSha() : tip;
    }

    /** GitOpsService.commitAndPush의 "변경도 tip 이후 커밋도 없음"(또는 git commit의 같은 뜻 출력). */
    static boolean isNothingToCommit(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("nothing to commit");
    }

    /** 수정 회차 push가 fast-forward가 아니라 거절됐을 때 사유 끝에 붙이는 설명. */
    static final String PUSH_REJECTED_NOTE = " — 작업하는 동안 원격 브랜치에 다른 커밋이 올라와 fast-forward push가 거절됐습니다"
            + "(force push는 하지 않습니다). 구현 재시도는 새 원격 끝에서 다시 고칩니다";

    /** git push의 거절(non-fast-forward) 출력인가 — 원격이 worktree를 만든 뒤에 앞섰다. */
    static boolean isPushRejected(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("[rejected]") || m.contains("non-fast-forward") || m.contains("fetch first"));
    }

    /** claim의 수정 회차 — 없으면(회차 아닌 구현·다른 kind·구버전 API) null. */
    static WorkerTaskResponse.FixRoundSpec fixOf(WorkerTaskResponse task) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s == null ? null : s.fix();
    }

    /**
     * 수정 회차 구현 보고의 표지 — 회차 안 시도 번호와 요청 id(스펙 2026-11-03 §7). 회차가 아니면(fix null) 그대로 둔다: 회차가 아닌 보고에
     * 표지를 달면 API가 닫힌 회차의 늦은 보고로 무시한다.
     */
    static WorkerResultRequest withFixMarks(WorkerResultRequest req, WorkerTaskResponse.FixRoundSpec fix) {
        return fix == null ? req : req.withFixAttempt(fix.attempt()).withFixRequestId(fix.requestId());
    }

    /** 인터뷰 목업 배치 실패 사유의 앞머리 — 구현·회차·디자인 공용. */
    static final String MOCKUP_PLACE_FAILED = "인터뷰 목업 배치 실패: ";

    /**
     * claim의 인터뷰 목업(interviewMockups)을 worktree의 {@code .netis/mockups/{n}-{name}-v{version}.html}로 쓴다(설계 2026-11-03 §7).
     * 목업이 없으면 폴더도 만들지 않는다(제외 번호만 있는 작업 포함). 하나라도 못 쓰면 IOException — 호출부가 claude 전에 실패로 끝낸다.
     * 경로는 {@link ImplementationScratch#mockupPath}가 이름을 접어 만들고, 정규화한 경로가 {@code .netis/mockups} 밖이면 쓰지 않는다.
     */
    static void placeInterviewMockups(WorkerTaskResponse task, Path worktree) throws IOException {
        for (WorkerTaskResponse.InterviewMockupFile m : task.interviewMockups()) {
            Path target = mockupTarget(worktree, ImplementationScratch.mockupPath(m));
            Files.createDirectories(target.getParent());
            Files.writeString(target, m.html() == null ? "" : m.html(), StandardCharsets.UTF_8);
        }
    }

    /** worktree 상대 경로를 정규화한 절대 경로 — {@code worktree/.netis/mockups} 아래가 아니면 IOException. */
    static Path mockupTarget(Path worktree, String relPath) throws IOException {
        Path base = worktree.resolve(ImplementationScratch.MOCKUPS_DIR).toAbsolutePath().normalize();
        Path target = worktree.resolve(relPath).toAbsolutePath().normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("목업 경로가 worktree 밖입니다: " + relPath);
        }
        return target;
    }

    /** 승인된 디자인 목업을 worktree의 .design/에 둔다(참조용 — 커밋 전 삭제). 없거나 실패하면 null(디자인 없이 진행). */
    private File placeMockups(WorkerTaskResponse task, File worktreeDir) {
        if (task.mockupFilesJson() == null || task.mockupFilesJson().isBlank()) return null;
        try {
            File designDir = new File(worktreeDir, ".design");
            writeMockupFiles(designDir, task.mockupFilesJson());
            java.nio.file.Files.writeString(new File(designDir, "DESIGN.md").toPath(),
                    nvl(task.designMarkdown(), ""));
            return designDir;
        } catch (Exception e) {
            log.warn("목업 배치 실패 (디자인 없이 진행): {}", e.getMessage());
            return null;
        }
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

    /**
     * claim의 작업 타입 — 구현 블록이 없거나(구버전 API) 타입이 없거나 모르는 값(다른 버전 API)이면 null(타입 없음).
     * 타입 없음은 커밋 앞머리 feat·PR 제목 앞머리 없음·타입 절 없음으로 타입이 생기기 전과 같다.
     */
    static TaskType taskTypeOf(WorkerTaskResponse task) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s == null ? null : TaskType.fromWireOrNull(s.taskType());
    }

    /**
     * claim의 개발 DB 쓰기 재료 — 구현 블록이나 재료가 없으면(구버전 API·재료를 못 만든 호출) 기본 경로·쓰기 기록 없음·자동 마이그레이션 아님
     * (스펙 2026-11-03 §6.6). 경로만 비어 온 재료는 기본 경로로 채우고 플래그는 지킨다.
     */
    static WorkerTaskResponse.DbWriteSpec dbSpecOf(WorkerTaskResponse task) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        WorkerTaskResponse.DbWriteSpec db = s == null ? null : s.db();
        if (db == null) return new WorkerTaskResponse.DbWriteSpec(ImplementationPrompts.defaultScriptPath(task.id()), false, false);
        if (db.scriptPath() == null || db.scriptPath().isBlank()) {
            return new WorkerTaskResponse.DbWriteSpec(ImplementationPrompts.defaultScriptPath(task.id()),
                    db.migrationManaged(), db.priorWrites());
        }
        return db;
    }

    /**
     * 구현 전에 이 작업의 DB 변경 기록을 조회해야 하는가 — 자동 마이그레이션 레포가 아니고(그 레포는 기록·스크립트 파일이 생기지 않는다)
     * 이 구현에 쓰기 도구가 있거나 이전 시도·회차가 남긴 쓰기 기록이 있을 때 (스펙 2026-11-03 §5.2·§6.6).
     */
    static boolean needsDbLog(WorkerTaskResponse task) {
        WorkerTaskResponse.DbWriteSpec db = dbSpecOf(task);
        return !db.migrationManaged() && (DbMcpWorkerSupport.anyWritable(task.dbMcp()) || db.priorWrites());
    }

    /** 구현 전 DB 변경 기록 사전 조회 결과 — 기록(이미 적용된 SQL 절의 재료)과 기준 id(이번 시도가 새로 남긴 기록은 이 id보다 크다). */
    record DbLog(List<DbChangeView> prior, long baselineId) {
        static final DbLog NONE = new DbLog(List.of(), 0L);
    }

    /** needsDbLog일 때만 조회한다 — 아니면 HTTP 호출 없이 NONE. 조회가 실패하면 예외를 그대로 던진다(호출부가 구현실패로 끝낸다). */
    private DbLog fetchDbLog(WorkerTaskResponse task) {
        if (!needsDbLog(task)) return DbLog.NONE;
        List<DbChangeView> fetched = http.dbChanges(task.id());
        List<DbChangeView> prior = fetched == null ? List.of() : fetched;
        long baseline = baselineIdOf(prior);
        log.info("DB 변경 기록 사전 조회: task={} {}건, 기준 id={}", task.id(), prior.size(), baseline);
        return new DbLog(prior, baseline);
    }

    /** 기록 전체의 최대 id(없으면 0) — 거부·실패 기록도 센다. 이번 시도의 쓰기 판정은 이 id보다 큰 기록만 본다. */
    static long baselineIdOf(List<DbChangeView> changes) {
        long max = 0L;
        if (changes == null) return max;
        for (DbChangeView c : changes) {
            if (c.id() != null && c.id() > max) max = c.id();
        }
        return max;
    }

    /** 사전 조회 실패 사유 — 같은 SQL이 두 번 실행되지 않게 claude를 돌리지 않고 끝낸다. 메시지가 없으면 예외 이름. */
    static String dbLogFailureReason(Exception e) {
        return "DB 변경 기록 조회 실패: " + messageOf(e);
    }

    /** SQL 스크립트 파일을 기록대로 만들지 못했을 때 사유에 붙는 말(스펙 2026-11-03 §6.6 — wip 경로는 사유 끝). */
    static final String SCRIPT_NOT_APPLIED = " — SQL 스크립트 파일 미반영";

    /** 커밋 직전 사후 조회가 실패해 wip으로 남길 때 wip 커밋 메시지의 중단 이유. */
    static final String DB_LOG_WIP_WHY = "DB 변경 기록 조회 실패";

    /** 커밋 직전 사후 조회 실패 사유 — 스크립트 파일 없이는 PR을 만들지 않고 wip으로 남긴다. 메시지가 없으면 예외 이름. */
    static String postDbLogFailureReason(DbLogUnavailable e) {
        return DB_LOG_WIP_WHY + SCRIPT_NOT_APPLIED + ": " + messageOf(e.failure);
    }

    private static String messageOf(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 커밋 직전 DB 변경 기록 사후 조회 실패 — 호출부가 commit/push 실패와 구분해 wip 보존·구현실패로 끝낸다. */
    static final class DbLogUnavailable extends Exception {
        private final Exception failure;

        DbLogUnavailable(Exception failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }
    }

    /**
     * 커밋 직전 준비 (스펙 2026-11-03 §6.6) — 일반 구현·수정 회차 공용. baseSha는 일반 구현이면 베이스 커밋, 회차면 브랜치 끝(tip),
     * baselineId는 claude 실행 전 기록의 최대 id다. 순서가 중요하다.
     * <ol>
     *   <li>세션이 쓴 스크립트 파일(누적 파일 경로·manual 경로)을 강제 추가 — 무시 규칙이 있어도 아래 변경 판정에 보이게</li>
     *   <li>DB 기록이 필요 없는 구현이면 끝 — 성공 판정이 코드 변경뿐이라 commitAndPush의 "nothing to commit" 판정과 같다(git 호출도 그대로)</li>
     *   <li>워커가 파일을 쓰기 <b>전에</b> 이번 시도의 코드 변경(남은 변경 또는 baseSha 이후 커밋)을 구한다</li>
     *   <li>기록 사후 조회 — 실패하면 {@link DbLogUnavailable}</li>
     *   <li>성공 판정({@link DbScriptFile#hasWork})이 거짓이면 commitAndPush와 같은 "nothing to commit"</li>
     *   <li>누적 기록이 있으면 스크립트 파일을 쓰고 강제 추가 — 기록이 없으면 세션이 쓴 파일을 덮어쓰지 않고, manual 파일은 건드리지 않는다</li>
     * </ol>
     *
     * @return 사후 조회한 기록(PR 본문 "DB 변경" 절의 재료) — 조회하지 않았으면 빈 목록
     */
    private List<DbChangeView> prepareCommit(WorkerTaskResponse task, File dir, String baseSha, long baselineId)
            throws IOException, InterruptedException, DbLogUnavailable {
        forceAddSessionScripts(task, dir);
        if (!needsDbLog(task)) return List.of();
        boolean codeChanged = gitOps.hasChanges(dir) || gitOps.hasCommitsSince(dir, baseSha);
        List<DbChangeView> changes;
        try {
            changes = requireDbChanges(task);
        } catch (Exception e) {
            throw new DbLogUnavailable(e);
        }
        Optional<String> script = DbScriptFile.render(task.id(), changes);
        boolean wrote = DbScriptFile.wroteSince(changes, baselineId);
        if (!DbScriptFile.hasWork(taskTypeOf(task), codeChanged, wrote, script.isPresent())) {
            throw new IOException(GitOpsService.NOTHING_TO_COMMIT);
        }
        if (script.isPresent()) {
            String rel = dbSpecOf(task).scriptPath();
            DbScriptFile.write(dir.toPath(), rel, script.get());
            gitOps.forceAdd(dir, rel);
            log.info("SQL 스크립트 파일 작성: task={} {} (기록 {}건, 이번 시도 쓰기 {})", task.id(), rel, changes.size(), wrote);
        }
        return changes;
    }

    /**
     * 스크립트 파일을 만들 기록 — 본문이 없으면(null) "기록 없음"이 아니라 조회 실패다. 기록 없음으로 읽으면 실행한 SQL이 파일에서 빠진다
     * (스펙 §11 반목표). 실제 클라이언트는 이미 IllegalStateException을 던진다(WorkerHttpClient.dbChanges).
     */
    private List<DbChangeView> requireDbChanges(WorkerTaskResponse task) {
        List<DbChangeView> changes = http.dbChanges(task.id());
        if (changes == null) throw new IllegalStateException("DB 변경 기록 응답 본문이 없다");
        return changes;
    }

    /** 리뷰 메모 뒤에 DB 메모를 빈 줄 하나로 이어 붙인다 — 하나만 있으면 그것만, 둘 다 없으면 empty. */
    static Optional<String> joinNotes(Optional<String> reviewNotes, Optional<String> dbNotes) {
        if (reviewNotes.isPresent() && dbNotes.isPresent()) return Optional.of(reviewNotes.get() + "\n\n" + dbNotes.get());
        return reviewNotes.isPresent() ? reviewNotes : dbNotes;
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

    /**
     * 세션이 쓸 수 있는 SQL 스크립트 파일 — 워커의 누적 파일 경로({@code dbSpecOf(task).scriptPath()}, 카탈로그 폴더 반영)와 쓰기 도구 없이 이전
     * 기록이 있을 때 세션이 쓰는 manual 경로(Task 14). 이 순서로 강제 추가한다.
     */
    static List<String> sessionScriptPaths(WorkerTaskResponse task) {
        String script = dbSpecOf(task).scriptPath();
        return List.of(script, ImplementationPrompts.manualScriptPath(script));
    }

    /**
     * 세션이 쓴 SQL 스크립트 파일({@link #sessionScriptPaths})이 worktree에 있으면 index에 강제로 올린다(스펙 2026-11-03 §6.6). 타입과 무관 —
     * 파일이 있으면 늘 올린다. 대상 레포의 {@code .gitignore}가 그 경로를 무시해도 커밋·WIP에 들어가게 하고, 이것이 먼저 일어나야
     * {@code git status --porcelain} 기반 "변경 있음" 판단에도 보인다(Review Focus 3). 없는 파일은 건너뛰고, 하나라도 실패하면 예외.
     */
    private void forceAddSessionScripts(WorkerTaskResponse task, File worktreeDir)
            throws IOException, InterruptedException {
        for (String rel : sessionScriptPaths(task)) forceAddIfPresent(worktreeDir, rel);
    }

    private void forceAddIfPresent(File worktreeDir, String rel) throws IOException, InterruptedException {
        if (!Files.isRegularFile(worktreeDir.toPath().resolve(rel), LinkOption.NOFOLLOW_LINKS)) return;
        gitOps.forceAdd(worktreeDir, rel);
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
        return preservePartialWork(task, wt, baseSha, "구현", why);
    }

    /**
     * what은 WIP 커밋 메시지의 무엇이 중단됐는지("구현"·"수정 N회차"). 수정 회차는 baseSha가 회차 브랜치 끝(tip)이고 같은 브랜치에
     * fast-forward로 올린다 — 다음 재시도가 그 위에서 이어 간다(스펙 2026-11-03 §7).
     */
    private PartialOutcome preservePartialWork(WorkerTaskResponse task, WorktreeService.CreatedWorktree wt,
                                               String baseSha, String what, String why) {
        return preservePartialWork(task, wt, baseSha, what, why, true);
    }

    /**
     * writeScript가 거짓이면 SQL 스크립트 파일의 기록 조회·쓰기를 건너뛴다 — 커밋 직전 사후 조회가 이미 실패해 그 사유로 보고하는 경로
     * (다시 조회하지 않고, 사유 끝 표시도 붙이지 않는다).
     */
    private PartialOutcome preservePartialWork(WorkerTaskResponse task, WorktreeService.CreatedWorktree wt,
                                               String baseSha, String what, String why, boolean writeScript) {
        // git add -A가 .netis/·.design/·배포 env 목록을 WIP 커밋에 담지 않도록 먼저 지운다
        ImplementationScratch.deleteAll(wt.dir().toPath());
        // 실패 경로에서도 누적 SQL 스크립트 파일을 만든다(스펙 2026-11-03 §6.6) — 수정 회차의 wip은 PR 브랜치에 그대로 올라가 "수정 중단"
        // 뒤 종료 때 병합된다. 기록을 못 받거나 못 쓰면 wip은 그대로 올리고 사유 끝에 알린다
        boolean scriptMissing = writeScript && !writeScriptForWip(task, wt.dir());
        // 스크립트 파일(워커가 방금 쓴 누적 파일 포함)과 세션의 manual 파일은 무시 규칙(db/·*.sql)이 있어도 WIP에 들어가야 한다 —
        // git add -A는 무시된 파일을 담지 않는다. 이미 중단 보고 중이라 실패해도 경고만 남기고 다음 파일·보존을 계속한다
        for (String rel : sessionScriptPaths(task)) {
            try {
                forceAddIfPresent(wt.dir(), rel);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("스크립트 파일 강제 추가 실패 — WIP 보존은 계속합니다: task={} {} {}",
                        task.id(), rel, GitRemotes.mask(String.valueOf(e.getMessage())));
            }
        }
        GitOpsService.PartialWork w = gitOps.preservePartialWork(wt.dir(), task.repoRef(), wt.branchName(), baseSha,
                "wip: " + what + " 중단(" + why + ") — task #" + task.id());
        PartialOutcome kept = PartialOutcome.of(w, wt.branchName());
        return scriptMissing ? kept.withNote(SCRIPT_NOT_APPLIED) : kept;
    }

    /**
     * wip 경로의 SQL 스크립트 파일 — DB 기록이 필요 없는 구현이면 아무것도 안 하고 true. 누적 기록이 있으면 쓰고(없으면 세션 파일을 덮어쓰지
     * 않는다) true, 조회·쓰기가 실패하면 경고를 남기고 false. 강제 추가는 호출부가 세션 파일과 함께 한다.
     */
    private boolean writeScriptForWip(WorkerTaskResponse task, File dir) {
        if (!needsDbLog(task)) return true;
        try {
            Optional<String> script = DbScriptFile.render(task.id(), requireDbChanges(task));
            if (script.isPresent()) DbScriptFile.write(dir.toPath(), dbSpecOf(task).scriptPath(), script.get());
            return true;
        } catch (Exception e) {
            log.warn("wip의 SQL 스크립트 파일 미반영: task={} {}", task.id(), GitRemotes.mask(String.valueOf(e.getMessage())));
            return false;
        }
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

        /** 사유 끝에 note를 더 붙인 결과(SQL 스크립트 파일 미반영 등). */
        PartialOutcome withNote(String note) {
            return new PartialOutcome(reasonSuffix + note, headSha);
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

        // 인터뷰 목업은 출발점으로 design worktree의 .netis/mockups에 둔다 — 디자인은 커밋하지 않고 수확은 .design-out만 읽으므로
        // exclude는 부르지 않는다(계획 "설계와 다름 ③"). 못 두면 claude 없이 디자인실패 뒤 worktree를 지운다(성공 수확 뒤와 같다)
        try {
            placeInterviewMockups(task, wt.toPath());
        } catch (IOException e) {
            safePostDesignFailure(task.id(), MOCKUP_PLACE_FAILED + messageOf(e), null);
            worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.DESIGN, task.id());
            return;
        }

        // 반려 재실행(claim에 이전 디자인이 있다)이면 이전 디자인과 피드백이 목업보다 우선이다(계획 Task 7 "설계와 다름 ①").
        // 디자인 claim에는 계획 본문이 없어 '전달되지 않은' 번호는 쓰지 않는다. 목업이 없으면 ""라 프롬프트가 예전과 같다
        boolean previousDesign = task.designMarkdown() != null && !task.designMarkdown().isBlank();
        String prompt = renderDesignPrompt(props.designPromptTemplate(), task)
                + InterviewMockupPrompts.designSection(task.interviewMockups(), previousDesign)
                + DbMcpWorkerSupport.promptSection(task.dbMcp());
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
        log.info("디자인 생성 완료: task={} screens 포함", task.id());
    }

    /** 디자인 프롬프트 — 템플릿의 {title}·{description}·{analysis_markdown}·{subtasks_json}·{previous_design_markdown}·{feedback_history}만 치환한다. */
    static String renderDesignPrompt(String template, WorkerTaskResponse task) {
        return template
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", nvl(task.analysisMarkdown(), ""))
                .replace("{subtasks_json}", nvl(task.subtasksJson(), "[]"))
                .replace("{previous_design_markdown}", nvl(task.designMarkdown(), "(없음 — 첫 생성)"))
                .replace("{feedback_history}", nvl(task.feedbackHistoryJson(), "[]"));
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
        // 실제로 빌드한 커밋(브랜치 끝, 전체 40자)도 싣는다 — API가 head_sha를 그 값으로 맞춘다(스펙 2026-11-03 §7, 못 읽었으면 null)
        reporter.reportTerminal(task.id(), WorkerResultRequest.deployed(
                props.id(), result.url(), result.containerId(), result.hostPort(),
                result.image(), durationMs, result.log(), result.builtSha()));
        log.info("배포 완료: task={} url={} 빌드 커밋={}", task.id(), result.url(),
                result.builtSha() == null ? "(모름)" : DeployService.shortSha(result.builtSha()));
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
                                              String planSection, String commitRule, String taskTypeSection) {
        String tpl = props.implementationPromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = defaultImplementationPrompt();
        }
        return fillImplementationPrompt(tpl, task, baseSha, branchName, planSection, commitRule, taskTypeSection);
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

    /** 타입 절 없는 채우기 — 기존 호출부·테스트용(타입 없음과 같은 결과). */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName,
                                           String planSection, String commitRule) {
        return fillImplementationPrompt(tpl, task, baseSha, branchName, planSection, commitRule, "");
    }

    /**
     * 구현 프롬프트 템플릿의 placeholder를 채운다(yml 템플릿·기본 템플릿 공용). planSection·commitRule은 방식별
     * (ImplementationPrompts, 스펙 2026-10-15 §6), taskTypeSection은 작업 타입별(스펙 2026-11-03 §5.2 — 타입 없음은 빈 문자열).
     * 끝에 배포 env 목록 지시를 붙인다.
     * {commit_rule}·{task_type_section}은 계획·디자인 본문을 넣기 전에 치환한다(본문 안의 같은 글자를 건드리지 않게).
     */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName,
                                           String planSection, String commitRule, String taskTypeSection) {
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
                .replace("{task_type_section}", taskTypeSection)
                .replace("{design_section}", renderDesignSection(task))
                .replace("{plan_section}", planSection)
                + DEPLOY_ENV_INSTRUCTION;
    }

    /**
     * 승인된 디자인이 있으면 구현 프롬프트에 삽입할 블록, 없으면 빈 문자열 (기존 작업 불변). 수정 회차 프롬프트는 이 절 대신
     * 참고용 H3(FixRoundPrompts.designSection)를 쓴다.
     */
    static String renderDesignSection(WorkerTaskResponse task) {
        if (!ImplementationPrompts.hasApprovedDesign(task)) return "";
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
                {plan_section}{task_type_section}
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

    /** DB 변경 절 없는 PR/MR 본문 — 기존 호출부·테스트용(DB 기록이 없는 구현과 같은 결과). */
    static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha,
                               String modeLine, String reviewNotes, List<String> refWarnings) {
        return renderPrBody(task, durationMs, headSha, modeLine, reviewNotes, refWarnings, "");
    }

    /**
     * PR/MR 본문. 전체가 PR_BODY_MAX_CHARS를 넘으면 사전 분석(설계 문서)을 먼저 줄이고, 그래도 넘으면 리뷰 메모를 줄인다 —
     * 줄인 절은 끝에 `…(이하 생략)`. 머리 줄(방식 줄 포함)·참조 절·목업 절·DB 변경 절과 꼬리는 그대로 둔다. 본문이 길면 이미 push한 구현이
     * "PR/MR 생성 실패"로 끝나기 때문이다(GitHub 65,536자 한도). 참조 절(고정 커밋·변경 경고, 스펙 2026-10-24 §6.6)은
     * 머리 다음에 오고, 참조가 없으면 비어 본문이 예전과 같다. "구현 기준 목업" 절(설계 2026-11-03 §7 — 번호·제목·이름·버전, 최대 10줄)은
     * 참조 절 바로 다음이고 넘긴 목업이 없으면 비어 본문이 예전과 같다. DB 변경 절({@link #dbChangeSection}, 스펙 2026-11-03 §6.5)은 그 다음이고
     * 스스로 승인 대기 목록을 {@link #PR_PENDING_SQL_MAX_CHARS}로 줄여 오며, 비면 본문이 예전과 같다.
     */
    static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha,
                               String modeLine, String reviewNotes, List<String> refWarnings, String dbSection) {
        boolean gitlab = task.repoRef().isGitlab();
        boolean hasDesign = task.designMarkdown() != null && !task.designMarkdown().isBlank();
        String designLine = !hasDesign ? ""
                : "- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #" + task.id() + " 디자인 카드 참고)\n";
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
        String mockupSection = InterviewMockupPrompts.prSection(task.interviewMockups());
        String analysisTitle = "## 사전 분석\n";
        String notesTitle = "## 리뷰 메모\n";

        String analysis = task.analysisMarkdown() == null ? "" : task.analysisMarkdown();
        String notes = reviewNotes;
        String db = dbSection == null ? "" : dbSection;
        // 절 제목·구분 줄바꿈·머리·참조 절·목업 절·DB 변경 절·꼬리를 뺀, 분석과 메모가 나눠 쓸 수 있는 글자 수
        int room = PR_BODY_MAX_CHARS - head.length() - refSection.length() - mockupSection.length() - db.length()
                - tail.length()
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
                + mockupSection
                + db
                + analysisTitle + analysis + "\n\n"
                + (notes == null ? "" : notesTitle + notes + "\n\n")
                + tail;
    }

    /** PR 본문 "DB 변경" 절의 승인 대기 SQL 목록 상한(자) — 이 절 혼자 본문 예산(PR_BODY_MAX_CHARS)을 다 쓰지 않게. */
    static final int PR_PENDING_SQL_MAX_CHARS = 20_000;
    /** 승인 대기 목록에서 상한보다 큰 문장을 자른 끝 표시 — 코드 블록 안이라 SQL 주석으로. */
    private static final String PENDING_SQL_CUT_MARK = "\n-- …(이하 생략)\n";

    /**
     * PR 본문 "DB 변경" 절 (스펙 2026-11-03 §6.5·§6.6) — 개발 DB에 실행한 SQL 수, 승인 대기 SQL 수와 목록, 스크립트 파일 경로. 끝에 빈 줄.
     * <ul>
     *   <li>scriptPath는 워커의 누적 파일 — 실행됐거나 승인 대기로 남은 기록이 있을 때만(그때 워커가 그 파일을 쓴다) {@code - 스크립트 파일:} 줄</li>
     *   <li>sessionPath는 {@code sessionScriptPath(db, writeTools)} — DB 작업에 쓰기 도구가 없으면 세션이 SQL을 적은 그 파일을
     *       {@code - 개발 DB 미적용 — 수동 적용 필요:} 줄에 쓴다</li>
     *   <li>자동 마이그레이션 레포는 기록·스크립트 파일이 없고 개발 DB에는 시험 컨테이너가 적용하므로(§6.8) 절이 없다. 기록도 수동 적용도 없으면
     *       절이 없다(본문이 예전과 같다)</li>
     * </ul>
     * 승인 대기 목록은 스크립트 파일과 같은 모양의 SQL 코드 블록이고 {@link #PR_PENDING_SQL_MAX_CHARS}를 넘으면 나머지는 개수만 적는다.
     */
    static String dbChangeSection(List<DbChangeView> changes, String scriptPath, String sessionPath,
                                  boolean writeTools, boolean migrationManaged, TaskType type) {
        if (migrationManaged) return "";
        int executed = 0;
        List<DbChangeView> deferred = new ArrayList<>();
        for (DbChangeView c : changes == null ? List.<DbChangeView>of() : changes) {
            if (DbScriptFile.EXECUTED.equals(c.classification())) executed++;
            else if (DbScriptFile.DEFERRED.equals(c.classification())) deferred.add(c);
        }
        boolean recorded = executed > 0 || !deferred.isEmpty();
        boolean manual = type == TaskType.DB_WORK && !writeTools;
        if (!recorded && !manual) return "";
        StringBuilder sb = new StringBuilder("## DB 변경\n");
        if (recorded) {
            sb.append("- 개발 DB에 실행한 SQL: ").append(executed).append("건\n")
              .append("- 병합 뒤 실행(승인 대기): ").append(deferred.size()).append("건\n")
              .append("- 스크립트 파일: `").append(scriptPath).append("`\n");
        }
        if (manual) sb.append("- 개발 DB 미적용 — 수동 적용 필요: `").append(sessionPath).append("`\n");
        if (!deferred.isEmpty()) sb.append(pendingSqlBlock(deferred));
        return sb.append('\n').toString();
    }

    /** 승인 대기 SQL 목록 — 한 코드 블록에 스크립트 파일과 같은 모양으로, 상한을 넘으면 앞에서부터 싣고 나머지는 개수만. */
    private static String pendingSqlBlock(List<DbChangeView> deferred) {
        StringBuilder sql = new StringBuilder();
        int listed = 0;
        for (DbChangeView c : deferred) {
            String stmt = (listed == 0 ? "" : "\n") + DbScriptFile.statement(c);
            if (sql.length() + stmt.length() > PR_PENDING_SQL_MAX_CHARS) {
                if (listed == 0) {
                    // 문장 하나가 상한보다 크다 — 비워 두지 않고 잘라서 싣는다(서로게이트 쌍은 가운데서 자르지 않는다)
                    int keep = PR_PENDING_SQL_MAX_CHARS - PENDING_SQL_CUT_MARK.length();
                    if (Character.isHighSurrogate(stmt.charAt(keep - 1))) keep--;
                    sql.append(stmt, 0, keep).append(PENDING_SQL_CUT_MARK);
                    listed = 1;
                }
                break;
            }
            sql.append(stmt);
            listed++;
        }
        int omitted = deferred.size() - listed;
        if (omitted > 0) sql.append("\n-- …외 ").append(omitted).append("건 생략 — 스크립트 파일·작업 상세의 DB 변경에서 확인\n");
        String fence = "`".repeat(Math.max(3, longestBacktickRun(sql) + 1));
        return "\n병합 뒤 실행할 SQL(승인 대기 — 관리자가 PR 병합 뒤 작업 상세에서 실행):\n"
                + fence + "sql\n" + sql + fence + "\n";
    }

    private static int longestBacktickRun(CharSequence s) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            run = s.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
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

    private void safePostImplementationFailure(WorkerTaskResponse task, int attempt, String reason,
                                               String headBranch, String headSha, String log_) {
        safePostImplementationFailure(task, attempt, reason, headBranch, headSha, log_, null);
    }

    /**
     * 구현실패 보고. 수정 회차면 회차 표지(withFixMarks)를 달고, 브랜치를 주지 않은 경로(참조 준비·처리 중 예외 등)도 회차 브랜치를
     * 싣는다 — API는 회차 브랜치일 때만 headSha를 받는다. 회차가 아니면 예전 그대로.
     */
    private void safePostImplementationFailure(WorkerTaskResponse task, int attempt, String reason,
                                               String headBranch, String headSha, String log_,
                                               WorkerResultRequest.UsageReport usage) {
        WorkerTaskResponse.FixRoundSpec fix = fixOf(task);
        String branch = headBranch == null && fix != null ? fix.headBranch() : headBranch;
        reporter.reportTerminal(task.id(), withFixMarks(new WorkerResultRequest(
                props.id(), TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, safeReason(reason),
                null, null, branch, headSha, log_,
                null, null, null, null, null,
                null, null, null, null, usage).withImplementationAttempt(attempt), fix));
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
