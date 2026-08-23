package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 배포 런타임 정합 잡 단위 테스트 — 관측→보고 판정 로직.
 * docker/HTTP 실호출 없이 mock/fake로 검증한다.
 */
class DeployReconcileJobTest {

    private WorkerHttpClient http;
    private Map<String, DeployTarget.DeployStatus> containerStates;
    private DeployReconcileJob job;

    private WorkerProperties props(boolean reconcileEnabled) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, reconcileEnabled, 0, null);
        return new WorkerProperties("mac-worker-1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, deploy);
    }

    private DeployTarget fakeTarget() {
        return new DeployTarget() {
            @Override
            public DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) {
                throw new UnsupportedOperationException();
            }
            @Override
            public void stop(String containerName) { }
            @Override
            public DeployStatus status(String containerName) {
                return containerStates.getOrDefault(containerName, DeployStatus.UNKNOWN);
            }
        };
    }

    @BeforeEach
    void setUp() {
        http = mock(WorkerHttpClient.class);
        containerStates = new java.util.HashMap<>();
        job = new DeployReconcileJob(http, fakeTarget(), props(true));
    }

    @Test
    void deployed_but_stopped_container_reports_lost() {
        when(http.deployedTasks()).thenReturn(List.of(new DeployedTaskSummary(13L, TaskStatus.DEPLOYED)));
        containerStates.put("netis-task-13", DeployTarget.DeployStatus.STOPPED);

        job.reconcile();

        ArgumentCaptor<WorkerRuntimeStatusRequest> req = ArgumentCaptor.forClass(WorkerRuntimeStatusRequest.class);
        verify(http).postRuntimeStatus(eq(13L), req.capture());
        assertThat(req.getValue().running()).isFalse();
        assertThat(req.getValue().workerId()).isEqualTo("mac-worker-1");
    }

    @Test
    void lost_but_running_container_reports_recovered() {
        when(http.deployedTasks()).thenReturn(List.of(new DeployedTaskSummary(13L, TaskStatus.DEPLOY_LOST)));
        containerStates.put("netis-task-13", DeployTarget.DeployStatus.RUNNING);

        job.reconcile();

        ArgumentCaptor<WorkerRuntimeStatusRequest> req = ArgumentCaptor.forClass(WorkerRuntimeStatusRequest.class);
        verify(http).postRuntimeStatus(eq(13L), req.capture());
        assertThat(req.getValue().running()).isTrue();
    }

    @Test
    void matching_states_report_nothing() {
        when(http.deployedTasks()).thenReturn(List.of(
                new DeployedTaskSummary(13L, TaskStatus.DEPLOYED),
                new DeployedTaskSummary(9L, TaskStatus.DEPLOY_LOST)));
        containerStates.put("netis-task-13", DeployTarget.DeployStatus.RUNNING);
        containerStates.put("netis-task-9", DeployTarget.DeployStatus.STOPPED);

        job.reconcile();

        verify(http, never()).postRuntimeStatus(any(), any());
    }

    @Test
    void inflight_deploy_statuses_are_ignored() {
        // 목록엔 GC 보호용 in-flight 상태도 오지만 대사는 DEPLOYED/DEPLOY_LOST만
        when(http.deployedTasks()).thenReturn(List.of(
                new DeployedTaskSummary(5L, TaskStatus.DEPLOY_PENDING),
                new DeployedTaskSummary(6L, TaskStatus.DEPLOYING)));
        containerStates.put("netis-task-5", DeployTarget.DeployStatus.STOPPED);
        containerStates.put("netis-task-6", DeployTarget.DeployStatus.STOPPED);

        job.reconcile();

        verify(http, never()).postRuntimeStatus(any(), any());
    }

    @Test
    void unknown_observation_is_skipped() {
        // docker 데몬 접근 불가 등 관측 불가 — 오탐 전이 방지 위해 보고하지 않음
        when(http.deployedTasks()).thenReturn(List.of(new DeployedTaskSummary(13L, TaskStatus.DEPLOYED)));
        containerStates.put("netis-task-13", DeployTarget.DeployStatus.UNKNOWN);

        job.reconcile();

        verify(http, never()).postRuntimeStatus(any(), any());
    }

    @Test
    void fetch_failure_skips_cycle_quietly() {
        when(http.deployedTasks()).thenThrow(new RestClientException("api down"));

        job.reconcile(); // 예외 전파 없이 스킵

        verify(http, never()).postRuntimeStatus(any(), any());
    }

    @Test
    void report_failure_does_not_abort_remaining_tasks() {
        when(http.deployedTasks()).thenReturn(List.of(
                new DeployedTaskSummary(13L, TaskStatus.DEPLOYED),
                new DeployedTaskSummary(9L, TaskStatus.DEPLOYED)));
        containerStates.put("netis-task-13", DeployTarget.DeployStatus.STOPPED);
        containerStates.put("netis-task-9", DeployTarget.DeployStatus.STOPPED);
        doThrow(new RestClientException("boom")).when(http).postRuntimeStatus(eq(13L), any());

        job.reconcile();

        verify(http).postRuntimeStatus(eq(9L), any()); // 13 실패해도 9는 보고됨
    }

    @Test
    void disabled_reconcile_is_noop() {
        job = new DeployReconcileJob(http, fakeTarget(), props(false));

        job.reconcile();

        verify(http, never()).deployedTasks();
    }
}
