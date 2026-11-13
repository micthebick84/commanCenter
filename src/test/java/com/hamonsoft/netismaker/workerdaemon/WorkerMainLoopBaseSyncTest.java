package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 기준 브랜치 반영 회차 — 작업 브랜치에 기준 브랜치를 병합해 같은 브랜치로 push (BASE_SYNC). */
class WorkerMainLoopBaseSyncTest {

    private static final String BRANCH = "netismaker/task-42-x";
    private static final String TIP = "abc1234" + "0".repeat(33);
    private static final String LABEL = "기준 브랜치 반영 1회차";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private BaseSyncMerger merger;
    private Path wt;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        repos = mock(GitRepoCache.class);
        claude = mock(ClaudeExecAdapter.class);
        worktrees = mock(WorktreeService.class);
        gitOps = mock(GitOpsService.class);
        reporter = mock(ResultReporter.class);
        merger = mock(BaseSyncMerger.class);
        wt = Files.createDirectories(tmp.resolve("wt"));

        when(repos.fetchForBaseSync(any(), eq(BRANCH), eq("main")))
                .thenReturn(new GitRepoCache.BaseSyncRepo(tmp.toFile(), "origin/main"));
        when(worktrees.createOnBranch(any(), anyString(), eq(BRANCH), eq(42L)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(gitOps.headSha(any())).thenReturn(TIP);
        when(gitOps.pushHead(any(), any(), eq(BRANCH))).thenReturn("merged1");
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""), mock(ClaudeAuthProbe.class), merger);
    }

    private static Task task() {
        Task t = Task.create("acme/widgets", "main", "Add login", "로그인 화면을 만든다", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setImplementationAttempt(2);
        t.setImplementationTimeoutMinutes(80);
        return t;
    }

    private WorkerTaskResponse claims(Task t, String kind) {
        var fix = new WorkerTaskResponse.FixRoundSpec(7L, 1, 1, "기준 브랜치 main 반영", List.of(), BRANCH, TIP, kind);
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: 로그인 화면", fix, null);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    private WorkerTaskResponse claims() {
        return claims(task(), "BASE_SYNC");
    }

    private void mergeStarts(BaseSyncMerger.Outcome outcome, List<String> conflicted, List<String> binary,
                             List<String> markerless) throws Exception {
        when(merger.start(any(), eq("origin/main")))
                .thenReturn(new BaseSyncMerger.Started(outcome, conflicted, binary, markerless, "snap"));
    }

    private void conflictOn(String... files) throws Exception {
        mergeStarts(BaseSyncMerger.Outcome.CONFLICTED, List.of(files), List.of(), List.of());
    }

    private record Exec(String prompt, Duration timeout, List<?> extras) {}

    private AtomicReference<Exec> claudeEnds(int exitCode) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
                    return new ClaudeExecAdapter.ExecResult(exitCode, "claude 출력", 10, null);
                });
        return seen;
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(42L), cap.capture());
        return cap.getValue();
    }

    private static void hasFixMarks(WorkerResultRequest r) {
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.fixAttempt()).isEqualTo(1);
        assertThat(r.fixRequestId()).isEqualTo(7L);
    }

    private void assertFailedWithoutPush(WorkerResultRequest r, String reason) throws Exception {
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).isEqualTo(reason);
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        hasFixMarks(r);
        verify(gitOps, never()).pushHead(any(), any(), anyString());
        verify(gitOps, never()).preservePartialWork(any(), any(), any(), any(), any());
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }

    @Test
    void 이미_반영돼_있으면_Claude와_push_없이_tip으로_성공보고() throws Exception {
        claims();
        mergeStarts(BaseSyncMerger.Outcome.UP_TO_DATE, List.of(), List.of(), List.of());

        loop().pollAndProcess();

        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verify(gitOps, never()).pushHead(any(), any(), anyString());
        verify(merger, never()).commitMerge(any(), anyList(), anyString());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.headSha()).isEqualTo(TIP);
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.implementationLog()).isEqualTo("기준 브랜치 main가 이미 반영돼 있습니다.");
        hasFixMarks(r);
        verify(worktrees).discard(anyString(), eq(WorktreeKind.TASK), eq(42L));
    }

    @Test
    void 충돌_없으면_Claude_없이_병합커밋_push_성공보고() throws Exception {
        claims();
        mergeStarts(BaseSyncMerger.Outcome.CLEAN, List.of(), List.of(), List.of());

        loop().pollAndProcess();

        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verify(merger).commitMerge(eq(wt.toFile()), eq(List.of()), startsWith("merge: 기준 브랜치 main 반영 — Add login"));
        verify(gitOps).pushHead(eq(wt.toFile()), any(), eq(BRANCH));
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.headSha()).isEqualTo("merged1");
        assertThat(r.implementationLog()).isEqualTo("충돌 없이 병합했습니다.");
        hasFixMarks(r);
        verify(worktrees).discard(anyString(), eq(WorktreeKind.TASK), eq(42L));
    }

    @Test
    void 충돌이면_Claude가_풀고_병합커밋_push() throws Exception {
        claims();
        conflictOn("PLAN.md");
        when(merger.unresolved(any(), eq(List.of("PLAN.md")))).thenReturn(List.of());
        when(merger.strayChanges(any(), any())).thenReturn(List.of());
        AtomicReference<Exec> seen = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(seen.get().prompt()).contains("- PLAN.md");
        assertThat(seen.get().extras()).isEmpty();
        assertThat(seen.get().timeout()).isEqualTo(Duration.ofMinutes(80));
        verify(merger).commitMerge(eq(wt.toFile()), eq(List.of("PLAN.md")), startsWith("merge: 기준 브랜치 main 반영 — "));
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.headSha()).isEqualTo("merged1");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.implementationLog()).startsWith("[" + LABEL + "] 충돌 파일: PLAN.md").contains("claude 출력");
        hasFixMarks(r);
        verify(worktrees).discard(anyString(), eq(WorktreeKind.TASK), eq(42L));
    }

    @Test
    void 충돌이_남으면_구현실패이고_push하지_않는다() throws Exception {
        claims();
        conflictOn("PLAN.md");
        when(merger.unresolved(any(), anyList())).thenReturn(List.of("PLAN.md"));
        claudeEnds(0);

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": 충돌 해결 실패 — 남은 충돌 파일: PLAN.md");
        assertThat(r.headSha()).isEqualTo(TIP);
        assertThat(r.implementationLog()).contains("claude 출력");
        verify(merger, never()).commitMerge(any(), anyList(), anyString());
    }

    @Test
    void 충돌파일_밖을_고치면_구현실패() throws Exception {
        claims();
        conflictOn("PLAN.md");
        when(merger.unresolved(any(), anyList())).thenReturn(List.of());
        when(merger.strayChanges(any(), any())).thenReturn(List.of("README.md"));
        claudeEnds(0);

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": 충돌 파일 밖을 수정함 — README.md");
        assertThat(r.headSha()).isEqualTo(TIP);
        assertThat(r.implementationLog()).contains("claude 출력");
        verify(merger, never()).commitMerge(any(), anyList(), anyString());
    }

    @Test
    void 바이너리_충돌은_Claude_없이_구현실패() throws Exception {
        claims();
        mergeStarts(BaseSyncMerger.Outcome.CONFLICTED, List.of("PLAN.md", "logo.png"), List.of("logo.png"), List.of());

        loop().pollAndProcess();

        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": 바이너리 파일 충돌 — logo.png — GitLab에서 직접 해결하세요");
        assertThat(r.headSha()).isEqualTo(TIP);
    }

    @Test
    void 표식없는_충돌은_Claude_없이_구현실패() throws Exception {
        claims();
        mergeStarts(BaseSyncMerger.Outcome.CONFLICTED, List.of("PLAN.md", "old.txt", "b.txt"), List.of(),
                List.of("old.txt", "b.txt"));

        loop().pollAndProcess();

        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": 표식 없는 충돌(한쪽에서 삭제·이름 변경 등) — old.txt, b.txt"
                + " — GitLab에서 직접 해결하세요");
        assertThat(r.headSha()).isEqualTo(TIP);
    }

    @Test
    void 공통조상이_없으면_구현실패() throws Exception {
        claims();
        when(repos.fetchForBaseSync(any(), eq(BRANCH), eq("main")))
                .thenThrow(new GitRepoCache.NoCommonAncestorException());

        loop().pollAndProcess();

        verify(merger, never()).start(any(), anyString());
        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": 공통 조상을 찾지 못함(이력 500커밋 초과) — GitLab에서 직접 해결하세요");
        assertThat(r.headSha()).isNull();
    }

    @Test
    void fetch가_실패하면_사유에_메시지를_싣는다() throws Exception {
        claims();
        when(repos.fetchForBaseSync(any(), eq(BRANCH), eq("main"))).thenThrow(new IOException("network down"));

        loop().pollAndProcess();

        assertFailedWithoutPush(reported(), LABEL + ": 레포 fetch 실패: network down");
    }

    @Test
    void 반영할_브랜치가_기준_브랜치와_같으면_fetch_없이_구현실패() throws Exception {
        var fix = new WorkerTaskResponse.FixRoundSpec(7L, 1, 1, "x", List.of(), "main", TIP, "BASE_SYNC");
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(task(), null, null, null, fix, null)));

        loop().pollAndProcess();

        verify(repos, never()).fetchForBaseSync(any(), anyString(), anyString());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason())
                .isEqualTo(LABEL + ": 반영할 브랜치가 비었거나 기준 브랜치와 같음(main) — 진행하지 않음");
    }

    @Test
    void push가_거절되면_구현실패_사유에_거절_안내() throws Exception {
        claims();
        conflictOn("PLAN.md");
        when(merger.unresolved(any(), anyList())).thenReturn(List.of());
        when(merger.strayChanges(any(), any())).thenReturn(List.of());
        claudeEnds(0);
        when(gitOps.pushHead(any(), any(), eq(BRANCH)))
                .thenThrow(new IOException("! [rejected] HEAD -> " + BRANCH + " (non-fast-forward)"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith(LABEL + ": push 실패: ")
                .endsWith(WorkerMainLoop.PUSH_REJECTED_NOTE);
        assertThat(r.headSha()).isEqualTo(TIP);
        hasFixMarks(r);
        verify(gitOps, never()).preservePartialWork(any(), any(), any(), any(), any());
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }

    @Test
    void claude가_비정상_종료하면_구현실패이고_Claude_출력을_꼬리로_싣는다() throws Exception {
        claims();
        conflictOn("PLAN.md");
        claudeEnds(2);

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertFailedWithoutPush(r, LABEL + ": claude exit=2\nclaude 출력");
        verify(merger, never()).unresolved(any(), anyList());
    }

    @Test
    void 원격_끝이_기록과_다르면_구현_로그_머리에_한_줄() throws Exception {
        claims();
        when(gitOps.headSha(any())).thenReturn("ffff111" + "0".repeat(33));
        mergeStarts(BaseSyncMerger.Outcome.UP_TO_DATE, List.of(), List.of(), List.of());

        loop().pollAndProcess();

        assertThat(reported().implementationLog())
                .startsWith("[" + LABEL + "] " + FixRoundPrompts.remoteAheadNote("ffff111" + "0".repeat(33), TIP))
                .endsWith("기준 브랜치 main가 이미 반영돼 있습니다.");
    }

    @Test
    void 참조repo가_있는_작업의_반영회차는_참조를_준비하지_않는다() throws Exception {
        Task t = task();
        t.setRefRepos(List.of(new RefRepo(12L, "옛앱", "apps/old-app", "https://gitlab.example/apps/old-app.git",
                "release/6.5", "a".repeat(40), "2026-10-24T01:00:00Z", null)));
        claims(t, "BASE_SYNC");
        mergeStarts(BaseSyncMerger.Outcome.UP_TO_DATE, List.of(), List.of(), List.of());

        loop().pollAndProcess();

        verify(repos, never()).prepareRefCommitUnderLock(any(), any(), any());
        verify(worktrees, never()).createRefWorktree(anyLong(), any());
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void FIX회차는_지금처럼_implementFixRound로_간다() throws Exception {
        claims(task(), "FIX");
        when(repos.fetchOnly(any(), eq(BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        claudeEnds(0);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");

        loop().pollAndProcess();

        verify(merger, never()).start(any(), anyString());
        verify(repos).fetchOnly(any(), eq(BRANCH));
        verify(repos, never()).fetchForBaseSync(any(), anyString(), anyString());
    }
}
