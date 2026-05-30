package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stale 회수 잡 단위 테스트 — Mockito로 repository 격리.
 *
 *  3가지 분기:
 *    1. heartbeat 정상 + 5분 미만 → no-op (stale 없음)
 *    2. 5분 초과 + retry < max → PENDING 복귀 + retry++
 *    3. 5분 초과 + retry == max → FAILED 직접 전이
 */
class StaleTaskRecoveryJobTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private StaleTaskRecoveryJob job;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        job = new StaleTaskRecoveryJob(taskRepo, historyRepo);
        ReflectionTestUtils.setField(job, "staleThresholdMinutes", 5);
    }

    @Test
    void no_stale_tasks_does_nothing() {
        when(taskRepo.findStaleInProgress(any())).thenReturn(List.of());
        job.recoverStale();
        verifyNoInteractions(historyRepo);
    }

    @Test
    void stale_under_max_retry_returns_to_pending_and_increments() {
        Task t = Task.create("hamonsoft/repo", "main", "T1", "desc", "user1", 3, java.util.List.of());
        ReflectionTestUtils.setField(t, "id", 100L);
        t.setStatus(TaskStatus.IN_PROGRESS);
        t.setWorkerId("mac-worker-1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t.setRetryCount(0);

        when(taskRepo.findStaleInProgress(any())).thenReturn(List.of(t));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(1);
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        verify(historyRepo, times(1)).save(any());
    }

    @Test
    void stale_at_max_retry_transitions_to_failed() {
        Task t = Task.create("hamonsoft/repo", "main", "T2", "desc", "user1", 3, java.util.List.of());
        ReflectionTestUtils.setField(t, "id", 101L);
        t.setStatus(TaskStatus.IN_PROGRESS);
        t.setWorkerId("mac-worker-1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t.setRetryCount(3);  // 한도 도달

        when(taskRepo.findStaleInProgress(any())).thenReturn(List.of(t));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(t.getRetryCount()).isEqualTo(3);  // 더 증가 안 함
        assertThat(t.getFailureReason()).contains("한도");
        verify(historyRepo, times(1)).save(any());
    }

    @Test
    void multiple_stale_processed_independently() {
        Task t1 = Task.create("a/b", "main", "T1", "d", "user1", 3, java.util.List.of());
        ReflectionTestUtils.setField(t1, "id", 1L);
        t1.setStatus(TaskStatus.IN_PROGRESS);
        t1.setRetryCount(0);
        t1.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t1.setWorkerId("w1");

        Task t2 = Task.create("a/b", "main", "T2", "d", "user1", 3, java.util.List.of());
        ReflectionTestUtils.setField(t2, "id", 2L);
        t2.setStatus(TaskStatus.IN_PROGRESS);
        t2.setRetryCount(3);
        t2.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t2.setWorkerId("w1");

        when(taskRepo.findStaleInProgress(any())).thenReturn(List.of(t1, t2));

        job.recoverStale();

        assertThat(t1.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t2.getStatus()).isEqualTo(TaskStatus.FAILED);
    }
}
