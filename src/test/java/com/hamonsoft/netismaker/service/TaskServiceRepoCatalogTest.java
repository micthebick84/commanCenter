package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskServiceRepoCatalogTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskDesignRepository designRepo;
    private TaskStatusHistoryRepository historyRepo;
    private McpCatalogService mcpCatalogService;
    private RepoCatalogService repoCatalogService;
    private InterviewService interviewService;
    private TaskAttachmentRepository attachmentRepo;
    private AttachmentStorage attachmentStorage;
    private TaskStageUsageRepository stageUsageRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        designRepo = mock(TaskDesignRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        repoCatalogService = mock(RepoCatalogService.class);
        interviewService = mock(InterviewService.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        attachmentStorage = mock(AttachmentStorage.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        service = new TaskService(taskRepo, analysisRepo, designRepo, historyRepo, mcpCatalogService,
                repoCatalogService, new ObjectMapper(), interviewService, attachmentRepo, attachmentStorage,
                stageUsageRepo, mock(DbConnectionService.class),
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class), mock(org.springframework.context.ApplicationEventPublisher.class));
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

    // ── 참조 repo (스펙 2026-10-24 §4.1) ──────────────────────────────────

    private static final RefRepo REF11 = new RefRepo(11L, "옛앱", "grp/old-app",
            "https://gitlab.example/grp/old-app.git", "release/6.5", null, null, null);

    private void stubTarget7() {
        when(repoCatalogService.resolveForRegistration(7L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(7L, "Netis7.0",
                        "https://github.com/micthebick84/netis7.0.git", "github",
                        "micthebick84/netis7.0", null));
    }

    @Test
    void create_saves_reference_snapshots_resolved_against_the_target() {
        stubTarget7();
        List<RefRepoInput> refs = List.of(new RefRepoInput(11L, "release/6.5"));
        when(repoCatalogService.resolveReferences(eq(7L), any())).thenReturn(List.of(REF11));   // 불변 리스트

        Task t = service.create(new TaskCreateRequest(7L, "main", "제목", "설명", refs, null), "user1");

        verify(repoCatalogService).resolveReferences(7L, refs);   // 대상(카탈로그 7) 기준, 요청 그대로
        assertThat(t.getRefRepos()).containsExactly(REF11);
        // 엔티티가 들고 있는 목록은 가변이어야 한다 — 불변 결과를 그대로 넣지 않았다
        assertThatCode(() -> t.getRefRepos().add(REF11)).doesNotThrowAnyException();
    }

    @Test
    void create_without_references_stores_an_empty_list() {
        stubTarget7();

        Task t = service.create(new TaskCreateRequest(7L, "main", "제목", "설명"), "user1");

        assertThat(t.getRefRepos()).isNotNull().isEmpty();
    }

    @Test
    void create_reference_rejection_saves_no_task() {
        stubTarget7();
        when(repoCatalogService.resolveReferences(eq(7L), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "대상 레포는 참조로 고를 수 없습니다"));
        TaskCreateRequest req = new TaskCreateRequest(7L, "main", "제목", "설명",
                List.of(new RefRepoInput(7L, "main")), null);

        assertThatThrownBy(() -> service.create(req, "user1"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("대상 레포는 참조로 고를 수 없습니다");
                });
        verify(taskRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }
}
