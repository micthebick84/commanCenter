package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskRecovery.RecoveryCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 워커 기동 정합 — 그 워커가 쥔 in-flight 작업을 회수하되 대기 목록(pending)에 있는 작업은 건너뛴다.
 */
class WorkerStartupServiceTest {

    private TaskRepository taskRepo;
    private TaskRecovery recovery;
    private WorkerStartupService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        recovery = mock(TaskRecovery.class);
        service = new WorkerStartupService(taskRepo, recovery);
    }

    private Task task(long id, TaskStatus status) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(5));
        return t;
    }

    @Test
    void recovers_all_inflight_of_the_worker() {
        Task a = task(1L, TaskStatus.IMPLEMENTING);
        Task b = task(2L, TaskStatus.DESIGNING);
        when(taskRepo.findInFlightClaimedByWorkerForUpdate("w1")).thenReturn(List.of(a, b));
        when(recovery.recover(eq(a), any())).thenReturn(TaskStatus.APPROVED);
        when(recovery.recover(eq(b), any())).thenReturn(TaskStatus.DESIGN_PENDING);

        WorkerStartupResponse res = service.reconcile("w1", Set.of());

        assertThat(res.recovered()).hasSize(2);
        assertThat(res.recovered().get(0).taskId()).isEqualTo(1L);
        assertThat(res.recovered().get(0).from()).isEqualTo(TaskStatus.IMPLEMENTING);
        assertThat(res.recovered().get(0).to()).isEqualTo(TaskStatus.APPROVED);
        assertThat(res.recovered().get(1).taskId()).isEqualTo(2L);
        assertThat(res.recovered().get(1).from()).isEqualTo(TaskStatus.DESIGNING);
        assertThat(res.recovered().get(1).to()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(res.skipped()).isEmpty();
        verify(recovery, times(2)).recover(any(), eq(RecoveryCause.workerRestart("w1")));
    }

    @Test
    void pending_task_is_skipped() {
        Task a = task(7L, TaskStatus.IMPLEMENTING);
        when(taskRepo.findInFlightClaimedByWorkerForUpdate("w1")).thenReturn(List.of(a));

        WorkerStartupResponse res = service.reconcile("w1", List.of(7L));

        assertThat(res.recovered()).isEmpty();
        assertThat(res.skipped()).containsExactly(7L);
        assertThat(a.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
        assertThat(a.getWorkerId()).isEqualTo("w1");
        verifyNoInteractions(recovery);
    }

    @Test
    void no_inflight_returns_empty() {
        when(taskRepo.findInFlightClaimedByWorkerForUpdate("w1")).thenReturn(List.of());

        WorkerStartupResponse res = service.reconcile("w1", null);

        assertThat(res.recovered()).isEmpty();
        assertThat(res.skipped()).isEmpty();
        verifyNoInteractions(recovery);
    }
}
