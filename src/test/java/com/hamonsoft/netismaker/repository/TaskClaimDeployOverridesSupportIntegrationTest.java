package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * deployOverrides를 알리지 않은 워커(구버전)는 배포 설정이 있는 레포나 고른 배포 DB가 있는 배포대기를 claim하지 못한다
 * (스펙 2026-11-24 §9) — 구버전 jar는 덮어쓰기를 버리고 저장된 env로만 배포하기 때문. 네이티브 claim 쿼리의 deploy 조건.
 * RUN_TESTCONTAINERS=true 전용 (이 PC에는 Docker가 없어 CI가 돌린다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskClaimDeployOverridesSupportIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskService taskService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        fixRepo.deleteAll();
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        jdbc.update("UPDATE com.repo_catalog SET deploy_config = NULL");
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE com.repo_catalog SET deploy_config = NULL");
    }

    /** catalog id=1 = V14 seed. 생성 시각을 minutesAgo분 전으로 당겨 claim 순서(FIFO)를 정한다. */
    private Task deployPending(String selectionJson, int minutesAgo) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "배포 claim", "설명"), "user1");
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setHeadBranch("netismaker/task-" + t.getId() + "-x");
        t.setHeadSha(SHA);
        t = taskRepo.saveAndFlush(t);
        jdbc.update("UPDATE com.task SET deploy_db_selection = CAST(? AS jsonb), "
                        + "created_at = now() - make_interval(mins => CAST(? AS integer)) WHERE id = ?",
                selectionJson, minutesAgo, t.getId());
        return t;
    }

    private List<Task> claim(boolean supportsDeployOverrides) {
        return taskRepo.findClaimableAmongForUpdateSkipLocked(List.of(TaskStatus.DEPLOY_PENDING),
                true, true, true, true, true, supportsDeployOverrides);
    }

    @Test
    void 배포설정_레포의_배포대기는_토큰없는_워커에_안간다() {
        jdbc.update("UPDATE com.repo_catalog SET deploy_config = CAST('{\"databases\":[],\"variables\":[]}' AS jsonb) WHERE id = 1");
        deployPending("[]", 10);

        assertThat(claim(false)).isEmpty();
    }

    @Test
    void 고른_배포_DB가_있으면_배포설정이_없어도_토큰없는_워커에_안간다() {
        Task chosen = deployPending("[{\"slot\":\"main\",\"connectionId\":43}]", 10);

        assertThat(claim(false)).isEmpty();
        assertThat(claim(true)).extracting(Task::getId).containsExactly(chosen.getId());
    }

    @Test
    void 토큰있는_워커는_받는다() {
        jdbc.update("UPDATE com.repo_catalog SET deploy_config = CAST('{\"databases\":[],\"variables\":[]}' AS jsonb) WHERE id = 1");
        Task t = deployPending("[]", 10);

        assertThat(claim(true)).extracting(Task::getId).containsExactly(t.getId());
    }

    @Test
    void 배포설정_없는_레포는_그대로() {
        Task t = deployPending("[]", 10);

        assertThat(claim(false)).extracting(Task::getId).containsExactly(t.getId());
        assertThat(claim(true)).extracting(Task::getId).containsExactly(t.getId());
    }
}
