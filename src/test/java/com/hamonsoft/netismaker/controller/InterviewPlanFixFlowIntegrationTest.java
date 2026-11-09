package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 간단 인터뷰(superpowers 끔)의 "계획 고쳐 달라기" 왕복 (설계 2026-11-03-interview-superpowers-toggle §4.1, Review Focus 1·2) —
 * 승인(끔) → 첫 턴에 바로 계획 → 플랜완료에 고칠 점 답변(replyToSeq null) → 같은 Claude 대화로 재claim → 고친 계획 →
 * 확정·구현 claim이 최신 계획을 쓰는지, 이전 계획은 design 턴에 남는지를 모두 HTTP(MockMvc)로 본다.
 * interview_plan은 PK가 session_id라 고친 계획이 같은 행을 덮어쓴다 — confirm·구현 claim은 고치지 않아도 최신 계획을 읽는다.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip). 어노테이션·정리·JWT/워커 API-key 사용 방식은
 * ImplementationModeFlowIntegrationTest의 관용구를 그대로 따른다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewPlanFixFlowIntegrationTest {

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
        // interview_session.task_id FK는 non-cascade라 세션을 task보다 먼저 삭제 (ImplementationModeFlowIntegrationTest와 같은 순서)
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
        String body = json.writeValueAsString(new TaskCreateRequest(1L, "main", "계획 고쳐 달라기 흐름 테스트",
                "간단 인터뷰 플랜완료 답변·재계획·확정 왕복 검증용 작업"));
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void postPlan(long sid, String bodyJson) throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isNoContent());
    }

    @Test
    void 간단_인터뷰_계획을_고치면_같은_대화로_이어_최신_계획으로_확정() throws Exception {
        // 1) 끔으로 승인 → claim: 간단 인터뷰, 첫 턴이라 Claude 세션 id 없음
        Long id = createTask();
        mvc.perform(post("/api/tasks/" + id + "/approve").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"superpowersEnabled\":false}"))
                .andExpect(status().isOk());
        long sid = sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(id).orElseThrow().getId();
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.superpowersEnabled").value(false))
                .andExpect(jsonPath("$.claudeSessionId").doesNotExist());

        // 2) 질문 없이 첫 턴에 바로 계획 v1 — 계획 보고가 Claude 세션 id를 싣는다 (RF1)
        postPlan(sid, "{\"designMarkdown\":\"# 설계 v1\",\"planMarkdown\":\"# 계획 v1\\n\\n### 작업 1: 가\","
                + "\"planJson\":\"[{\\\"title\\\":\\\"v1\\\"}]\",\"claudeSessionId\":\"sess-1\","
                + "\"costUsd\":0.1,\"durationMs\":1000}");
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERVIEW_REVIEW"));

        // 3) 플랜완료에 고칠 점 — replyToSeq null (화면은 라이브로 받은 계획의 design 턴 seq를 모른다, RF2) → 재큐·인터뷰중
        mvc.perform(post("/api/interviews/" + sid + "/answer").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"컬럼명을 바꿔 주세요\",\"replyToSeq\":null}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERVIEWING"));
        mvc.perform(get("/api/tasks/" + id + "/history").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromStatus").value("INTERVIEW_REVIEW"))
                .andExpect(jsonPath("$[0].toStatus").value("INTERVIEWING"))
                .andExpect(jsonPath("$[0].reason").value("계획 수정 요청 → 인터뷰 재개"));

        // 4) 재claim: 같은 Claude 대화를 이어 가고(새 kickoff 아님) 고칠 점이 resume 답변으로 실린다
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.superpowersEnabled").value(false))
                .andExpect(jsonPath("$.claudeSessionId").value("sess-1"))
                .andExpect(jsonPath("$.lastAnswer").value("컬럼명을 바꿔 주세요"));

        // 5) 고친 계획 v2
        postPlan(sid, "{\"designMarkdown\":\"# 설계 v2\",\"planMarkdown\":\"# 계획 v2\\n\\n### 작업 1: 나\","
                + "\"planJson\":\"[{\\\"title\\\":\\\"v2\\\"}]\",\"claudeSessionId\":\"sess-1\","
                + "\"costUsd\":0.1,\"durationMs\":1000}");

        // 계획 행은 하나(PK = session_id)이고 최신 계획으로 덮어써졌다. 이전 계획 본문은 design 턴에 남는다 (끔: design + 빈 줄 + 계획)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM com.interview_plan WHERE session_id = ?", Integer.class, sid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT plan_markdown FROM com.interview_plan WHERE session_id = ?", String.class, sid))
                .startsWith("# 계획 v2");
        List<String> designTurns = jdbc.queryForList(
                "SELECT content FROM com.interview_turn WHERE session_id = ? AND role = 'assistant' AND kind = 'design'"
                        + " ORDER BY seq", String.class, sid);
        assertThat(designTurns).hasSize(2);
        assertThat(designTurns.get(0)).contains("# 계획 v1").doesNotContain("# 계획 v2")
                .isEqualTo("# 설계 v1\n\n# 계획 v1\n\n### 작업 1: 가");
        assertThat(designTurns.get(1)).contains("# 계획 v2")
                .isEqualTo("# 설계 v2\n\n# 계획 v2\n\n### 작업 1: 나");
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERVIEW_REVIEW"));

        // 6) 구현 진행(확정) → task_analysis는 최신 계획의 planJson과 design 전용 본문(계획 본문을 넣지 않는 고정 계약)
        mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"designRequested\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id));
        Map<String, Object> analysis = jdbc.queryForMap(
                "SELECT subtasks_json::text AS subtasks, markdown_result FROM com.task_analysis WHERE task_id = ?", id);
        assertThat(json.readTree((String) analysis.get("subtasks")))
                .isEqualTo(json.readTree("[{\"title\":\"v2\"}]"));
        assertThat(analysis.get("markdown_result")).isEqualTo("# 설계 v2");

        // 7) 구현 claim이 실제로 읽는 계획 본문도 최신 계획
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.planMarkdown").value(startsWith("# 계획 v2")));
    }

    @Test
    void 칼럼은_NOT_NULL_DEFAULT_true() throws Exception {
        // V40: 기존 세션·질문 세션·바디 없는 승인은 켬
        Map<String, Object> col = jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_schema = 'com' AND table_name = 'interview_session'"
                        + " AND column_name = 'superpowers_enabled'");
        assertThat(col.get("data_type")).isEqualTo("boolean");
        assertThat(col.get("is_nullable")).isEqualTo("NO");
        assertThat(col.get("column_default")).isEqualTo("true");

        Long id = createTask();
        mvc.perform(post("/api/tasks/" + id + "/approve").with(adminJwt("admin")))
                .andExpect(status().isOk());
        long sid = sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(id).orElseThrow().getId();
        assertThat(jdbc.queryForObject(
                "SELECT superpowers_enabled FROM com.interview_session WHERE id = ?", Boolean.class, sid)).isTrue();
    }
}
