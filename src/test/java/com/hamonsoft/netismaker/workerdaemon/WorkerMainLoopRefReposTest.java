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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 구현 흐름의 참조 repo — 준비 순서·시간 차감·프롬프트·계획 파일 머리·변경 검사·PR 본문·정리 (스펙 2026-10-24 §6). */
class WorkerMainLoopRefReposTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String BRANCH = "netismaker/task-7-add-login";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private Path wt;
    private Path pluginDir;
    /** 테스트 시계 — createRefWorktree 응답만 앞으로 돌린다. 0이 아닌 값에서 시작해 차이만 쓰는지 본다. */
    private final AtomicLong now = new AtomicLong(987_654_321L);

    private WorkerTaskResponse task;
    private WorkerTaskResponse.TaskRefRepo ref12;
    private WorkerTaskResponse.TaskRefRepo ref13;
    private WorktreeService.RefWorktree rw12;
    private WorktreeService.RefWorktree rw13;

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

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    int attempt = inv.getArgument(5);
                    return new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH + (attempt >= 2 ? "-r" + attempt : ""));
                });
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
        rw12 = new WorktreeService.RefWorktree(12L, "옛앱", "release/6.5", SHA_A, "_gitlab/apps+old-app",
                tmp.resolve("refs/12").toFile(), List.of());
        rw13 = new WorktreeService.RefWorktree(13L, "둘째", "release/9", SHA_B, "_gitlab/apps+second",
                tmp.resolve("refs/13").toFile(), List.of());
    }

    private WorkerMainLoop loop(String pluginPath) {
        WorkerMainLoop loop = new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(pluginPath));
        loop.nanoClock = now::get;
        return loop;
    }

    /** 인터뷰 계획이 있는 구현 claim — refs가 있으면 참조 12 "옛앱"·13 "둘째"를 싣고 각 준비 응답을 rw12·rw13으로. */
    private void claims(ImplementationMode mode, int attempt, int timeoutMinutes, String plan, boolean withRefs)
            throws Exception {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(mode);
        t.setImplementationAttempt(attempt);
        t.setImplementationTimeoutMinutes(timeoutMinutes);
        if (withRefs) {
            t.setRefRepos(List.of(
                    new RefRepo(12L, "옛앱", "apps/old-app", "https://gitlab.example/apps/old-app.git",
                            "release/6.5", SHA_A, "2026-10-24T01:00:00Z", null),
                    new RefRepo(13L, "둘째", "apps/second", "https://gitlab.example/apps/second.git",
                            "release/9", SHA_B, "2026-10-24T01:00:00Z", null)));
        }
        task = WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, plan);
        when(http.nextTask()).thenReturn(Optional.of(task));
        if (withRefs) {
            ref12 = task.refRepos().get(0);
            ref13 = task.refRepos().get(1);
            when(worktrees.createRefWorktree(7L, ref12)).thenReturn(rw12);
            when(worktrees.createRefWorktree(7L, ref13)).thenReturn(rw13);
        }
    }

    private void prepTakes(Duration first, Duration second) throws Exception {
        when(worktrees.createRefWorktree(7L, ref12)).thenAnswer(inv -> {
            now.addAndGet(first.toNanos());
            return rw12;
        });
        when(worktrees.createRefWorktree(7L, ref13)).thenAnswer(inv -> {
            now.addAndGet(second.toNanos());
            return rw13;
        });
    }

    private record Exec(String prompt, Duration timeout, ClaudeExecAdapter.ExecOptions options, String planFile) {}

    /** claude가 exitCode로 끝난다 — 그 시점의 프롬프트·상한·옵션·계획 파일(없으면 null)을 남긴다. */
    private AtomicReference<Exec> claudeEnds(int exitCode) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Path plan = wt.resolve(ImplementationScratch.planPath(7L));
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(2), inv.getArgument(7),
                            Files.exists(plan) ? Files.readString(plan) : null));
                    return new ClaudeExecAdapter.ExecResult(exitCode, "done", 10, null);
                });
        return seen;
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    private String prBody() throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());
        return body.getValue();
    }

    private void verifyRefsDiscarded() {
        verify(worktrees).discardRefWorktree(7L, ref12);
        verify(worktrees).discardRefWorktree(7L, ref13);
    }

    @ParameterizedTest
    @EnumSource(value = ImplementationMode.class, names = {"DIRECT", "REVIEW"})
    void refs_are_prepared_in_order_before_the_target_fetch_and_the_target_worktree(ImplementationMode mode)
            throws Exception {
        String plan = "# 계획\n### 작업 1: A\n";
        claims(mode, 1, 80, plan, true);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop("").pollAndProcess();

        // 대상 fetch → 대상 worktree 사이에는 아무것도 끼지 않는다 — worktree 기준(origin/main)과 baseSha가 같게
        InOrder order = inOrder(repos, worktrees, claude, reporter);
        order.verify(worktrees).createRefWorktree(7L, ref12);
        order.verify(worktrees).createRefWorktree(7L, ref13);
        order.verify(repos).ensureFresh(any(), eq("main"));
        order.verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt());
        order.verify(claude).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        order.verify(reporter).reportTerminal(eq(7L), any());
        order.verify(worktrees).discard("acme/widgets", WorktreeKind.TASK, 7L);
        order.verify(worktrees).discardRefWorktree(7L, ref12);
        order.verify(worktrees).discardRefWorktree(7L, ref13);

        assertThat(exec.get().prompt()).endsWith(RefRepoPrompts.promptSection(List.of(rw12, rw13)))
                .doesNotContain(ImplementationPrompts.SDD_REF_LINE);
        // 참조는 --add-dir로 넘기지 않는다 — 절대 경로로 읽는다 (스펙 §1.2·§6.7)
        assertThat(exec.get().options().extraArgs()).doesNotContain("--add-dir")
                .noneMatch(a -> a.contains(rw12.dir().getPath()) || a.contains(rw13.dir().getPath()));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(80));
        if (mode == ImplementationMode.REVIEW) {
            assertThat(exec.get().planFile()).isEqualTo(RefRepoPrompts.planFileHeader(List.of(rw12, rw13)) + plan);
        } else {
            assertThat(exec.get().planFile()).isNull();
        }
        assertThat(prBody()).contains("\n\n## 참조 repo (읽기 전용)\n- 옛앱 · release/6.5@aaaaaaa\n"
                + "- 둘째 · release/9@bbbbbbb\n\n## 사전 분석\n").doesNotContain("변경 감지");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.implementationAttempt()).isEqualTo(1);
        assertThat(r.refWarnings()).isNull();
        verify(worktrees).refChangeWarning(rw12);
        verify(worktrees).refChangeWarning(rw13);
    }

    @Test
    void a_ref_prep_failure_reports_before_claude_and_cleans_up() throws Exception {
        claims(ImplementationMode.REVIEW, 2, 80, "### 작업 1: A", true);
        when(worktrees.createRefWorktree(7L, ref13)).thenThrow(new IOException(
                "git failed (128): Cloning into 'x'...\nfatal: Remote branch release/9 not found in upstream origin\n"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).isEqualTo(
                "참조 repo 준비 실패: 둘째(release/9) — fatal: Remote branch release/9 not found in upstream origin");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.headBranch()).isNull();
        verifyNoInteractions(repos);   // 대상은 fetch도 하지 않는다
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString(), anyInt());
        verifyNoInteractions(claude);
        verifyNoInteractions(gitOps);
        verifyRefsDiscarded();   // 실패한 참조의 남은 폴더까지
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
        verify(worktrees, never()).refChangeWarning(any());
    }

    /** 참조를 다 준비한 뒤 대상 fetch가 실패하면 — 기존 사유 그대로 보고하고, 준비한 참조는 보고 전에 지운다. */
    @Test
    void a_target_fetch_failure_after_the_refs_are_ready_discards_them() throws Exception {
        claims(ImplementationMode.REVIEW, 2, 80, "### 작업 1: A", true);
        when(repos.ensureFresh(any(), eq("main")))
                .thenThrow(new IOException("git failed (128): fatal: couldn't find remote ref main"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).isEqualTo("레포 fetch 실패: git failed (128): fatal: couldn't find remote ref main");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.headBranch()).isNull();
        InOrder order = inOrder(worktrees, repos, reporter);
        order.verify(worktrees).createRefWorktree(7L, ref12);
        order.verify(worktrees).createRefWorktree(7L, ref13);
        order.verify(repos).ensureFresh(any(), eq("main"));
        order.verify(worktrees).discardRefWorktree(7L, ref12);
        order.verify(worktrees).discardRefWorktree(7L, ref13);
        order.verify(reporter).reportTerminal(eq(7L), any());
        verifyRefsDiscarded();   // 한 번씩만 — 보고 뒤에 또 지우지 않는다
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString(), anyInt());
        verifyNoInteractions(claude);
        verifyNoInteractions(gitOps);
        verify(worktrees, never()).refChangeWarning(any());
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }

    /** 참조가 없는 작업의 대상 fetch 실패는 예전 그대로 — 참조 메서드를 부르지 않는다. */
    @Test
    void a_target_fetch_failure_without_refs_is_reported_as_before() throws Exception {
        claims(ImplementationMode.DIRECT, 1, 45, null, false);
        when(repos.ensureFresh(any(), eq("main"))).thenThrow(new IOException("network down"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).isEqualTo("레포 fetch 실패: network down");
        verify(worktrees, never()).createRefWorktree(anyLong(), any());
        verify(worktrees, never()).discardRefWorktree(anyLong(), any());
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString(), anyInt());
        verifyNoInteractions(claude);
    }

    /** claim의 참조 원소가 이상해(카탈로그 id 없음 등) 런타임 예외가 나도 준비 실패로 끝난다 — 잡히지 않은 예외가 아니다. */
    @Test
    void a_runtime_error_while_preparing_a_ref_is_a_prep_failure_too() throws Exception {
        claims(ImplementationMode.DIRECT, 1, 45, null, true);
        when(worktrees.createRefWorktree(7L, ref12)).thenThrow(new NullPointerException());

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).isEqualTo("참조 repo 준비 실패: 옛앱(release/6.5) — NullPointerException");
        verify(worktrees, never()).createRefWorktree(7L, ref13);   // 하나라도 실패하면 거기서 멈춘다
        verify(worktrees).discardRefWorktree(7L, ref12);
        verify(worktrees, never()).discardRefWorktree(7L, ref13);
        verifyNoInteractions(repos);
        verifyNoInteractions(claude);
    }

    @Test
    void ref_prep_time_comes_out_of_the_claude_limit() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        prepTakes(Duration.ofMinutes(4), Duration.ofMinutes(6));
        AtomicReference<Exec> exec = claudeEnds(0);

        loop("").pollAndProcess();

        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(70));
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @ParameterizedTest
    @ValueSource(ints = {80, 90})
    void no_time_left_fails_as_a_timeout_without_a_target_worktree(int prepMinutes) throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        prepTakes(Duration.ofMinutes(prepMinutes - 1), Duration.ofMinutes(1));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("시간 초과")
                .isEqualTo("시간 초과: 참조 repo 준비에 " + prepMinutes * 60 + "초를 써 구현 상한(80분)이 남지 않았습니다");
        verifyNoInteractions(repos);   // 남은 시간이 없으면 대상 fetch도 하지 않는다
        verifyNoInteractions(claude);
        verify(worktrees, never()).create(any(), anyString(), anyString(), anyLong(), anyString(), anyInt());
        verifyRefsDiscarded();
    }

    @Test
    void the_plan_file_starts_with_the_ref_section_and_keeps_its_task_count() throws Exception {
        String plan = "### 작업 1: A\n### 작업 2: B";
        claims(ImplementationMode.SDD, 1, 240, plan, true);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop(pluginDir.toString()).pollAndProcess();

        String planFile = exec.get().planFile();
        assertThat(planFile).startsWith("## 참조 repo (읽기 전용)\n")
                .isEqualTo(RefRepoPrompts.planFileHeader(List.of(rw12, rw13))
                        + ImplementationScratch.normalizeTaskHeadings(plan));
        assertThat(ImplementationScratch.countTaskHeadings(planFile)).isEqualTo(2);
        assertThat(exec.get().options().extraArgs()).containsExactly("--plugin-dir", pluginDir.toString());
        assertThat(exec.get().prompt()).contains(ImplementationPrompts.SDD_REF_LINE)
                .endsWith(RefRepoPrompts.promptSection(List.of(rw12, rw13)));
        assertThat(prBody()).contains("- **구현 방식**: SDD\n");
    }

    @Test
    void ref_changes_go_to_the_pr_body_and_the_result() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        claudeEnds(0);
        when(worktrees.refChangeWarning(rw12)).thenReturn(Optional.of("옛앱: 파일 3개"));

        loop("").pollAndProcess();

        // claude가 끝난 뒤, 커밋 전에 본다
        InOrder order = inOrder(claude, worktrees, gitOps);
        order.verify(claude).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        order.verify(worktrees).refChangeWarning(rw12);
        order.verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), any());
        assertThat(prBody()).contains("- 둘째 · release/9@bbbbbbb\n"
                + "> ⚠ 참조 repo 변경 감지(이 PR에는 포함 안 됨): 옛앱: 파일 3개\n\n## 사전 분석\n");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.refWarnings()).containsExactly("옛앱: 파일 3개");
        assertThat(r.failureReason()).isNull();
        verifyRefsDiscarded();
    }

    @Test
    void a_failed_run_still_checks_refs_and_appends_the_warning() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        claudeEnds(1);
        when(worktrees.refChangeWarning(rw13)).thenReturn(Optional.of("둘째: HEAD가 고정 커밋과 다름"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("claude exit=1")
                .endsWith(" — 참조 repo 변경 감지: 둘째: HEAD가 고정 커밋과 다름");
        assertThat(r.refWarnings()).isNull();   // 실패 보고에는 사유에만 붙인다
        verifyRefsDiscarded();
        verify(worktrees, never()).discard(anyString(), any(), anyLong());   // 실패한 대상 worktree는 디버그용으로 남긴다
    }

    @Test
    void a_claude_exception_and_a_pr_failure_carry_the_warning_too() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after PT70M"));
        when(worktrees.refChangeWarning(rw12)).thenReturn(Optional.of("옛앱: 참조 폴더가 지워짐"));

        loop("").pollAndProcess();

        assertThat(reported().failureReason()).startsWith("claude exec 실패: claude timeout after PT70M")
                .endsWith(" — 참조 repo 변경 감지: 옛앱: 참조 폴더가 지워짐");
        verifyRefsDiscarded();

        // PR 생성 실패 — 이미 push한 SHA와 함께 같은 꼬리
        reset(reporter, claude);
        claudeEnds(0);
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IOException("gh 거부"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.failureReason()).startsWith("PR/MR 생성 실패: gh 거부")
                .endsWith(" — 참조 repo 변경 감지: 옛앱: 참조 폴더가 지워짐");
        assertThat(r.headSha()).isEqualTo("head456");
    }

    @Test
    void refs_are_discarded_when_an_unexpected_exception_escapes() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A", true);
        when(worktrees.ensureWorkerExcludes(any())).thenThrow(new IllegalStateException("boom"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.failureReason()).contains("처리 중 예외: IllegalStateException: boom");
        // 참조 정리(finally)가 바깥 catch의 보고보다 먼저 돈다
        InOrder order = inOrder(worktrees, reporter);
        order.verify(worktrees).discardRefWorktree(7L, ref12);
        order.verify(worktrees).discardRefWorktree(7L, ref13);
        order.verify(reporter).reportTerminal(eq(7L), any());
        verifyNoInteractions(claude);
    }

    @ParameterizedTest
    @EnumSource(ImplementationMode.class)
    void a_task_without_refs_never_touches_the_ref_methods(ImplementationMode mode) throws Exception {
        String plan = "# 계획\n### 작업 1: A\n### 작업 2: B\n";
        claims(mode, 1, 80, plan, false);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop(pluginDir.toString()).pollAndProcess();

        verify(worktrees, never()).createRefWorktree(anyLong(), any());
        verify(worktrees, never()).refChangeWarning(any());
        verify(worktrees, never()).discardRefWorktree(anyLong(), any());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.refWarnings()).isNull();
        assertThat(prBody()).doesNotContain("## 참조 repo");
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(80));
        // (a) SDD 참조 줄 없음
        assertThat(exec.get().prompt()).doesNotContain(ImplementationPrompts.SDD_REF_LINE);
        // (b) 계획 파일은 예전 그대로 — 직접 방식은 계획 파일을 쓰지 않는다 (R16.2)
        switch (mode) {
            case DIRECT -> assertThat(exec.get().planFile()).isNull();
            case REVIEW -> assertThat(exec.get().planFile()).isEqualTo(plan);
            case SDD -> assertThat(exec.get().planFile()).isEqualTo(ImplementationScratch.normalizeTaskHeadings(plan));
        }
        // (c) 프롬프트는 참조 도입 전 조립과 바이트 그대로 (R16.1)
        boolean designWritten = mode == ImplementationMode.SDD;
        assertThat(exec.get().prompt()).isEqualTo(WorkerMainLoop.fillImplementationPrompt(
                WorkerMainLoop.defaultImplementationPrompt(), task, "base123", BRANCH,
                ImplementationPrompts.planSection(task, mode, "base123", designWritten),
                ImplementationPrompts.commitRule(mode))
                + DbMcpWorkerSupport.promptSection(task.dbMcp()));
    }
}
