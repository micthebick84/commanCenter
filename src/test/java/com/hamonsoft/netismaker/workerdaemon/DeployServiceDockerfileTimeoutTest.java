package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Dockerfile 생성 claude는 docker build 상한(build-timeout)이 아니라 자기 상한(dockerfile-timeout)을 받는다.
 * docker build 상한을 늘려도 claude가 붙잡는 시간은 늘지 않게 — 직렬 워커라 그동안 다른 작업이 모두 멈춘다.
 */
class DeployServiceDockerfileTimeoutTest {

    @TempDir Path tmp;

    @Test
    void Dockerfile_생성은_dockerfile_timeout을_쓴다() throws Exception {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        GitRepoCache repos = mock(GitRepoCache.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        ClaudeExecAdapter claude = mock(ClaudeExecAdapter.class);
        File cache = Files.createDirectories(tmp.resolve("cache")).toFile();
        File wt = Files.createDirectories(tmp.resolve("wt")).toFile();   // Dockerfile 없음 → claude 생성
        when(repos.fetchOnly(any(), anyString())).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "sha"));
        when(worktrees.createForDeploy(any(), anyString(), anyString(), anyLong())).thenReturn(wt);
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "실패", 10, null));
        DeployService service = new DeployService(props, repos, worktrees, claude, mock(DeployTarget.class));

        var task = new WorkerTaskResponse(9L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-9-add-login",
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.deploy(task, line -> {}))
                .isInstanceOf(DeployService.DeployException.class);

        verify(claude).exec(anyString(), eq(wt), eq(Duration.ofMinutes(7)), anyList(), eq(true));
    }
}
