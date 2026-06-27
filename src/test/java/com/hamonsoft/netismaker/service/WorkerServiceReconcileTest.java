package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerServiceReconcileTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        WorkerHeartbeatRepository heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task failedTask() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setFailureReason("Stale 회수: 워커 mac-worker-1 응답 없음(heartbeat) (구현 중단)");
        return t;
    }

    private WorkerResultRequest prCreated(String prUrl, String headBranch) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, 123L, null,
                prUrl, 10, headBranch, "abcdef1", "impl log",
                null, null, null, null, null);
    }

    @Test
    void late_pr_created_on_stale_failed_task_reconciles_to_pr_created() {
        Task t = failedTask();
        when(taskRepo.findById(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, prCreated("https://github.com/o/r/pull/10", "netismaker/task-16"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/o/r/pull/10");
        assertThat(t.getHeadBranch()).isEqualTo("netismaker/task-16");
        assertThat(t.getFailureReason()).isNull();
        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED.dbValue());
        assertThat(cap.getValue().getToStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(cap.getValue().getActorType()).isEqualTo("worker");
        assertThat(cap.getValue().getActorId()).isEqualTo("mac-worker-1");
    }

    @Test
    void late_pr_created_missing_pr_meta_is_rejected_as_conflict() {
        Task t = failedTask();
        when(taskRepo.findById(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, prCreated(null, null)))
                .isInstanceOf(TaskException.class);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }
}
