package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 직전 배포 조회 native 쿼리 + env_template jsonb 매핑 (스펙 2026-10-07 §3.2, §6.2). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskPreviousDeployIntegrationTest {

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    // catalog id=1 = V14 seed (alias 'Netis7.0') — 모두 같은 레포
    private Task prCreated() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "배포", "설명"), "user1");
        t.setStatus(TaskStatus.PR_CREATED);
        return taskRepo.save(t);
    }

    private static List<EnvVar> env(String value) {
        return List.of(new EnvVar("DB_URL", value, false));
    }

    @Test
    void 배포대기_이력이_최근인_순으로_같은_레포의_env_있는_작업만_돌려준다() throws Exception {
        Task a = prCreated();
        Task b = prCreated();
        prCreated();                       // 배포 요청 없음(env 빈 배열) — 제외
        Task deleted = prCreated();
        Task current = prCreated();

        taskService.deploy(a.getId(), "admin", env("jdbc:a"));
        Thread.sleep(5);
        taskService.deploy(b.getId(), "admin", env("jdbc:b"));
        taskService.deploy(deleted.getId(), "admin", env("jdbc:deleted"));
        Task del = taskRepo.findById(deleted.getId()).orElseThrow();
        ReflectionTestUtils.setField(del, "deletedAt", OffsetDateTime.now());
        taskRepo.save(del);
        // a를 나중에 재배포 → a가 가장 최근. 배포 중지로 deployed_at이 비어 있어도 이력으로 판정한다.
        Task a2 = taskRepo.findById(a.getId()).orElseThrow();
        a2.setStatus(TaskStatus.DEPLOY_FAILED);
        taskRepo.save(a2);
        Thread.sleep(5);
        taskService.redeploy(a.getId(), "admin", env("jdbc:a2"));

        List<Task> found = taskRepo.findRecentlyDeployedWithEnv(current.getGithubRepo(), current.getId(),
                TaskStatus.DEPLOY_PENDING.dbValue());

        assertThat(found).extracting(Task::getId).containsExactly(a.getId(), b.getId());
        assertThat(found.get(0).getDeployedAt()).isNull();
        assertThat(found.get(0).getEnvVars()).extracting(EnvVar::value).containsExactly("jdbc:a2");
    }

    @Test
    void env_template은_jsonb로_저장되고_그대로_읽힌다() {
        Task t = prCreated();
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true))));
        taskRepo.save(t);

        assertThat(taskRepo.findById(t.getId()).orElseThrow().getEnvTemplate())
                .containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }
}
