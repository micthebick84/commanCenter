package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 배포·재배포 요청의 DB 선택 저장·검증 (스펙 2026-11-24 Task 7). */
class TaskServiceDeployDbSelectionTest {

    private TaskRepository taskRepo;
    private RepoCatalogRepository catalogRepo;
    private DbConnectionRepository connRepo;
    private TaskService service;
    private RepoCatalogEntry catalog;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        catalogRepo = mock(RepoCatalogRepository.class);
        connRepo = mock(DbConnectionRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class),
                mock(RepoCatalogService.class), new ObjectMapper(), mock(InterviewService.class),
                mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class),
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        ReflectionTestUtils.invokeMethod(service, "setRepoCatalogRepository", catalogRepo);
        ReflectionTestUtils.invokeMethod(service, "setDbConnectionRepository", connRepo);

        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("JDBC_URL", "url");
        DeployConfig cfg = new DeployConfig(
                List.of(new DbSlot("main", "주 DB", List.of(DbType.POSTGRESQL), new Target(TargetKind.ENV, null, entries))),
                List.of(new Variable("APP_MODE", null, false, false, null, null, null)));
        catalog = mock(RepoCatalogEntry.class);
        when(catalog.getDeployConfig()).thenReturn(cfg);
        when(catalog.getId()).thenReturn(5L);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(catalog));
    }

    private Task task() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setRepoCatalogId(5L);
        t.setStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    private DbConnection conn(long id, DbConnectionScope scope, Long catalogId, String owner, DbType type) {
        DbConnection c = DbConnection.create(scope, catalogId, owner, "c" + id, type, "h", 5432, "d", "u", "enc", "admin");
        ReflectionTestUtils.setField(c, "id", id);
        when(connRepo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void 선택을_저장한다() {
        Task t = task();
        conn(10L, DbConnectionScope.REPO, 5L, null, DbType.POSTGRESQL);
        service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L)));
        assertThat(t.getDeployDbSelection()).containsExactly(new DeployDbChoice("main", 10L));
    }

    @Test
    void 선택이_null이면_유지() {
        Task t = task();
        t.setDeployDbSelection(new ArrayList<>(List.of(new DeployDbChoice("main", 10L))));
        service.deploy(42L, "admin", null, null);
        assertThat(t.getDeployDbSelection()).containsExactly(new DeployDbChoice("main", 10L));
    }

    @Test
    void 고르지_않음을_저장한다() {
        Task t = task();
        t.setDeployDbSelection(new ArrayList<>(List.of(new DeployDbChoice("main", 10L))));
        service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", null)));
        assertThat(t.getDeployDbSelection()).containsExactly(new DeployDbChoice("main", null));
    }

    @Test
    void 재배포도_선택을_저장한다() {
        Task t = task();
        t.setStatus(TaskStatus.DEPLOYED);
        conn(10L, DbConnectionScope.REPO, 5L, null, DbType.POSTGRESQL);
        service.redeploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L)));
        assertThat(t.getDeployDbSelection()).containsExactly(new DeployDbChoice("main", 10L));
    }

    @Test
    void 카탈로그_id가_없는_옛_작업도_git_url로_찾은_카탈로그로_검증한다() {
        Task t = task();
        t.setRepoCatalogId(null);
        t.setGitUrl("https://git.example/owner/repo.git");
        when(catalogRepo.findByGitUrl("https://git.example/owner/repo.git")).thenReturn(Optional.of(catalog));
        conn(10L, DbConnectionScope.REPO, 5L, null, DbType.POSTGRESQL);
        service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L)));
        assertThat(t.getDeployDbSelection()).containsExactly(new DeployDbChoice("main", 10L));
    }

    @Test
    void 다른_카탈로그_연결은_400() {
        Task t = task();
        conn(10L, DbConnectionScope.REPO, 6L, null, DbType.POSTGRESQL);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L))))
                .isInstanceOf(TaskException.class).hasMessageContaining("레포에 속하지 않는");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getDeployDbSelection()).isEmpty();
    }

    @Test
    void 다른_관리자_개인연결은_400() {
        task();
        conn(10L, DbConnectionScope.USER, 5L, "other", DbType.POSTGRESQL);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L))))
                .isInstanceOf(TaskException.class).hasMessageContaining("개인 DB 연결");
    }

    @Test
    void 이미_저장된_다른관리자_연결은_허용() {
        Task t = task();
        t.setDeployDbSelection(new ArrayList<>(List.of(new DeployDbChoice("main", 10L))));
        conn(10L, DbConnectionScope.USER, 5L, "other", DbType.POSTGRESQL);
        service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L)));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void DB_ENV대상_이름의_자유행은_400() {
        Task t = task();
        assertThatThrownBy(() -> service.deploy(42L, "admin", List.of(new EnvVar("JDBC_URL", "x", false)), null))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("배포 DB가 정하는 변수는 따로 넣을 수 없습니다: JDBC_URL");
        assertThat(t.getEnvVars()).isNullOrEmpty();
    }

    @Test
    void 카탈로그_변수_키는_허용() {
        Task t = task();
        service.deploy(42L, "admin", List.of(new EnvVar("APP_MODE", "prod", false)), null);
        assertThat(t.getEnvVars()).hasSize(1);
    }

    @Test
    void 배포설정_없는_레포에_선택을_보내면_400() {
        task();
        when(catalog.getDeployConfig()).thenReturn(null);
        conn(10L, DbConnectionScope.REPO, 5L, null, DbType.POSTGRESQL);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L))))
                .isInstanceOf(TaskException.class).hasMessageContaining("배포 설정이 없어");
    }

    @Test
    void 모르는_자리와_중복_자리는_400() {
        task();
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("x", null))))
                .isInstanceOf(TaskException.class);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null,
                List.of(new DeployDbChoice("main", null), new DeployDbChoice("main", null))))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void 종류가_다르거나_비활성_연결은_400() {
        task();
        conn(10L, DbConnectionScope.REPO, 5L, null, DbType.MYSQL);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 10L))))
                .isInstanceOf(TaskException.class).hasMessageContaining("DB 종류");
        DbConnection off = conn(11L, DbConnectionScope.REPO, 5L, null, DbType.POSTGRESQL);
        ReflectionTestUtils.setField(off, "enabled", false);
        assertThatThrownBy(() -> service.deploy(42L, "admin", null, List.of(new DeployDbChoice("main", 11L))))
                .isInstanceOf(TaskException.class).hasMessageContaining("사용 중지");
    }
}
