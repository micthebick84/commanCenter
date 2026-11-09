package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.WriteClassification;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDbWriteLogRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB 변경 조회·실행 안 함·관리자 실행 흐름 (스펙 2026-11-03 §6.5, 계획 Task 12). 실제 PostgreSQL의 조건부 UPDATE(transition)가
 * 동시에 누른 두 관리자 중 한 명만 통과시키는지(Review Focus 2)와 권한(조회는 등록자·관리자, 처리는 관리자)을 본다.
 * 외부 DB에 실제로 쓰는 실행 성공 경로는 서비스 단위 시험(DbWriteServiceDeferredTest)이 보고, 여기서는 스위치가 꺼진 기본 설정에서
 * 관리자 실행이 409로 막히고 기록이 승인 대기 그대로인지를 본다.
 * Testcontainers 기반 — RUN_TESTCONTAINERS=true 일 때만 실행(이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskDbChangeFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDbWriteLogRepository logRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void cleanTasks() {
        // task_db_write_log는 task 삭제로 함께 지워진다(ON DELETE CASCADE)
        fixRepo.deleteAll();
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor adminJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private Long task(String requester) {
        Task t = Task.create("owner/repo", "main", "DB 변경 흐름", "d", requester, 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.PR_CREATED);
        return taskRepo.save(t).getId();
    }

    private Long log(Long taskId, WriteClassification c, String sql) {
        // 연결을 지운 뒤의 기록처럼 connection_id는 비운다(FK 없이도 만들 수 있다) — 이름은 기록 시점 값
        return logRepo.save(TaskDbWriteLog.create(taskId, null, "개발DB (PostgreSQL)", 1, null, sql, c)).getId();
    }

    @Test
    void dismiss_뒤_GET에_DISMISSED_와_이력() throws Exception {
        Long id = task("user1");
        Long deferred = log(id, WriteClassification.DEFERRED, "ALTER TABLE legacy DROP COLUMN old_col");
        Long rejected = log(id, WriteClassification.REJECTED, "DROP TABLE legacy");

        // 목록은 등록자·관리자만 — 남의 작업은 403, 시간순이다
        mvc.perform(get("/api/tasks/" + id + "/db-changes").with(userJwt("user2")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/" + id + "/db-changes").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(deferred))
                .andExpect(jsonPath("$[0].classification").value("DEFERRED"))
                .andExpect(jsonPath("$[0].connectionName").value("개발DB (PostgreSQL)"))
                .andExpect(jsonPath("$[0].sqlText").value("ALTER TABLE legacy DROP COLUMN old_col"))
                .andExpect(jsonPath("$[1].id").value(rejected));
        mvc.perform(get("/api/tasks/" + id + "/db-changes").with(adminJwt("admin")))
                .andExpect(status().isOk());

        // 처리는 관리자 전용
        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/dismiss").with(userJwt("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/dismiss").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deferred))
                .andExpect(jsonPath("$.classification").value("DISMISSED"))
                .andExpect(jsonPath("$.executedBy").value("admin"));

        mvc.perform(get("/api/tasks/" + id + "/db-changes").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classification").value("DISMISSED"))
                .andExpect(jsonPath("$[0].executedBy").value("admin"))
                .andExpect(jsonPath("$[0].executedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].classification").value("REJECTED"));
        List<TaskStatusHistory> history = historyRepo.findByTaskIdOrderByAtDescIdDesc(id, Limit.of(5));
        assertThat(history.get(0).getReason()).isEqualTo("DB 변경 실행 안 함: #" + deferred);
        assertThat(history.get(0).getFromStatus()).isEqualTo(history.get(0).getToStatus())
                .isEqualTo(TaskStatus.PR_CREATED.dbValue());

        // 다시 누르거나 거부·다른 작업의 기록을 누르면 막힌다
        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/dismiss").with(adminJwt("admin")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + rejected + "/dismiss").with(adminJwt("admin")))
                .andExpect(status().isConflict());
        Long other = task("user1");
        mvc.perform(post("/api/tasks/" + other + "/db-changes/" + deferred + "/dismiss").with(adminJwt("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 비관리자_execute는_403_스위치가_꺼진_기본_설정에서는_관리자도_409() throws Exception {
        Long id = task("user1");
        Long deferred = log(id, WriteClassification.DEFERRED, "ALTER TABLE legacy DROP COLUMN old_col");

        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/execute").with(userJwt("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/execute").with(adminJwt("admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("개발 DB 쓰기 스위치가 꺼져 있습니다"));

        // 기록은 승인 대기 그대로 — 처리한 시각·사람이 남지 않았다
        TaskDbWriteLog row = logRepo.findById(deferred).orElseThrow();
        assertThat(row.getClassification()).isEqualTo(WriteClassification.DEFERRED);
        assertThat(row.getExecutedAt()).isNull();
        assertThat(row.getExecutedBy()).isNull();
    }

    /** Review Focus 2 — 두 관리자가 동시에 눌러도 선점(조건부 UPDATE)이 한 번만 통과한다. */
    @Test
    void 동시에_두_관리자가_눌러도_한번만_처리() throws Exception {
        Long id = task("user1");
        Long deferred = log(id, WriteClassification.DEFERRED, "ALTER TABLE legacy DROP COLUMN old_col");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String admin : List.of("admin1", "admin2")) {
                Callable<Integer> click = () -> {
                    start.await();
                    return mvc.perform(post("/api/tasks/" + id + "/db-changes/" + deferred + "/dismiss")
                                    .with(adminJwt(admin)))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(click));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(logRepo.findById(deferred).orElseThrow().getClassification()).isEqualTo(WriteClassification.DISMISSED);
        // 이력도 한 번만 남는다
        assertThat(historyRepo.findByTaskIdOrderByAtDescIdDesc(id, Limit.of(10)).stream()
                .filter(h -> ("DB 변경 실행 안 함: #" + deferred).equals(h.getReason())).count()).isEqualTo(1);
    }

    @Test
    void 워커는_작업의_기록을_시간순으로_받는다() throws Exception {
        Long id = task("user1");
        Long first = log(id, WriteClassification.EXECUTED, "CREATE TABLE t_new (id int)");
        Long second = log(id, WriteClassification.DEFERRED, "ALTER TABLE legacy DROP COLUMN old_col");

        mvc.perform(get("/worker/tasks/" + id + "/db-changes").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].classification").value("EXECUTED"))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[1].sqlText").value("ALTER TABLE legacy DROP COLUMN old_col"));
        // 워커 키 없이는 못 본다
        mvc.perform(get("/worker/tasks/" + id + "/db-changes"))
                .andExpect(status().is4xxClientError());
    }
}
