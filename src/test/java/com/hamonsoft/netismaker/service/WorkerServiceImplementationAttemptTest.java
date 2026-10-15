package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 재시도 뒤 이전 시도의 늦은 보고는 상태를 바꾸지 않는다 (스펙 2026-10-15 §5.6). */
class WorkerServiceImplementationAttemptTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskStageUsageRepository stageUsageRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, stageUsageRepo,
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(TaskStatus status, int attempt) {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setImplementationAttempt(attempt);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        return t;
    }

    private static WorkerResultRequest prCreated(Integer attempt) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/9", 9, "netismaker/task-7-t", "abc1234", "log",
                null, null, null, null, null,
                null, null, null, null,
                new WorkerResultRequest.UsageReport(new BigDecimal("1.50"), 100L, 50L, 0L, 0L))
                .withImplementationAttempt(attempt);
    }

    private static WorkerResultRequest implementationFailed(Integer attempt) {
        return new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1",
                null, null, "netismaker/task-7-t", null, null,
                null, null, null, null, null,
                null, null, null, null, null)
                .withImplementationAttempt(attempt);
    }

    private String lastHistoryReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void a_late_report_from_an_earlier_attempt_only_leaves_history_and_usage() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(1));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
        assertThat(t.getPrUrl()).isNull();
        assertThat(lastHistoryReason()).isEqualTo("이전 시도(1회째)의 늦은 보고 무시: PR생성");
        verify(stageUsageRepo).accumulate(eq(7L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void a_late_failure_after_the_retry_was_queued_is_accepted_quietly() {
        Task t = task(TaskStatus.APPROVED, 2);

        service.recordResult(7L, implementationFailed(1));   // 예전에는 409 → dead-letter

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    void stale_reconciliation_only_happens_for_the_same_attempt() {
        Task t = task(TaskStatus.IMPLEMENTATION_FAILED, 2);

        service.recordResult(7L, prCreated(1));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getPrUrl()).isNull();
    }

    @Test
    void the_current_attempt_is_recorded_as_before() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(2));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/acme/widgets/pull/9");
    }

    @Test
    void an_old_worker_without_an_attempt_keeps_the_existing_behavior() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(null));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
    }
}
