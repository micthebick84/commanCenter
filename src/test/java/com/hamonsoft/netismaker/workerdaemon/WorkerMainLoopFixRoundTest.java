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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 수정 회차 구현 — 같은 브랜치 끝에서 이어 고치고 PR 없이 같은 브랜치로 push (스펙 2026-11-03 §7). */
class WorkerMainLoopFixRoundTest {

    private static final String BRANCH = "netismaker/task-42-x";
    private static final String RECORDED_HEAD = "abc1234";
    /** 기록된 head와 같은 원격 끝(전체 40자). */
    private static final String SAME_TIP = "abc1234" + "0".repeat(33);
    private static final String BODY = "대상장비 탭 갱신 안 됨 — 저장 뒤 목록이 그대로입니다";
    private static final String PREVIOUS = "장기분석 차트가 비어 있음";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private Path wt;
    private Path pluginDir;

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
        wt = Files.createDirectories(tmp.resolve("wt"));
        pluginDir = tmp.resolve("superpowers");
        Files.createDirectories(pluginDir.resolve("skills/subagent-driven-development"));
        Files.writeString(pluginDir.resolve(SuperpowersPlugin.SDD_SKILL), "# SDD");

        when(repos.fetchOnly(any(), eq(BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        when(worktrees.createOnBranch(any(), anyString(), eq(BRANCH), eq(42L)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        when(gitOps.headSha(any())).thenReturn(SAME_TIP);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(pluginDir.toString()), mock(ClaudeAuthProbe.class));
    }

    private static Task task(ImplementationMode taskMode) {
        Task t = Task.create("acme/widgets", "main", "Add login", "로그인 화면을 만든다", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setImplementationMode(taskMode);
        t.setImplementationAttempt(2);            // 작업의 시도 번호 — 회차 안 시도 번호(1)와 다르게
        t.setImplementationTimeoutMinutes(80);
        return t;
    }

    /** 수정 2회차(요청 #9, 회차 안 1회째) claim — 앞 회차 본문 하나. */
    private WorkerTaskResponse claims(ImplementationMode roundMode) {
        return claims(task(ImplementationMode.DIRECT), roundMode);
    }

    private WorkerTaskResponse claims(Task t, ImplementationMode roundMode) {
        var fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, BODY, List.of(PREVIOUS), BRANCH, RECORDED_HEAD);
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: 로그인 화면", fix, roundMode);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    private record Exec(String prompt, Duration timeout, ClaudeExecAdapter.ExecOptions options) {}

    private AtomicReference<Exec> claudeEnds(int exitCode, Consumer<Path> sideEffect) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(2), inv.getArgument(7)));
                    sideEffect.accept(wt);
                    return new ClaudeExecAdapter.ExecResult(exitCode, "claude 출력", 10, null);
                });
        return seen;
    }

    private AtomicReference<Exec> claudeSucceeds() throws Exception {
        return claudeEnds(0, dir -> { });
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(42L), cap.capture());
        return cap.getValue();
    }

    /** 회차 보고 표지 — 작업의 시도 번호·회차 안 시도 번호·요청 id. */
    private static void hasFixMarks(WorkerResultRequest r) {
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.fixAttempt()).isEqualTo(1);
        assertThat(r.fixRequestId()).isEqualTo(9L);
    }

    @Test
    void 회차는_createOnBranch를_쓰고_create는_안_부른다() throws Exception {
        claims(ImplementationMode.REVIEW);
        claudeSucceeds();

        loop().pollAndProcess();

        verify(repos).fetchOnly(any(), eq(BRANCH));
        verify(worktrees).createOnBranch(any(), eq("acme/widgets"), eq(BRANCH), eq(42L));
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString(), anyInt());
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString());
        verify(repos, never()).ensureFresh(any(), anyString());   // 베이스 브랜치 최신화도 하지 않는다
    }

    @Test
    void 프롬프트에_수정본문과_이어고치기_규칙() throws Exception {
        claims(ImplementationMode.DIRECT);
        AtomicReference<Exec> exec = claudeSucceeds();

        loop().pollAndProcess();

        assertThat(exec.get().prompt())
                .contains(BODY)
                .contains("처음부터 다시 짜지 말고")
                .contains(PREVIOUS)
                .contains("수정 2회차")
                .contains(BRANCH)
                .contains("로그인 화면을 만든다")                 // 원래 요건
                .contains("### 작업 1: 로그인 화면")               // 원래 계획(참고용)
                .contains(ImplementationPrompts.DIRECT_COMMIT_RULE)
                .contains(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION)
                .doesNotContain("마지막 리뷰")                    // 직접 방식은 리뷰 절이 없다
                .doesNotContain("원격 브랜치 끝이");               // 원격 끝 = 기록된 head
        assertThat(exec.get().prompt().indexOf(BODY)).isLessThan(exec.get().prompt().indexOf("### 작업 1: 로그인 화면"));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(80));
    }

    @Test
    void PR을_새로_만들지_않고_성공보고() throws Exception {
        claims(ImplementationMode.REVIEW);
        claudeSucceeds();

        loop().pollAndProcess();

        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(gitOps).commitAndPush(eq(wt.toFile()), any(), eq(BRANCH), msg.capture(), eq(SAME_TIP));
        assertThat(msg.getValue()).startsWith("fix: 수정 2회차 — Add login\n\ntask #42\n\nCo-Authored-By: netisMaker <");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isEqualTo("head456");
        assertThat(r.prUrl()).isNull();
        assertThat(r.prNumber()).isNull();
        assertThat(r.failureReason()).isNull();
        hasFixMarks(r);
        verify(worktrees).discard("acme/widgets", WorktreeKind.TASK, 42L);
    }

    @Test
    void 원격끝이_앞서면_그_위에서_고치고_로그에_남긴다() throws Exception {
        when(gitOps.headSha(wt.toFile())).thenReturn("tip777");
        claims(ImplementationMode.DIRECT);
        AtomicReference<Exec> exec = claudeSucceeds();

        loop().pollAndProcess();

        assertThat(exec.get().prompt()).contains("원격 브랜치 끝이 tip777").contains(RECORDED_HEAD);
        verify(gitOps).commitAndPush(any(), any(), eq(BRANCH), anyString(), eq("tip777"));
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.implementationLog()).contains("원격 브랜치 끝이 tip777").contains("claude 출력");
    }

    @Test
    void 변경이_없으면_구현실패_사유가_변경없음() throws Exception {
        claims(ImplementationMode.DIRECT);
        claudeSucceeds();
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IOException("nothing to commit — 구현 시도 후 파일 변경이 없음"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("수정 2회차: 변경 없음 — Claude가 고칠 곳을 찾지 못함");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isEqualTo(SAME_TIP);   // 올린 것이 없으면 원격 끝 그대로
        hasFixMarks(r);
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }

    @Test
    void push가_거절되면_원격이_앞섰다고_알린다() throws Exception {
        claims(ImplementationMode.DIRECT);
        claudeSucceeds();
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IOException("git failed (1): ! [rejected] HEAD -> " + BRANCH + " (fetch first)"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("commit/push 실패: ").contains("rejected")
                .contains("원격 브랜치에 다른 커밋이 올라와 fast-forward push가 거절됐습니다");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isEqualTo(SAME_TIP);   // 올린 것이 없다 — 알고 있는 원격 끝
        hasFixMarks(r);
    }

    @Test
    void claude_실패면_wip_보존하고_fixAttempt를_실어_실패보고() throws Exception {
        claims(ImplementationMode.REVIEW);
        claudeEnds(1, dir -> { });
        when(gitOps.preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq(SAME_TIP), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq(SAME_TIP), wip.capture());
        assertThat(wip.getValue()).startsWith("wip: 수정 2회차 중단(비정상 종료) — task #42");
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("claude exit=1").contains("브랜치 `" + BRANCH + "`에 올려 둠");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isEqualTo("wip999");
        hasFixMarks(r);
    }

    @Test
    void claude가_시간_초과로_끝나도_회차_표지를_단다() throws Exception {
        claims(ImplementationMode.DIRECT);
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after 80m"));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(0, null, null));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("claude exec 실패: claude timeout");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isEqualTo(SAME_TIP);
        hasFixMarks(r);
    }

    @Test
    void SDD가_와도_리뷰로_돈다() throws Exception {
        claims(ImplementationMode.SDD);
        AtomicReference<Exec> exec = claudeSucceeds();

        loop().pollAndProcess();

        assertThat(exec.get().options().extraArgs()).isEmpty();   // 플러그인이 준비돼 있어도 --plugin-dir 없음
        assertThat(exec.get().prompt())
                .contains("마지막 리뷰")
                .contains("이번 수정 요청")
                .doesNotContain("subagent-driven-development 스킬로")
                .doesNotContain("__REVIEWER__")
                .doesNotContain("__BASE_SHA__")
                .contains("git diff " + SAME_TIP);
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 리뷰_메모는_구현_로그에_붙이고_커밋_전에_지운다() throws Exception {
        claims(ImplementationMode.REVIEW);
        claudeEnds(0, dir -> {
            try {
                Files.createDirectories(dir.resolve(".netis"));
                Files.writeString(dir.resolve(".netis/review-notes.md"), "리뷰 결론: 요청한 문제는 고침");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        AtomicReference<Boolean> netisAtCommit = new AtomicReference<>();
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            netisAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop().pollAndProcess();

        assertThat(netisAtCommit.get()).isFalse();
        assertThat(reported().implementationLog()).contains("리뷰 결론: 요청한 문제는 고침").contains("claude 출력");
    }

    @Test
    void 브랜치_fetch가_실패해도_회차_표지를_단_실패보고() throws Exception {
        claims(ImplementationMode.DIRECT);
        when(repos.fetchOnly(any(), eq(BRANCH))).thenThrow(new IOException("couldn't find remote ref"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("레포 fetch 실패: ");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isNull();
        hasFixMarks(r);
        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
    }

    /** 회차 브랜치만 바꾼 claim — 깨진 데이터·구버전 API가 보낸 값. */
    private void claimsOnBranch(String headBranch) {
        var fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, BODY, List.of(PREVIOUS), headBranch, RECORDED_HEAD);
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(task(ImplementationMode.DIRECT),
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: 로그인 화면", fix,
                ImplementationMode.DIRECT)));
    }

    @Test
    void 회차_브랜치가_베이스와_같으면_fetch도_claude도_없이_실패보고() throws Exception {
        claimsOnBranch(" main ");   // 작업의 베이스 브랜치 — 여기에 push하면 베이스에 직접 커밋된다

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason())
                .isEqualTo("수정 2회차: 이어 고칠 브랜치가 비었거나 베이스 브랜치와 같음(main) — 진행하지 않음");
        assertThat(r.headBranch()).isEqualTo(" main ");   // 받은 값 그대로
        assertThat(r.headSha()).isNull();
        hasFixMarks(r);
        verify(repos, never()).fetchOnly(any(), anyString());
        verify(repos, never()).ensureFresh(any(), anyString());
        verify(worktrees, never()).createOnBranch(any(), anyString(), anyString(), anyLong());
        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 회차_브랜치가_비었으면_fetch도_claude도_없이_실패보고() throws Exception {
        for (String blank : new String[]{null, "", "  "}) {
            reset(reporter, repos, claude);
            claimsOnBranch(blank);

            loop().pollAndProcess();

            WorkerResultRequest r = reported();
            assertThat(r.status()).as(String.valueOf(blank)).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
            assertThat(r.failureReason())
                    .isEqualTo("수정 2회차: 이어 고칠 브랜치가 비었거나 베이스 브랜치와 같음(없음) — 진행하지 않음");
            assertThat(r.headBranch()).isEqualTo(blank);
            hasFixMarks(r);
            verify(repos, never()).fetchOnly(any(), any());
            verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        }
    }

    @Test
    void 회차_브랜치_판정() {
        assertThat(WorkerMainLoop.isUsableFixBranch(BRANCH, "main")).isTrue();
        assertThat(WorkerMainLoop.isUsableFixBranch(BRANCH, null)).isTrue();
        assertThat(WorkerMainLoop.isUsableFixBranch("main", "main")).isFalse();
        assertThat(WorkerMainLoop.isUsableFixBranch(" main", "main ")).isFalse();
        assertThat(WorkerMainLoop.isUsableFixBranch(null, "main")).isFalse();
        assertThat(WorkerMainLoop.isUsableFixBranch(" ", "main")).isFalse();
    }

    @Test
    void 참조_준비_실패도_회차_표지를_단다() throws Exception {
        Task t = task(ImplementationMode.DIRECT);
        t.setRefRepos(List.of(new RefRepo(12L, "옛앱", "apps/old-app", "https://gitlab.example/apps/old-app.git",
                "release/6.5", "a".repeat(40), "2026-10-24T01:00:00Z", null)));
        WorkerTaskResponse task = claims(t, ImplementationMode.DIRECT);
        when(worktrees.createRefWorktree(42L, task.refRepos().get(0))).thenThrow(new IOException("권한 없음"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("참조 repo 준비 실패: ");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        hasFixMarks(r);
        verify(repos, never()).fetchOnly(any(), anyString());
    }

    @Test
    void 처리_중_예외도_회차_표지를_단다() throws Exception {
        claims(ImplementationMode.DIRECT);
        when(worktrees.ensureWorkerExcludes(any())).thenThrow(new IllegalStateException("예상 못 한 오류"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("처리 중 예외: IllegalStateException");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        hasFixMarks(r);
    }

    /** 회차가 아닌 구현 보고에는 회차 표지가 없어야 한다 — 있으면 API가 닫힌 회차의 보고로 무시한다. */
    @Test
    void 회차가_아닌_구현은_회차_표지를_달지_않는다() throws Exception {
        Task t = task(ImplementationMode.DIRECT);
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: 로그인 화면")));
        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(42L), anyString(), anyInt()))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), "netismaker/task-42-add-login-r2"));
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
        claudeSucceeds();

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.fixAttempt()).isNull();
        assertThat(r.fixRequestId()).isNull();
        verify(worktrees, never()).createOnBranch(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void 회차가_아닌_구현_실패도_회차_표지를_달지_않는다() throws Exception {
        Task t = task(ImplementationMode.DIRECT);
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, null)));
        when(repos.ensureFresh(any(), eq("main"))).thenThrow(new IOException("fetch 실패"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.headBranch()).isNull();
        assertThat(r.fixAttempt()).isNull();
        assertThat(r.fixRequestId()).isNull();
    }
}
