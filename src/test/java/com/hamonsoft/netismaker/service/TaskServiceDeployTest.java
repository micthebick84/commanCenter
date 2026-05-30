package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 배포 전이 단위 테스트 — deploy/redeploy/undeploy 상태 가드.
 */
class TaskServiceDeployTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private TaskService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        service = new TaskService(taskRepo, analysisRepo, historyRepo, mcpCatalogService);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void deploy_from_pr_created_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        Task result = service.deploy(42L, "admin");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(result.getWorkerId()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void deploy_from_wrong_status_throws() {
        Task t = taskWithStatus(TaskStatus.COMPLETED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.deploy(42L, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("PR생성");
    }

    @Test
    void redeploy_from_deployed_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.redeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void redeploy_from_deploy_failed_is_allowed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_FAILED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.redeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void undeploy_from_deployed_moves_to_undeploy_pending() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.undeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.UNDEPLOY_PENDING);
    }

    @Test
    void undeploy_from_pr_created_throws() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.undeploy(42L, "admin"))
                .isInstanceOf(TaskException.class);
    }
}
