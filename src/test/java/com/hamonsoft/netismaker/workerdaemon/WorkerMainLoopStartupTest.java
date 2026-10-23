package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
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
        return new WorkerMainLoop(props(), http, mock(GitRepoCache.class), mock(ClaudeExecAdapter.class),
                mock(PromptResultParser.class), mock(WorkerMcpSupport.class), mock(WorktreeService.class),
                mock(GitOpsService.class), mock(DeployService.class), mock(ResultReporter.class),
                tracker, mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""));
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
