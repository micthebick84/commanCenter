package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceRepoCatalogTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskDesignRepository designRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private RepoCatalogService repoCatalogService;
    private TaskService service;

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
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
    }

    @Test
    void create_resolves_catalog_and_snapshots_repo_fields() {
        when(repoCatalogService.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github",
                        "micthebick84/netis7.0", null));
        TaskCreateRequest req = new TaskCreateRequest(7L, "main", "제목", "설명");

        Task t = service.create(req, "user1");

        assertThat(t.getGithubRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(t.getGitUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
        assertThat(t.getRepoAlias()).isEqualTo("Netis7.0");
        assertThat(t.getRepoCatalogId()).isEqualTo(7L);
    }

    @Test
    void create_propagates_resolve_rejection() {
        when(repoCatalogService.resolveForRegistration(8L))
                .thenThrow(new TaskException(org.springframework.http.HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: X"));
        TaskCreateRequest req = new TaskCreateRequest(8L, "main", "제목", "설명");

        assertThatThrownBy(() -> service.create(req, "user1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("비활성");
    }
}
