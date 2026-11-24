package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 워커 기동 알림 — 첫 claim 전에 성공(또는 404)해야 nextTask를 부른다 (스펙 2026-10-23 §3.5). */
class WorkerMainLoopStartupTest {

    private WorkerHttpClient http;
    private SilentLossTracker tracker;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    @BeforeEach
    void setUp() {
        http = mock(WorkerHttpClient.class);
        tracker = mock(SilentLossTracker.class);
        when(http.nextTask()).thenReturn(Optional.empty());
    }

    private WorkerMainLoop loop() {
        return loop(mock(DeployService.class));
    }

    private WorkerMainLoop loop(DeployService deploy) {
        return new WorkerMainLoop(props(), http, mock(GitRepoCache.class), mock(ClaudeExecAdapter.class),
                mock(PromptResultParser.class), mock(WorkerMcpSupport.class), mock(WorktreeService.class),
                mock(GitOpsService.class), deploy, mock(ResultReporter.class),
                tracker, mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""), mock(ClaudeAuthProbe.class), mock(BaseSyncMerger.class));
    }

    private static SilentLossTracker.PendingEntry pending(long taskId) {
        return new SilentLossTracker.PendingEntry(taskId, mock(WorkerResultRequest.class), 0, "raw" + taskId);
    }

    @Test
    void does_not_poll_until_startup_succeeds() {
        when(http.startup(any()))
                .thenThrow(new ResourceAccessException("down"))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn(Optional.of(new WorkerStartupResponse(List.of(), List.of())));
        WorkerMainLoop loop = loop();

        loop.pollAndProcess();
        loop.pollAndProcess();
        verify(http, never()).nextTask();

        loop.pollAndProcess();
        verify(http, times(3)).startup(any());
        verify(http, times(1)).nextTask();
    }

    @Test
    void proceeds_when_api_does_not_know_startup() {
        when(http.startup(any())).thenReturn(Optional.empty());

        loop().pollAndProcess();

        verify(http, times(1)).nextTask();
    }

    @Test
    void cleans_leftover_deploy_overrides_once_after_startup_with_active_task_ids() {
        when(http.startup(any())).thenReturn(Optional.of(new WorkerStartupResponse(List.of(), List.of())));
        when(http.activeWorktreeTasks()).thenReturn(List.of(new ActiveWorktreeTaskSummary(60L, TaskStatus.DEPLOYING)));
        DeployService deploy = mock(DeployService.class);
        WorkerMainLoop loop = loop(deploy);

        loop.pollAndProcess();
        loop.pollAndProcess();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.function.Supplier<java.util.Set<Long>>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(deploy, times(1)).cleanStaleOverrides(ids.capture());
        org.assertj.core.api.Assertions.assertThat(ids.getValue().get()).containsExactly(60L);
    }

    @Test
    void leftover_cleanup_failure_does_not_block_polling_and_retries_until_it_succeeds() {
        when(http.startup(any())).thenReturn(Optional.of(new WorkerStartupResponse(List.of(), List.of())));
        DeployService deploy = mock(DeployService.class);
        when(deploy.cleanStaleOverrides(any()))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn(0);
        WorkerMainLoop loop = loop(deploy);

        loop.pollAndProcess();   // 실패 — claim은 계속
        verify(http, times(1)).nextTask();
        loop.pollAndProcess();   // 다시 시도해 성공
        loop.pollAndProcess();   // 성공 뒤에는 다시 하지 않는다

        verify(deploy, times(2)).cleanStaleOverrides(any());
        verify(http, times(3)).nextTask();
    }

    @Test
    void leftover_cleanup_waits_for_startup_report() {
        when(http.startup(any()))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn(Optional.of(new WorkerStartupResponse(List.of(), List.of())));
        DeployService deploy = mock(DeployService.class);
        WorkerMainLoop loop = loop(deploy);

        loop.pollAndProcess();
        verify(deploy, never()).cleanStaleOverrides(any());   // 기동 정합 전에는 이 워커의 배포중이 아직 보호 목록에 있다

        loop.pollAndProcess();
        org.mockito.InOrder order = inOrder(http, deploy);
        order.verify(http, times(2)).startup(any());
        order.verify(deploy).cleanStaleOverrides(any());
        order.verify(http).nextTask();
    }

    @Test
    void sends_pending_task_ids_once() {
        when(tracker.snapshotPending()).thenReturn(List.of(pending(5), pending(5), pending(9)));
        when(http.startup(any())).thenReturn(Optional.of(new WorkerStartupResponse(List.of(), List.of(5L, 9L))));
        WorkerMainLoop loop = loop();

        loop.pollAndProcess();
        loop.pollAndProcess();

        verify(http, times(1)).startup(List.of(5L, 9L));
        verify(http, times(2)).nextTask();
    }
}
