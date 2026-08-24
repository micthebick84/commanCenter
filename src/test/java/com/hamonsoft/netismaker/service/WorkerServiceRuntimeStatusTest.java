package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 배포 런타임 정합 보고(recordRuntimeStatus) 전이 테스트.
 *
 *  배포완료   + running=false → 배포중단됨 (히스토리 source=system)
 *  배포중단됨 + running=true  → 배포완료 복귀
 *  그 외 조합/상태            → no-op (재배포·중지 경합 흡수)
 */
class WorkerServiceRuntimeStatusTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 13L);
        t.setStatus(status);
        t.setDeployUrl("https://task-13.micthebick.dev");
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        TaskDesignRepository designRepo = mock(TaskDesignRepository.class);
        RepoCatalogRepository repoCatalogRepo = mock(RepoCatalogRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        WorkerHeartbeatRepository heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, designRepo, repoCatalogRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void deployed_and_not_running_transitions_to_deploy_lost_with_system_history() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(t));

        service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", false, "컨테이너 비실행"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
        assertThat(t.getDeployUrl()).isEqualTo("https://task-13.micthebick.dev"); // 배포 메타 보존
        ArgumentCaptor<TaskStatusHistory> h = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(h.capture());
        assertThat(h.getValue().getActorType()).isEqualTo("system");
        assertThat(h.getValue().getToStatus()).isEqualTo(TaskStatus.DEPLOY_LOST.dbValue());
    }

    @Test
    void deploy_lost_and_running_recovers_to_deployed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(t));

        service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", true, "실행 확인"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        verify(historyRepo).save(any());
    }

    @Test
    void deployed_and_running_is_noop() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(t));

        service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", true, null));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void report_racing_with_redeploy_is_noop() {
        // 관측 시점엔 배포완료였지만 보고 도착 전에 관리자가 재배포를 시작한 경우
        Task t = taskWithStatus(TaskStatus.DEPLOYING);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(t));

        service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", false, "컨테이너 비실행"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING); // 덮어쓰지 않음
        verify(historyRepo, never()).save(any());
    }

    @Test
    void repeated_lost_report_is_idempotent() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(t));

        service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", false, "컨테이너 비실행"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
        verify(historyRepo, never()).save(any()); // 중복 히스토리 없음
    }

    @Test
    void report_for_deleted_task_is_rejected() {
        // 관측 목록 수신 후 삭제된 task의 지각 보고 — 삭제 row를 변이시키지 않는다
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.recordRuntimeStatus(13L, new WorkerRuntimeStatusRequest("mac-worker-1", false, "비실행")))
                .isInstanceOf(TaskException.class);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void list_deploy_reconcilable_maps_id_and_status() {
        Task deployed = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findDeployReconcilable()).thenReturn(List.of(deployed));

        var out = service.listDeployReconcilable();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(13L);
        assertThat(out.get(0).status()).isEqualTo(TaskStatus.DEPLOYED);
    }
}
