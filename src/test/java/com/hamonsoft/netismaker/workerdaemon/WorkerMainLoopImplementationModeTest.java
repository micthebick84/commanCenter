package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 방식별 구현 흐름 배선 (스펙 2026-10-15 §6). */
class WorkerMainLoopImplementationModeTest {

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

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    int attempt = inv.getArgument(5);
                    return new WorktreeService.CreatedWorktree(wt.toFile(),
                            "netismaker/task-7-add-login" + (attempt >= 2 ? "-r" + attempt : ""));
                });
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
    }

    private WorkerMainLoop loop(String pluginPath) {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(pluginPath));
    }

    /** 인터뷰 플랜이 있는 구현 작업. mode가 null이면 claim에 implementation 블록이 없다(옛 API). */
    private void claims(ImplementationMode mode, int attempt, Integer timeoutMinutes, String plan) {
        WorkerTaskResponse task;
        if (mode == null) {
            task = new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                    WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), "# 설계 문서", "[]", null, null, List.of(),
                    null, null, null, null, null, null, null, null, null, null, plan);
        } else {
            Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                    "claude-opus-5-5", "high");
            ReflectionTestUtils.setField(t, "id", 7L);
            t.setImplementationMode(mode);
            t.setImplementationAttempt(attempt);
            t.setImplementationTimeoutMinutes(timeoutMinutes);
            task = WorkerTaskResponse.forImplementation(t,
                    TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, plan);
        }
        when(http.nextTask()).thenReturn(Optional.of(task));
    }

    private record Exec(String prompt, Duration timeout, ClaudeExecAdapter.ExecOptions options) {}

    private AtomicReference<Exec> claudeSucceeds(Consumer<Path> sideEffect) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(2), inv.getArgument(7)));
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

    @Test
    void old_api_payload_runs_direct_first_attempt_with_the_worker_timeout() throws Exception {
        claims(null, 0, null, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop("").pollAndProcess();

        verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), eq(1));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(45));
        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(exec.get().options().env())
                .containsEntry("GIT_AUTHOR_NAME", "netisMaker")
                .containsEntry("GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local");
        assertThat(exec.get().prompt()).contains("이 세션에서 직접 구현하세요").doesNotContain("마지막 리뷰");
        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(reported().implementationAttempt()).isEqualTo(1);
        assertThat(prBody()).contains("- **구현 방식**: 직접\n");
    }

    @Test
    void sdd_with_a_ready_plugin_adds_plugin_dir_and_hands_over_a_normalized_plan_file() throws Exception {
        claims(ImplementationMode.SDD, 2, 240, "# 계획\n### 작업 1: A\n### 작업 2: B\n");
        AtomicReference<String> planAtExec = new AtomicReference<>();
        AtomicReference<Exec> exec = claudeSucceeds(dir -> {
            try {
                planAtExec.set(Files.readString(dir.resolve(".netis/plan/task-7-plan.md")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            write(dir.resolve(".netis/review-notes.md"), "Ruling: 범위 밖 정규화는 보류");
        });
        AtomicBoolean netisAtCommit = new AtomicBoolean(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), eq("base123"))).thenAnswer(inv -> {
            netisAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop(pluginDir.toString()).pollAndProcess();

        verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), eq(2));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(240));
        assertThat(exec.get().options().extraArgs()).containsExactly("--plugin-dir", pluginDir.toString());
        assertThat(exec.get().prompt())
                .contains("superpowers:subagent-driven-development 스킬로")
                .contains(ImplementationPrompts.SDD_COMMIT_RULE)
                .contains("`.netis/plan/task-7-design.md`");
        assertThat(planAtExec.get()).contains("### Task 1: A").contains("### Task 2: B");
        assertThat(netisAtCommit.get()).isFalse();
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(prBody()).contains("- **구현 방식**: SDD (2회째)\n")
                .contains("## 리뷰 메모\nRuling: 범위 밖 정규화는 보류");
    }

    @Test
    void sdd_without_a_plugin_falls_back_to_review_and_says_so() throws Exception {
        claims(ImplementationMode.SDD, 1, 240, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop("").pollAndProcess();

        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(exec.get().prompt()).contains("마지막 리뷰").doesNotContain("subagent-driven-development 스킬로");
        assertThat(prBody()).contains("- **구현 방식**: 리뷰 (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)\n");
    }

    @Test
    void sdd_falls_back_to_review_when_the_exclude_file_cannot_be_written() throws Exception {
        claims(ImplementationMode.SDD, 1, 240, "### 작업 1: A");
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(false);
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop(pluginDir.toString()).pollAndProcess();

        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(exec.get().prompt()).contains("마지막 리뷰");
        assertThat(prBody()).contains("- **구현 방식**: 리뷰 (SDD 요청 — info/exclude 기록 실패로 리뷰로 진행)\n");
    }

    @Test
    void sdd_falls_back_to_review_when_the_plan_has_no_task_headings() throws Exception {
        claims(ImplementationMode.SDD, 1, 240, "# 계획\n작업 순서만 문장으로 적은 계획");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop(pluginDir.toString()).pollAndProcess();

        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(prBody()).contains("(SDD 요청 — 계획에서 Task N 제목을 찾지 못함으로 리뷰로 진행)");
    }

    @Test
    void review_falls_back_to_direct_when_the_plan_file_cannot_be_written() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A");
        write(wt.resolve(".netis"), "디렉터리 자리를 막는 파일");   // .netis/plan을 만들 수 없다
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop("").pollAndProcess();

        assertThat(exec.get().prompt()).contains("이 세션에서 직접 구현하세요").doesNotContain("마지막 리뷰");
        assertThat(prBody()).contains("- **구현 방식**: 직접 (리뷰 요청 — 계획 파일 쓰기 실패로");
    }

    @Test
    void review_notes_go_into_the_pr_body_masked_and_never_into_the_commit() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A");
        claudeSucceeds(dir -> write(dir.resolve(".netis/review-notes.md"),
                "결론: Important 1건 수정\n참고 https://oauth2:glpat-SECRET@gitlab.example/g/p.git"));
        // 커밋 시점의 작업 트리 — git add -A가 담을 내용이라, 이때 .netis/가 남아 있으면 메모가 커밋에 섞인다
        AtomicBoolean netisAtCommit = new AtomicBoolean(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), eq("base123"))).thenAnswer(inv -> {
            netisAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop("").pollAndProcess();

        assertThat(netisAtCommit.get()).isFalse();
        assertThat(prBody()).contains("## 리뷰 메모\n결론: Important 1건 수정").doesNotContain("glpat-SECRET");
        assertThat(wt.resolve(".netis")).doesNotExist();
    }

    @Test
    void a_timeout_preserves_partial_work_and_reports_the_pushed_branch() throws Exception {
        claims(ImplementationMode.SDD, 2, 240, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after PT4H"));
        when(gitOps.preservePartialWork(any(), any(), anyString(), eq("base123"), anyString()))
                .thenReturn(new GitOpsService.PartialWork(2, "wip999", null));

        loop(pluginDir.toString()).pollAndProcess();

        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(any(), any(), eq("netismaker/task-7-add-login-r2"), eq("base123"),
                wip.capture());
        assertThat(wip.getValue()).isEqualTo("wip: 구현 중단(시간 초과) — task #7");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("claude timeout after PT4H")
                .contains("지금까지 작업(커밋 2개)은 브랜치 `netismaker/task-7-add-login-r2`에 올려 둠");
        assertThat(r.headBranch()).isEqualTo("netismaker/task-7-add-login-r2");
        assertThat(r.headSha()).isEqualTo("wip999");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void a_non_zero_exit_reports_a_failed_partial_push() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "boom", 10, null));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip1", "push 거부"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.failureReason()).contains("claude exit=1")
                .contains("부분 작업 push 실패(worktree에만 남음): push 거부");
        assertThat(r.headSha()).isNull();
        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), wip.capture());
        assertThat(wip.getValue()).startsWith("wip: 구현 중단(비정상 종료)");
    }

    @Test
    void the_scratch_files_are_gone_before_partial_work_is_preserved() throws Exception {
        claims(ImplementationMode.SDD, 1, 240, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    write(wt.resolve(".netis/review-notes.md"), "미완 메모");
                    write(wt.resolve(".design/DESIGN.md"), "목업");
                    write(wt.resolve(DeployEnvManifest.FILE_NAME), "{\"vars\":[]}");
                    throw new IOException("claude timeout after PT4H");
                });
        // git add -A가 담을 내용 — 워커 임시 파일이 하나라도 남아 있으면 WIP 커밋에 섞인다
        AtomicReference<List<Boolean>> leftovers = new AtomicReference<>();
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            leftovers.set(List.of(Files.exists(wt.resolve(".netis")), Files.exists(wt.resolve(".design")),
                    Files.exists(wt.resolve(DeployEnvManifest.FILE_NAME))));
            return new GitOpsService.PartialWork(0, null, null);
        });

        loop(pluginDir.toString()).pollAndProcess();

        assertThat(leftovers.get()).containsExactly(false, false, false);
        WorkerResultRequest r = reported();
        assertThat(r.headSha()).isNull();   // 남길 게 없으면 사유는 그대로, head SHA 없음
        assertThat(r.failureReason()).doesNotContain("올려 둠").doesNotContain("worktree에만 남음");
    }

    @Test
    void a_commit_failure_after_claude_succeeded_is_not_preserved_and_carries_the_fallback_note() throws Exception {
        claims(ImplementationMode.SDD, 2, 240, "### 작업 1: A");
        claudeSucceeds(dir -> { });
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IOException("nothing to commit — 구현 시도 후 파일 변경이 없음"));

        loop("").pollAndProcess();   // 플러그인 없음 → SDD를 리뷰로 대신 진행

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("commit/push 실패: nothing to commit")
                .endsWith("\n- **구현 방식**: 리뷰 (2회째) (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)");
        assertThat(r.headSha()).isNull();
        assertThat(r.implementationAttempt()).isEqualTo(2);
        // claude 성공 뒤의 커밋·PR 실패는 부분 작업 보존 대상이 아니다 (스펙 §6.6)
        verify(gitOps, never()).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void a_pr_creation_failure_keeps_the_pushed_sha_and_the_attempt() throws Exception {
        claims(ImplementationMode.REVIEW, 2, 80, "### 작업 1: A");
        claudeSucceeds(dir -> { });
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IOException("gh 거부"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("PR/MR 생성 실패: gh 거부");
        assertThat(r.headBranch()).isEqualTo("netismaker/task-7-add-login-r2");
        assertThat(r.headSha()).isEqualTo("head456");   // commitAndPush까지 끝났으니 원격에 있는 SHA
        assertThat(r.implementationAttempt()).isEqualTo(2);
        verify(gitOps, never()).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void a_worktree_failure_reports_the_attempt_and_never_starts_claude() throws Exception {
        claims(ImplementationMode.REVIEW, 3, 80, "### 작업 1: A");
        doThrow(new IOException("worktree add 실패"))
                .when(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt());

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("worktree 생성 실패: worktree add 실패");
        assertThat(r.implementationAttempt()).isEqualTo(3);
        verifyNoInteractions(claude);
    }

    @Test
    void an_unexpected_exception_still_reports_the_claims_attempt() throws Exception {
        claims(ImplementationMode.REVIEW, 3, 80, "### 작업 1: A");
        // 구현 흐름 자체의 try/catch 밖에서 터지는 예외 — pollAndProcess의 마지막 catch가 보고한다
        when(worktrees.ensureWorkerExcludes(any())).thenThrow(new IllegalStateException("boom"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("처리 중 예외: IllegalStateException: boom");
        assertThat(r.implementationAttempt()).isEqualTo(3);
    }

    // ---- 순수 함수 ----

    private static WorkerTaskResponse.ImplementationSpec spec(String mode, Integer attempt, Integer timeoutMinutes) {
        return new WorkerTaskResponse.ImplementationSpec(mode, attempt, timeoutMinutes);
    }

    private static WorkerTaskResponse taskWith(WorkerTaskResponse.ImplementationSpec spec) {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), null, null, null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, spec);
    }

    @Test
    void the_attempt_defaults_to_one_when_the_claim_has_no_valid_number() {
        assertThat(WorkerMainLoop.implementationAttemptOf(taskWith(null))).isEqualTo(1);
        assertThat(WorkerMainLoop.implementationAttemptOf(taskWith(spec("REVIEW", null, 80)))).isEqualTo(1);
        assertThat(WorkerMainLoop.implementationAttemptOf(taskWith(spec("REVIEW", 0, 80)))).isEqualTo(1);
        assertThat(WorkerMainLoop.implementationAttemptOf(taskWith(spec("REVIEW", -2, 80)))).isEqualTo(1);
        assertThat(WorkerMainLoop.implementationAttemptOf(taskWith(spec("REVIEW", 3, 80)))).isEqualTo(3);
    }

    @Test
    void the_timeout_comes_from_the_claim_only_when_it_is_positive() {
        Duration fallback = Duration.ofMinutes(45);

        assertThat(WorkerMainLoop.implementationTimeoutOf(taskWith(null), fallback)).isEqualTo(fallback);
        assertThat(WorkerMainLoop.implementationTimeoutOf(taskWith(spec("SDD", 1, null)), fallback)).isEqualTo(fallback);
        assertThat(WorkerMainLoop.implementationTimeoutOf(taskWith(spec("SDD", 1, 0)), fallback)).isEqualTo(fallback);
        assertThat(WorkerMainLoop.implementationTimeoutOf(taskWith(spec("SDD", 1, -5)), fallback)).isEqualTo(fallback);
        assertThat(WorkerMainLoop.implementationTimeoutOf(taskWith(spec("SDD", 1, 240)), fallback))
                .isEqualTo(Duration.ofMinutes(240));
    }

    @Test
    void the_partial_outcome_carries_a_head_sha_only_when_the_work_was_pushed() {
        String branch = "netismaker/task-7-add-login";

        WorkerMainLoop.PartialOutcome pushed = WorkerMainLoop.PartialOutcome.of(
                new GitOpsService.PartialWork(2, "wip999", null), branch);
        assertThat(pushed.headSha()).isEqualTo("wip999");
        assertThat(pushed.reasonSuffix()).isEqualTo(" — 지금까지 작업(커밋 2개)은 브랜치 `" + branch + "`에 올려 둠");

        WorkerMainLoop.PartialOutcome pushFailed = WorkerMainLoop.PartialOutcome.of(
                new GitOpsService.PartialWork(2, "wip999", "push 거부"), branch);
        assertThat(pushFailed.headSha()).isNull();
        assertThat(pushFailed.reasonSuffix()).isEqualTo(" — 부분 작업 push 실패(worktree에만 남음): push 거부");

        // 커밋 0개에 오류 = push 전에 실패(WIP 커밋·커밋 수 확인 단계)
        WorkerMainLoop.PartialOutcome keepFailed = WorkerMainLoop.PartialOutcome.of(
                new GitOpsService.PartialWork(0, null, "git add 실패"), branch);
        assertThat(keepFailed.headSha()).isNull();
        assertThat(keepFailed.reasonSuffix()).isEqualTo(" — 부분 작업 보존 실패(worktree에만 남음): git add 실패");

        // 남길 게 없거나 결과를 못 받았으면 사유는 그대로, head SHA 없음
        for (GitOpsService.PartialWork nothing : new GitOpsService.PartialWork[] {
                new GitOpsService.PartialWork(0, null, null), null}) {
            WorkerMainLoop.PartialOutcome none = WorkerMainLoop.PartialOutcome.of(nothing, branch);
            assertThat(none.headSha()).isNull();
            assertThat(none.reasonSuffix()).isEmpty();
        }
    }

    @Test
    void the_git_author_env_never_carries_a_missing_value() {
        assertThat(WorkerMainLoop.gitAuthorEnv("봇", "bot@example.com")).containsOnly(
                entry("GIT_AUTHOR_NAME", "봇"), entry("GIT_COMMITTER_NAME", "봇"),
                entry("GIT_AUTHOR_EMAIL", "bot@example.com"), entry("GIT_COMMITTER_EMAIL", "bot@example.com"));
        assertThat(WorkerMainLoop.gitAuthorEnv(null, " ")).isEmpty();
        assertThat(WorkerMainLoop.gitAuthorEnv("봇", null)).containsOnlyKeys("GIT_AUTHOR_NAME", "GIT_COMMITTER_NAME");
        // ExecOptions는 Map.copyOf라 null 값이 하나라도 있으면 NPE — 그 자리에서 구현이 죽는다
        assertThatCode(() -> new ClaudeExecAdapter.ExecOptions(List.of(), WorkerMainLoop.gitAuthorEnv(null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void the_pr_body_places_the_mode_line_after_the_sha_and_the_review_notes_after_the_analysis() {
        WorkerTaskResponse task = taskWith(null);

        String body = WorkerMainLoop.renderPrBody(task, 12_000, "abcdef1234567",
                "- **구현 방식**: 리뷰\n", "Ruling: 보류");

        assertThat(body).contains("- **구현 SHA**: `abcdef1`\n- **구현 방식**: 리뷰\n- **소요시간**: 12초\n");
        assertThat(body.indexOf("## 사전 분석")).isLessThan(body.indexOf("## 리뷰 메모\nRuling: 보류\n\n---\n"));
        // 방식 줄·메모가 없으면 예전 본문 그대로 — "null" 글자가 새지 않는다
        assertThat(WorkerMainLoop.renderPrBody(task, 12_000, "abcdef1234567", null, null))
                .doesNotContain("null").doesNotContain("구현 방식").doesNotContain("리뷰 메모");
    }
}
