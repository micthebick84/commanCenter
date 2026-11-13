package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 배포는 브랜치 끝을 빌드한다 — 실제로 빌드한 커밋(deploy worktree의 HEAD, 전체 40자)으로 이미지 태그를 달고 배포완료 보고에
 * 싣는다(스펙 2026-11-03 §7, 판정 8). 사람이 MR에 push했거나 끊긴 수정 시도가 남긴 커밋이 있으면 기록된 head_sha와 다르다.
 * HEAD를 못 읽으면 예전처럼 기록된 head_sha로 태그를 달고 빌드 커밋은 싣지 않는다.
 */
class DeployBuiltShaTest {

    private static final String BRANCH = "netismaker/task-20-add-login";

    @TempDir Path tmp;

    private DeployTarget target;
    private DeployService service;
    private File wt;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    private static String git(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    @BeforeEach
    void setUp() throws Exception {
        GitRepoCache repos = mock(GitRepoCache.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        target = mock(DeployTarget.class);
        File cache = Files.createDirectories(tmp.resolve("cache")).toFile();
        wt = Files.createDirectories(tmp.resolve("wt")).toFile();
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM scratch\nEXPOSE 8080\n");
        when(repos.fetchOnly(any(), eq(BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "cachehead"));
        when(worktrees.createForDeploy(any(), anyString(), eq(BRANCH), eq(20L))).thenReturn(wt);
        when(target.deploy(any(), any())).thenAnswer(inv -> {
            DeployTarget.DeploySpec spec = inv.getArgument(0);
            return new DeployTarget.DeployResult("http://h:19000", "cid", 19000, spec.imageName(), "[target]\n");
        });
        service = new DeployService(props(), repos, worktrees, mock(ClaudeExecAdapter.class), target);
    }

    private static WorkerTaskResponse task() {
        return new WorkerTaskResponse(20L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, BRANCH,
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null);
    }

    private DeployTarget.DeploySpec deployedSpec() throws Exception {
        ArgumentCaptor<DeployTarget.DeploySpec> spec = ArgumentCaptor.forClass(DeployTarget.DeploySpec.class);
        verify(target).deploy(spec.capture(), any());
        return spec.getValue();
    }

    @Test
    void 배포_worktree의_HEAD로_이미지_태그를_달고_빌드_커밋을_돌려준다() throws Exception {
        git(wt, "init", "-b", "main");
        git(wt, "add", "-A");
        git(wt, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "사람이 MR에 올린 커밋");
        String head = git(wt, "rev-parse", "HEAD");

        DeployTarget.DeployResult result = service.deploy(task(), line -> { });

        assertThat(head).hasSize(40);
        assertThat(deployedSpec().imageName()).isEqualTo("netis-task-20:" + head.substring(0, 7));
        // 레포 Dockerfile이 묶음 이름에 쓰는 커밋도 이미지 태그와 같은 실제 빌드 커밋이어야 한다(PR #124 NETIS_GIT_SHA)
        assertThat(deployedSpec().buildArgs()).containsExactly(java.util.Map.entry("NETIS_GIT_SHA", head));
        assertThat(result.builtSha()).isEqualTo(head);
        assertThat(result.log()).contains("[빌드 커밋] " + head.substring(0, 7));
    }

    @Test
    void HEAD를_못_읽으면_기록된_head_sha로_태그를_달고_빌드_커밋은_없다() throws Exception {
        // wt는 git 작업 폴더가 아니다 — rev-parse 실패

        DeployTarget.DeployResult result = service.deploy(task(), line -> { });

        assertThat(deployedSpec().imageName()).isEqualTo("netis-task-20:abc1234");
        assertThat(deployedSpec().buildArgs()).containsExactly(java.util.Map.entry("NETIS_GIT_SHA", task().headSha()));
        assertThat(result.builtSha()).isNull();
        assertThat(result.log()).contains("[빌드 커밋] 확인 못 함");
    }

    // ---- 워커 메인 루프: 배포완료 보고 ----

    private WorkerResultRequest reportedDeploy(DeployTarget.DeployResult result) throws Exception {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        DeployService deploy = mock(DeployService.class);
        ResultReporter reporter = mock(ResultReporter.class);
        when(http.nextTask()).thenReturn(Optional.of(task()));
        when(deploy.deploy(any(), any())).thenReturn(result);
        new WorkerMainLoop(props(), http, mock(GitRepoCache.class), mock(ClaudeExecAdapter.class),
                mock(PromptResultParser.class), mock(WorkerMcpSupport.class), mock(WorktreeService.class),
                mock(GitOpsService.class), deploy, reporter, mock(SilentLossTracker.class),
                mock(DesignResultHarvester.class), mock(TaskCloser.class), new SuperpowersPlugin(""),
                mock(ClaudeAuthProbe.class), mock(BaseSyncMerger.class)).pollAndProcess();
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(20L), cap.capture());
        return cap.getValue();
    }

    @Test
    void 배포완료_보고에_빌드한_커밋을_싣는다() throws Exception {
        String built = "f".repeat(40);

        WorkerResultRequest r = reportedDeploy(new DeployTarget.DeployResult("http://h:19000", "cid", 19000,
                "netis-task-20:fffffff", "log", built));

        assertThat(r.status()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(r.headSha()).isEqualTo(built);
        assertThat(r.deployImage()).isEqualTo("netis-task-20:fffffff");
        assertThat(r.fixAttempt()).isNull();
        assertThat(r.implementationAttempt()).isNull();
    }

    @Test
    void 빌드_커밋을_모르면_배포완료_보고의_headSha는_비운다() throws Exception {
        WorkerResultRequest r = reportedDeploy(new DeployTarget.DeployResult("http://h:19000", "cid", 19000,
                "netis-task-20:abc1234", "log"));

        assertThat(r.status()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(r.headSha()).isNull();
    }
}
