package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hamonsoft.netismaker.entity.EnvVar;
import java.util.ArrayList;
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
    private TaskDesignRepository designRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private RepoCatalogService repoCatalogService;
    private TaskService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        designRepo = mock(TaskDesignRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        repoCatalogService = mock(RepoCatalogService.class);
        service = new TaskService(taskRepo, analysisRepo, designRepo, historyRepo, mcpCatalogService,
                repoCatalogService, new ObjectMapper());
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void deploy_from_pr_created_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        Task result = service.deploy(42L, "admin", null);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(result.getWorkerId()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void deploy_from_wrong_status_throws() {
        Task t = taskWithStatus(TaskStatus.COMPLETED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.deploy(42L, "admin", null))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("PR생성");
    }

    @Test
    void redeploy_from_deployed_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.redeploy(42L, "admin", null).getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void redeploy_from_deploy_failed_is_allowed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_FAILED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.redeploy(42L, "admin", null).getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void redeploy_from_deploy_lost_is_allowed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.redeploy(42L, "admin", null).getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void undeploy_from_deploy_lost_is_allowed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThat(service.undeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.UNDEPLOY_PENDING);
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

    @Test
    void soft_delete_of_deployed_task_is_rejected() {
        // 삭제하면 reconcile/GC 보호에서 빠져 컨테이너·공개 URL이 고아가 됨 — 중지 먼저
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.softDelete(42L, "admin", true))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("중지");
        assertThat(t.getDeletedAt()).isNull();
    }

    @Test
    void soft_delete_of_deploy_lost_task_is_rejected() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.softDelete(42L, "admin", true))
                .isInstanceOf(TaskException.class);
        assertThat(t.getDeletedAt()).isNull();
    }

    @Test
    void soft_delete_of_pr_created_task_is_allowed() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        service.softDelete(42L, "admin", true);
        assertThat(t.getDeletedAt()).isNotNull();
    }

    @Test
    void deploy_persists_env_vars() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        List<EnvVar> env = List.of(new EnvVar("JWT_SECRET", "s3cr3t", true));
        Task result = service.deploy(42L, "admin", env);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(result.getEnvVars()).hasSize(1);
        assertThat(result.getEnvVars().get(0).key()).isEqualTo("JWT_SECRET");
    }

    @Test
    void deploy_with_null_env_keeps_existing() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("A", "1", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        Task result = service.deploy(42L, "admin", null);
        assertThat(result.getEnvVars()).hasSize(1);
        assertThat(result.getEnvVars().get(0).key()).isEqualTo("A");
    }
}
