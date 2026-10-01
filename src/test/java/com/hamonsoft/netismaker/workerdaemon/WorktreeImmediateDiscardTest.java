package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployFailedException;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 성공한 작업만 그 자리에서 worktree를 지우고, 실패·정리 예외는 결과에 영향이 없는지. */
class WorktreeImmediateDiscardTest {

    @TempDir Path tmp;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    private static WorkerTaskResponse task(WorkerTaskResponse.Kind kind) {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc", kind,
                List.of(), null, null, "netismaker/task-7-add-login", "abc1234def", List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    // ---- 구현 ----

    private record Loop(WorkerMainLoop loop, WorktreeService worktrees, ResultReporter reporter,
                        GitOpsService gitOps, ClaudeExecAdapter claude) {}

    private Loop implementationLoop(int claudeExit) throws Exception {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        GitRepoCache repos = mock(GitRepoCache.class);
        ClaudeExecAdapter claude = mock(ClaudeExecAdapter.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        GitOpsService gitOps = mock(GitOpsService.class);
        ResultReporter reporter = mock(ResultReporter.class);
        File dir = Files.createDirectories(tmp.resolve("wt")).toFile();

        when(http.nextTask()).thenReturn(Optional.of(task(WorkerTaskResponse.Kind.IMPLEMENTATION)));
        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(dir, "base123"));
        when(worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login")))
                .thenReturn(new WorktreeService.CreatedWorktree(dir, "netismaker/task-7-add-login"));
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(claudeExit, "done", 10, null));
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));

        WorkerMainLoop loop = new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class));
        return new Loop(loop, worktrees, reporter, gitOps, claude);
    }

    @Test
    void successful_implementation_discards_its_worktree_after_reporting() throws Exception {
        Loop l = implementationLoop(0);

        l.loop().pollAndProcess();

        InOrder order = inOrder(l.reporter(), l.worktrees());
        order.verify(l.reporter()).reportTerminal(eq(7L), any());
        order.verify(l.worktrees()).discard("acme/widgets", WorktreeKind.TASK, 7L);
    }

    @Test
    void failed_implementation_keeps_its_worktree() throws Exception {
        Loop l = implementationLoop(1);   // claude exit=1 → 구현실패 경로

        l.loop().pollAndProcess();

        verify(l.reporter()).reportTerminal(eq(7L), any());
        verify(l.worktrees(), never()).discard(anyString(), any(), anyLong());
    }

    // ---- 배포 ----

    private DeployService deployService(WorktreeService worktrees, DeployTarget target) throws Exception {
        GitRepoCache repos = mock(GitRepoCache.class);
        Path wt = Files.createDirectories(tmp.resolve("deploy-7"));
        Files.writeString(wt.resolve("Dockerfile"), "FROM scratch\nEXPOSE 8080\n");
        when(repos.fetchOnly(any(), eq("netismaker/task-7-add-login")))
                .thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "head456"));
        when(worktrees.createForDeploy(any(), eq("acme/widgets"), eq("netismaker/task-7-add-login"), eq(7L)))
                .thenReturn(wt.toFile());
        return new DeployService(props(), repos, worktrees, mock(ClaudeExecAdapter.class), target);
    }

    private static DeployTarget target(boolean succeed) {
        return new DeployTarget() {
            @Override
            public DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) throws Exception {
                if (!succeed) throw new DeployFailedException("health check failed", "log");
                return new DeployResult("http://h:19000", "cid", 19000, spec.imageName(), "");
            }
            @Override public void stop(String containerName) { }
            @Override public DeployStatus status(String containerName) { return DeployStatus.UNKNOWN; }
        };
    }

    @Test
    void successful_deploy_discards_its_worktree() throws Exception {
        WorktreeService worktrees = mock(WorktreeService.class);

        deployService(worktrees, target(true)).deploy(task(WorkerTaskResponse.Kind.DEPLOY), line -> { });

        verify(worktrees).discard("acme/widgets", WorktreeKind.DEPLOY, 7L);
    }

    @Test
    void failed_deploy_keeps_its_worktree() throws Exception {
        WorktreeService worktrees = mock(WorktreeService.class);

        assertThatThrownBy(() -> deployService(worktrees, target(false))
                .deploy(task(WorkerTaskResponse.Kind.DEPLOY), line -> { }))
                .isInstanceOf(DeployService.DeployException.class);
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }
}
