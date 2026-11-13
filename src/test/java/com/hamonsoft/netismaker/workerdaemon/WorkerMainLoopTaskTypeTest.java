package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 작업 타입이 구현 흐름에 닿는 곳 — 프롬프트 타입 절, 커밋·PR 제목 앞머리, 세션이 쓴 스크립트 파일의 강제 추가
 * (스펙 2026-11-03 §4.1·§5.2·§6.6). 하네스는 WorkerMainLoopImplementationModeTest를 따른다.
 */
class WorkerMainLoopTaskTypeTest {

    private static final String SCRIPT = "db/symphony/task-7.sql";
    private static final String FIX_BRANCH = "netismaker/task-7-add-login";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
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
        wt = Files.createDirectories(tmp.resolve("wt"));

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), FIX_BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        // 수정 회차 하네스
        when(repos.fetchOnly(any(), eq(FIX_BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        when(worktrees.createOnBranch(any(), anyString(), eq(FIX_BRANCH), eq(7L)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), FIX_BRANCH));
        when(gitOps.headSha(any())).thenReturn("abc1234" + "0".repeat(33));
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""), mock(ClaudeAuthProbe.class), mock(BaseSyncMerger.class));
    }

    /** 타입을 가진 구현 작업. type이 null이면 타입 없음. */
    private WorkerTaskResponse claims(TaskType type, String plan) {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setTaskType(type);
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, plan);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    /** API가 모르는 타입 문자열을 실어 보낸 claim(다른 버전 API). */
    private void claimsRawType(String wireType) {
        WorkerTaskResponse task = new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), "# 설계 문서", "[]", null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, "### 작업 1: A",
                null, null, null, null, null, null,
                new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, wireType));
        when(http.nextTask()).thenReturn(Optional.of(task));
    }

    /** 수정 2회차 claim — 작업 타입은 BUG_FIX여도 회차 흐름은 타입과 무관해야 한다. */
    private void claimsFixRound(TaskType type) {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setTaskType(type);
        var fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, "저장 뒤 목록이 그대로", List.of(), FIX_BRANCH, "abc1234");
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: A", fix,
                ImplementationMode.DIRECT)));
    }

    private record Exec(String prompt) {}

    private AtomicReference<Exec> claudeSucceeds(Consumer<Path> sideEffect) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0)));
                    sideEffect.accept(wt);
                    return new ClaudeExecAdapter.ExecResult(0, "done", 10, null);
                });
        return seen;
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String commitMessage() throws Exception {
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(gitOps).commitAndPush(any(), any(), anyString(), msg.capture(), any());
        return msg.getValue();
    }

    private String prTitle() throws Exception {
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), title.capture(), anyString());
        return title.getValue();
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    // ---- 커밋·PR 제목 앞머리 ----

    @Test
    void 버그픽스는_커밋과_PR제목이_fix() throws Exception {
        claims(TaskType.BUG_FIX, "### 작업 1: A");
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(commitMessage()).startsWith("fix: Add login\n\ntask #7\n\nCo-Authored-By: netisMaker <");
        assertThat(prTitle()).isEqualTo("fix: Add login");
    }

    @Test
    void DB작업은_커밋과_PR제목이_db() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(commitMessage()).startsWith("db: Add login\n\ntask #7\n");
        assertThat(prTitle()).isEqualTo("db: Add login");
    }

    @Test
    void 신규개발과_기능수정은_feat() throws Exception {
        for (TaskType type : List.of(TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE)) {
            setUp();
            claims(type, "### 작업 1: A");
            claudeSucceeds(dir -> { });

            loop().pollAndProcess();

            assertThat(commitMessage()).startsWith("feat: Add login\n\ntask #7\n");
            assertThat(prTitle()).isEqualTo("feat: Add login");
        }
    }

    @Test
    void 타입없음과_모르는_타입은_feat_PR제목_그대로() throws Exception {
        claims(null, "### 작업 1: A");
        claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        assertThat(commitMessage()).startsWith("feat: Add login\n\ntask #7\n");
        assertThat(prTitle()).isEqualTo("Add login");

        setUp();
        claimsRawType("SOMETHING_NEW");
        claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        assertThat(commitMessage()).startsWith("feat: Add login\n\ntask #7\n");
        assertThat(prTitle()).isEqualTo("Add login");
    }

    @Test
    void 수정회차는_타입과_무관() throws Exception {
        claimsFixRound(TaskType.BUG_FIX);
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(gitOps).commitAndPush(any(), any(), eq(FIX_BRANCH), msg.capture(), any());
        assertThat(msg.getValue()).startsWith("fix: 수정 2회차 — Add login\n\ntask #7\n");
        assertThat(exec.get().prompt()).doesNotContain("## 작업 타입");
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());

        setUp();
        claimsFixRound(TaskType.DB_WORK);
        claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        ArgumentCaptor<String> dbMsg = ArgumentCaptor.forClass(String.class);
        verify(gitOps).commitAndPush(any(), any(), eq(FIX_BRANCH), dbMsg.capture(), any());
        assertThat(dbMsg.getValue()).startsWith("fix: 수정 2회차 — Add login\n");
    }

    // ---- 프롬프트 타입 절 ----

    @Test
    void 버그픽스_프롬프트에_타입절() throws Exception {
        claims(TaskType.BUG_FIX, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(exec.get().prompt()).contains("## 작업 타입: 버그 픽스\n먼저 계획의 재현 방법으로");
    }

    @Test
    void DB작업_프롬프트는_쓰기도구_없음_문구와_기본_스크립트_경로() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(exec.get().prompt()).contains("## 작업 타입: DB 작업\n이 구현에는 개발 DB 쓰기 도구가 없습니다.")
                .contains("`" + SCRIPT + "` 파일에 실행 순서대로 적으세요");
    }

    @Test
    void 추가_지시가_없는_타입과_타입없음은_타입절이_없다() throws Exception {
        for (TaskType type : new TaskType[]{null, TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE}) {
            setUp();
            claims(type, "### 작업 1: A");
            AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

            loop().pollAndProcess();

            assertThat(exec.get().prompt()).doesNotContain("## 작업 타입").doesNotContain("{task_type_section}");
        }
    }

    // ---- 세션이 쓴 스크립트 파일의 강제 추가 ----

    @Test
    void 세션이_쓴_스크립트는_커밋전에_강제추가() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "CREATE TABLE t_new (id int);\n"));

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).commitAndPush(eq(wt.toFile()), any(), anyString(), anyString(), eq("base123"));
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 스크립트_파일이_없으면_강제추가하지_않는다() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(gitOps, never()).forceAdd(any(), anyString());
        verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 스크립트_강제추가는_타입과_무관() throws Exception {
        claims(null, "### 작업 1: A");
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "SELECT 1;\n"));

        loop().pollAndProcess();

        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
    }

    @Test
    void 강제추가가_실패하면_commit_push_실패로_끝낸다() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "SELECT 1;\n"));
        doThrow(new IOException("git add 거부")).when(gitOps).forceAdd(any(), anyString());

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("commit/push 실패: git add 거부");
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void wip경로도_세션_스크립트를_강제추가() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    write(wt.resolve(SCRIPT), "CREATE TABLE t_new (id int);\n");
                    throw new IOException("claude timeout after PT45M");
                });
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), anyString(), eq("base123"), anyString());
        assertThat(reported().headSha()).isEqualTo("wip999");
    }

    @Test
    void 비정상_종료_wip도_강제추가() throws Exception {
        claims(TaskType.BUG_FIX, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    write(wt.resolve(SCRIPT), "SELECT 1;\n");
                    return new ClaudeExecAdapter.ExecResult(1, "boom", 10, null);
                });
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(0, null, null));

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void wip경로_강제추가_실패는_경고만_남기고_보존을_계속한다() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    write(wt.resolve(SCRIPT), "SELECT 1;\n");
                    throw new IOException("claude timeout after PT45M");
                });
        doThrow(new IOException("index.lock")).when(gitOps).forceAdd(any(), anyString());
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.headSha()).isEqualTo("wip999");
    }

    @Test
    void wip경로에서_스크립트가_없으면_강제추가하지_않는다() throws Exception {
        claims(TaskType.DB_WORK, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after PT45M"));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(0, null, null));

        loop().pollAndProcess();

        verify(gitOps, never()).forceAdd(any(), anyString());
        verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void 수정회차도_세션_스크립트를_강제추가() throws Exception {
        claimsFixRound(TaskType.DB_WORK);
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "SELECT 1;\n"));

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).commitAndPush(any(), any(), eq(FIX_BRANCH), anyString(), any());
    }

    // ---- 순수 함수 ----

    @Test
    void taskTypeOf는_claim의_구현_블록에서_읽고_모르는_값은_타입없음() {
        WorkerTaskResponse withSpec = taskWith(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, "BUG_FIX"));

        assertThat(WorkerMainLoop.taskTypeOf(withSpec)).isEqualTo(TaskType.BUG_FIX);
        assertThat(WorkerMainLoop.taskTypeOf(taskWith(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, " DB_WORK "))))
                .isEqualTo(TaskType.DB_WORK);
        assertThat(WorkerMainLoop.taskTypeOf(taskWith(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, "NOPE"))))
                .isNull();
        assertThat(WorkerMainLoop.taskTypeOf(taskWith(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, null))))
                .isNull();
        assertThat(WorkerMainLoop.taskTypeOf(taskWith(null))).isNull();
    }

    private static WorkerTaskResponse taskWith(WorkerTaskResponse.ImplementationSpec spec) {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), null, null, null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, spec);
    }
}
