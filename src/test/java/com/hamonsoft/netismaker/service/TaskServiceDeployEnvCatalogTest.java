package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Choice;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.DbSlotView;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskServiceDeployEnvCatalogTest {

    private TaskRepository taskRepo;
    private RepoCatalogRepository catalogRepo;
    private DbConnectionService dbService;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        catalogRepo = mock(RepoCatalogRepository.class);
        dbService = mock(DbConnectionService.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(TaskStatusHistoryRepository.class), mock(McpCatalogService.class),
                mock(RepoCatalogService.class), new ObjectMapper(), mock(InterviewService.class),
                mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), dbService,
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        ReflectionTestUtils.invokeMethod(service, "setRepoCatalogRepository", catalogRepo);

        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("JDBC_URL", "url");
        entries.put("JDBC_USER", "user");
        DeployConfig cfg = new DeployConfig(
                List.of(new DbSlot("main", "주 DB", List.of(DbType.POSTGRESQL), new Target(TargetKind.ENV, null, entries))),
                List.of());
        RepoCatalogEntry catalog = mock(RepoCatalogEntry.class);
        when(catalog.getDeployConfig()).thenReturn(cfg);
        when(catalog.getId()).thenReturn(5L);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(catalog));
    }

    private static Task task(long id) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", id);
        t.setRepoCatalogId(5L);
        return t;
    }

    private static Choice choice(long id) {
        return new Choice(id, "c" + id, "POSTGRESQL", "REPO", "db.local", 5432, "app");
    }

    @SuppressWarnings("unchecked")
    private void choices(Long... ids) {
        List<Choice> list = new ArrayList<>();
        for (Long id : ids) list.add(choice(id));
        when(dbService.deployChoices(eq(5L), eq("admin"), any(Set.class), any())).thenReturn(list);
    }

    private void previous(long id, Long conn) {
        Task p = task(id);
        p.setDeployDbSelection(new ArrayList<>(List.of(new DeployDbChoice("main", conn))));
        when(taskRepo.findRecentlyDeployedWithInput("owner/repo", 42L, TaskStatus.DEPLOY_PENDING.dbValue()))
                .thenReturn(List.of(p));
    }

    @Test
    void 고르지_않음을_저장한_작업은_직전배포_DB를_고르지_않는다() {
        Task t = task(42L);
        t.setDeployDbSelection(new ArrayList<>(List.of(new DeployDbChoice("main", null))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        choices(43L);
        previous(41L, 43L);

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L, "admin");

        DbSlotView v = r.databases().get(0);
        assertThat(v.selected().connectionId()).isNull();
        assertThat(v.selected().from()).isEqualTo(Source.SAVED);
        assertThat(v.targetSummary()).isEqualTo("env JDBC_URL, JDBC_USER");
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void 선택이_없으면_직전배포_DB를_PREVIOUS로() {
        Task t = task(42L);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        choices(43L);
        previous(41L, 43L);

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L, "admin");

        DbSlotView v = r.databases().get(0);
        assertThat(v.selected().connectionId()).isEqualTo(43L);
        assertThat(v.selected().from()).isEqualTo(Source.PREVIOUS);
        assertThat(r.previousTaskId()).isEqualTo(41L);
    }

    @Test
    void 직전배포_연결을_현재_관리자가_못고르면_비운다() {
        Task t = task(42L);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        choices(50L);
        previous(41L, 43L);

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L, "admin");

        assertThat(r.databases().get(0).selected()).isNull();
        assertThat(r.databases().get(0).choices()).extracting(Choice::id).containsExactly(50L);
        assertThat(r.previousTaskId()).isNull();
    }
}
