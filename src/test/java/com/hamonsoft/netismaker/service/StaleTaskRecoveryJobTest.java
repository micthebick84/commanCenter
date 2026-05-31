package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stale 회수 잡 단위 테스트 — 하트비트 인식 + 절대 백스톱.
 */
class StaleTaskRecoveryJobTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerHeartbeatRepository heartbeatRepo;
    private StaleTaskRecoveryJob job;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        job = new StaleTaskRecoveryJob(taskRepo, historyRepo, heartbeatRepo);
        ReflectionTestUtils.setField(job, "analysisStaleThresholdMinutes", 5);
        ReflectionTestUtils.setField(job, "implementationStaleThresholdMinutes", 60);
        ReflectionTestUtils.setField(job, "deployStaleThresholdMinutes", 15);
        ReflectionTestUtils.setField(job, "workerDeadThresholdSeconds", 60);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(long id, TaskStatus status, String worker, int claimedMinAgo, int retry) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of());
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setWorkerId(worker);
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(claimedMinAgo));
        t.setRetryCount(retry);
        return t;
    }

    private WorkerHeartbeat hb(String id, int seenSecAgo) {
        WorkerHeartbeat h = new WorkerHeartbeat();
        h.setWorkerId(id);
        h.setLastSeenAt(OffsetDateTime.now().minusSeconds(seenSecAgo));
        return h;
    }

    @Test
    void none_inflight_does_nothing() {
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of());
        job.recoverStale();
        verifyNoInteractions(historyRepo);
    }

    @Test
    void alive_worker_within_ceiling_not_recovered() {
        Task t = task(1L, TaskStatus.DEPLOYING, "w1", 5, 0); // 5분 < 15분, 워커 살아있음
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5))); // 5초 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void dead_worker_deploying_to_deploy_failed() {
        Task t = task(2L, TaskStatus.DEPLOYING, "w1", 5, 0); // claimed 5분 전(>60s), heartbeat 끊김
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 300))); // 5분 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(t.getWorkerId()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void dead_worker_undeploying_requeues_to_undeploy_pending() {
        Task t = task(3L, TaskStatus.UNDEPLOYING, "w1", 5, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of()); // heartbeat 없음 = 사망
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.UNDEPLOY_PENDING);
        assertThat(t.getClaimedAt()).isNull();
    }

    @Test
    void freshly_claimed_missing_heartbeat_not_recovered() {
        Task t = task(4L, TaskStatus.IN_PROGRESS, "w1", 0, 0); // 방금 claim (0분 전)
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of()); // 아직 heartbeat 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS); // 유예로 보호
        verifyNoInteractions(historyRepo);
    }

    @Test
    void hung_backstop_alive_worker_implementing_to_failed() {
        Task t = task(5L, TaskStatus.IMPLEMENTING, "w1", 61, 0); // 61분 > 60분 ceiling
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5))); // 살아있어도 백스톱
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }

    @Test
    void dead_worker_in_progress_under_retry_returns_pending() {
        Task t = task(6L, TaskStatus.IN_PROGRESS, "w1", 6, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 300)));
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(1);
    }

    @Test
    void stale_at_max_retry_transitions_to_failed() {
        // retry==maxRetry(3) → FAILED, retry_count 변경 없음, failureReason에 "한도 초과" 포함
        Task t = task(7L, TaskStatus.IN_PROGRESS, "w1", 6, 3); // retryCount=3 == maxRetry=3
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 300))); // 사망 워커
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(t.getRetryCount()).isEqualTo(3); // 증가 없음
        assertThat(t.getFailureReason()).contains("한도 초과");
        verify(historyRepo).save(any());
    }
}
