package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewPlanRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** worktree 정리 잡 보호 목록(listWorktreeActive) 매핑 테스트. */
class WorkerServiceWorktreeActiveTest {

    @Test
    void maps_worktree_active_tasks_to_id_and_status() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        WorkerService service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class),
                mock(TaskDesignRepository.class), mock(RepoCatalogRepository.class),
                mock(TaskStatusHistoryRepository.class), mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 21L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        when(taskRepo.findWorktreeActive()).thenReturn(List.of(t));

        assertThat(service.listWorktreeActive())
                .containsExactly(new ActiveWorktreeTaskSummary(21L, TaskStatus.IMPLEMENTING));
    }
}
