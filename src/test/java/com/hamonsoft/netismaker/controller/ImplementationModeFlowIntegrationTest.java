package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 구현 방식 선택 기능의 통합 테스트 (스펙 2026-10-15 §9) — V29 기본값·CHECK 제약, 그리고
 * 플랜 확정 → 워커 claim → 구현실패 → 재시도 → 이전 시도의 늦은 보고 → 재claim 왕복.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip).
 * 어노테이션·정리·JWT/워커 API-key 사용 방식은 TaskDesignFlowIntegrationTest의 관용구를 그대로 따르고,
 * 인터뷰 플랜 준비는 InterviewApiIntegrationTest의 워커 claim → plan 흐름을 따른다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class ImplementationModeFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void cleanTasks() {
        // interview_session.task_id FK는 non-cascade라 세션을 task보다 먼저 삭제 (TaskDesignFlowIntegrationTest와 같은 순서)
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

    // catalog id=1 corresponds to the V14 seed entry (alias 'Netis7.0').
    private Long createTask() throws Exception {
        String body = json.writeValueAsString(new TaskCreateRequest(1L, "main", "구현 방식 흐름 테스트",
                "구현 방식 확정·재시도 왕복 검증용 작업"));
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    /** 리포지토리로 DB의 최신 행을 다시 읽는다 — 응답이 숨기는 컬럼(head_branch 등)까지 본다. */
    private Task reload(Long id) {
        return taskRepo.findById(id).orElseThrow();
    }

    @Test
    void v29_defaults_and_check_constraints() throws Exception {
        Long id = createTask();

        // 새로 만든 작업은 직접·1회째·상한 없음 (V29 기본값)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT implementation_mode, implementation_attempt, implementation_timeout_minutes"
                        + " FROM com.task WHERE id = ?", id);
        assertThat(row.get("implementation_mode")).isEqualTo("DIRECT");
        assertThat(row.get("implementation_attempt")).isEqualTo(1);
        assertThat(row.get("implementation_timeout_minutes")).isNull();

        // 모르는 방식 이름·0 이하의 시도 번호는 DB가 거절한다
        assertThatThrownBy(() -> jdbc.update("UPDATE com.task SET implementation_mode = 'XYZ' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_task_implementation_mode");
        assertThatThrownBy(() -> jdbc.update("UPDATE com.task SET implementation_attempt = 0 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_task_implementation_attempt");
    }

    @Test
    void confirm_claim_fail_retry_and_late_report_round_trip() throws Exception {
        // 준비) 등록 → 관리자 승인(인터뷰 시작) → 인터뷰 워커가 플랜 보고 → 플랜승인대기
        Long id = createTask();
        mvc.perform(post("/api/tasks/" + id + "/approve").with(adminJwt("admin")))
                .andExpect(status().isOk());
        long sid = sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(id).orElseThrow().getId();
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\","
                                + "\"costUsd\":0.1,\"durationMs\":1000}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERVIEW_REVIEW"));

        // a) 리뷰 방식으로 플랜 확정 → 구현대기, 방식 리뷰, 1회째, 아직 상한 없음. 이력 사유에 방식이 남는다
        mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"designRequested\":false,\"implementationMode\":\"REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id));
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.implementationMode").value("REVIEW"))
                .andExpect(jsonPath("$.implementationAttempt").value(1))
                .andExpect(jsonPath("$.implementationTimeoutMinutes").doesNotExist());
        mvc.perform(get("/api/tasks/" + id + "/history").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").value("INTERVIEW_REVIEW"))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].reason").value(containsString("구현 방식: 리뷰")));

        // b) 워커 claim → 구현 지시 블록(방식·시도 번호·상한 80)이 실리고 같은 상한이 작업에 기록된다
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.implementation.mode").value("REVIEW"))
                .andExpect(jsonPath("$.implementation.attempt").value(1))
                .andExpect(jsonPath("$.implementation.timeoutMinutes").value(80));
        assertThat(reload(id).getImplementationTimeoutMinutes()).isEqualTo(80);
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPLEMENTING"))
                .andExpect(jsonPath("$.implementationMode").value("REVIEW"))
                .andExpect(jsonPath("$.implementationAttempt").value(1))
                .andExpect(jsonPath("$.claimedAt").isNotEmpty())
                .andExpect(jsonPath("$.implementationTimeoutMinutes").value(80));

        // c) 시도 1의 구현실패 보고 (브랜치는 만들었지만 PR 전에 실패) → 구현실패.
        //    이 시점의 흔적(브랜치·claim 시각·상한)이 d)에서 비워지는지 보려고 먼저 확인해 둔다
        String branch = "netismaker/task-" + id + "-flow";
        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"status\":\"IMPLEMENTATION_FAILED\","
                                + "\"failureReason\":\"claude 시간 초과\",\"headBranch\":\"" + branch + "\","
                                + "\"implementationAttempt\":1}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPLEMENTATION_FAILED"));
        Task failed = reload(id);
        assertThat(failed.getHeadBranch()).isEqualTo(branch);
        assertThat(failed.getClaimedAt()).isNotNull();
        assertThat(failed.getImplementationTimeoutMinutes()).isEqualTo(80);

        // d) 관리자가 SDD로 재시도 → 구현대기, 2회째, 방식 SDD. 이전 시도의 브랜치·claim 시각·상한은 비운다
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"SDD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.implementationMode").value("SDD"))
                .andExpect(jsonPath("$.implementationAttempt").value(2));
        Task retried = reload(id);
        assertThat(retried.getImplementationMode()).isEqualTo(ImplementationMode.SDD);
        assertThat(retried.getImplementationAttempt()).isEqualTo(2);
        assertThat(retried.getHeadBranch()).isNull();
        assertThat(retried.getClaimedAt()).isNull();
        assertThat(retried.getImplementationTimeoutMinutes()).isNull();
        mvc.perform(get("/api/tasks/" + id + "/history").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").value("IMPLEMENTATION_FAILED"))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].reason").value(containsString("2회째")))
                .andExpect(jsonPath("$[0].reason").value(containsString("리뷰 → SDD")));

        // e) 시도 1의 늦은 PR생성 보고 → 정상 응답(204)이지만 상태·PR 정보는 그대로, 이력에만 남는다
        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"status\":\"PR_CREATED\","
                                + "\"prUrl\":\"https://github.com/owner/repo/pull/7\",\"prNumber\":7,"
                                + "\"headBranch\":\"" + branch + "\","
                                + "\"headSha\":\"0123456789abcdef0123456789abcdef01234567\","
                                + "\"implementationAttempt\":1}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.implementationAttempt").value(2))
                // 작업 응답의 implementation은 PR 정보 뷰(prUrl·로그가 없으면 null) — claim 응답의 구현 지시 블록과 이름만 같다
                .andExpect(jsonPath("$.implementation").doesNotExist());
        Task afterLateReport = reload(id);
        assertThat(afterLateReport.getPrUrl()).isNull();
        assertThat(afterLateReport.getPrNumber()).isNull();
        assertThat(afterLateReport.getHeadBranch()).isNull();
        assertThat(afterLateReport.getHeadSha()).isNull();
        mvc.perform(get("/api/tasks/" + id + "/history").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].reason").value(containsString("이전 시도(1회째)의 늦은 보고 무시")));

        // f) 같은 작업이 다시 claim되면 2회째·SDD·상한 240이 실린다
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.implementation.mode").value("SDD"))
                .andExpect(jsonPath("$.implementation.attempt").value(2))
                .andExpect(jsonPath("$.implementation.timeoutMinutes").value(240));
        assertThat(reload(id).getImplementationTimeoutMinutes()).isEqualTo(240);
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IMPLEMENTING"))
                .andExpect(jsonPath("$.implementationMode").value("SDD"))
                .andExpect(jsonPath("$.implementationAttempt").value(2))
                .andExpect(jsonPath("$.claimedAt").isNotEmpty())
                .andExpect(jsonPath("$.implementationTimeoutMinutes").value(240));
    }
}
