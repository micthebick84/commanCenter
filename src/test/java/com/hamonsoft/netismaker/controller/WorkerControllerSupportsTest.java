package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.service.WorkerCapabilities;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** next-task supports — 워커가 아는 기능 토큰(refRepos·fixRounds)을 읽어 claim에 넘긴다. */
class WorkerControllerSupportsTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final WorkerController controller = new WorkerController(workerService, mock(DbMcpGrantService.class), mock(DbWriteService.class));

    @Test
    void supports_토큰_파싱() {
        assertThat(WorkerController.supports("refRepos,fixRounds", "refRepos")).isTrue();
        assertThat(WorkerController.supports("refRepos,fixRounds", "fixRounds")).isTrue();
        assertThat(WorkerController.supports("refRepos", "fixRounds")).isFalse();
        assertThat(WorkerController.supports("fixRounds", "refRepos")).isFalse();
        assertThat(WorkerController.supports(null, "refRepos")).isFalse();
        assertThat(WorkerController.supports(null, "fixRounds")).isFalse();
    }

    @Test
    void 쉼표_분리_공백_제거_정확히_일치() {
        assertThat(WorkerController.supports(" refRepos , fixRounds ", "fixRounds")).isTrue();
        assertThat(WorkerController.supports("fixRounds,", "fixRounds")).isTrue();
        assertThat(WorkerController.supports("", "fixRounds")).isFalse();
        // 부분 문자열·대소문자 다른 토큰은 인정하지 않는다 — 구버전 워커를 새 워커로 잘못 보면 회차를 내주게 된다
        assertThat(WorkerController.supports("fixRoundsX", "fixRounds")).isFalse();
        assertThat(WorkerController.supports("notfixRounds", "fixRounds")).isFalse();
        assertThat(WorkerController.supports("fixrounds", "fixRounds")).isFalse();
        assertThat(WorkerController.supports("fix Rounds", "fixRounds")).isFalse();
    }

    @Test
    void supportsRefRepos는_같은_함수로_위임한다() {
        assertThat(WorkerController.SUPPORTS_FIX_ROUNDS).isEqualTo("fixRounds");
        assertThat(WorkerController.supportsRefRepos("refRepos,fixRounds")).isTrue();
        assertThat(WorkerController.supportsRefRepos("fixRounds")).isFalse();
        assertThat(WorkerController.supportsRefRepos(null)).isFalse();
    }

    @Test
    void supports를_능력으로_바꿔_넘긴다() {
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        assertThat(controller.nextTask("w1", "refRepos,fixRounds", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        controller.nextTask("w2", "refRepos", null);
        controller.nextTask("w3", null, null);

        verify(workerService).claimNextTask("w1", new WorkerCapabilities(true, true), null);
        verify(workerService).claimNextTask("w2", new WorkerCapabilities(true, false), null);
        verify(workerService).claimNextTask("w3", new WorkerCapabilities(false, false), null);
    }

    @Test
    void supports에_baseSync가_있으면_다섯째_true_없으면_false() {
        assertThat(WorkerController.SUPPORTS_BASE_SYNC).isEqualTo("baseSync");
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("new", "refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch,deployOverrides", null);
        controller.nextTask("old", "refRepos,fixRounds,dbWrite,mockups", null);
        controller.nextTask("none", null, null);

        verify(workerService).claimNextTask("new", WorkerCapabilities.ALL, null);
        verify(workerService).claimNextTask("new", new WorkerCapabilities(true, true, true, true, true, true, true), null);
        verify(workerService).claimNextTask("old", new WorkerCapabilities(true, true, true, true, false, false), null);
        verify(workerService).claimNextTask("none", new WorkerCapabilities(false, false, false, false, false, false), null);
    }

    @Test
    void supports에_closeKeepBranch가_있으면_여섯째_true_baseSync까지만_알면_false() {
        assertThat(WorkerController.SUPPORTS_CLOSE_KEEP_BRANCH).isEqualTo("closeKeepBranch");
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("prev", "refRepos,fixRounds,dbWrite,mockups,baseSync", null);

        verify(workerService).claimNextTask("prev", new WorkerCapabilities(true, true, true, true, true, false), null);
    }

    @Test
    void 종류와_함께_와도_능력을_같이_넘긴다() {
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("remote-1", "refRepos,fixRounds", "IMPLEMENTATION,DESIGN");

        verify(workerService).claimNextTask("remote-1", new WorkerCapabilities(true, true),
                EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN));
    }

    @Test
    void supports에_deployOverrides가_있으면_일곱째_true() {
        assertThat(WorkerController.SUPPORTS_DEPLOY_OVERRIDES).isEqualTo("deployOverrides");
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("prev", "refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch", null);
        controller.nextTask("new", "refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch,deployOverrides", null);

        verify(workerService).claimNextTask("prev", new WorkerCapabilities(true, true, true, true, true, true, false), null);
        verify(workerService).claimNextTask("new", WorkerCapabilities.ALL, null);
    }

    @Test
    void DEPLOY_claim에만_덮어쓰기를_싣는다() {
        var service = mock(com.hamonsoft.netismaker.service.deployconfig.DeployOverrideService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "deployOverrideService", service);
        var overrides = com.hamonsoft.netismaker.dto.DeployOverrides.failed("x");
        when(service.forClaim(7L)).thenReturn(overrides);
        var task = com.hamonsoft.netismaker.entity.Task.create("a/b", "main", "T", "d", "u", 3, java.util.List.of(), "m", "high");
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", 7L);
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any()))
                .thenReturn(Optional.of(WorkerTaskResponse.forDeploy(task)), Optional.of(WorkerTaskResponse.forClose(task, null)));

        assertThat(controller.nextTask("w", "deployOverrides", null).getBody().deployOverrides()).isEqualTo(overrides);
        assertThat(controller.nextTask("w", "deployOverrides", null).getBody().deployOverrides()).isNull();
    }

    @Test
    void forClaim이_던지면_failed_덮어쓰기를_싣는다() {
        var service = mock(com.hamonsoft.netismaker.service.deployconfig.DeployOverrideService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "deployOverrideService", service);
        when(service.forClaim(7L)).thenThrow(new IllegalStateException("boom"));
        var task = com.hamonsoft.netismaker.entity.Task.create("a/b", "main", "T", "d", "u", 3, java.util.List.of(), "m", "high");
        org.springframework.test.util.ReflectionTestUtils.setField(task, "id", 7L);
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any()))
                .thenReturn(Optional.of(WorkerTaskResponse.forDeploy(task)));

        var o = controller.nextTask("w", "deployOverrides", null).getBody().deployOverrides();

        assertThat(o.error()).isEqualTo("배포 덮어쓰기 준비 실패: IllegalStateException");
    }
}
