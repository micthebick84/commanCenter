package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 질문 세션 API 표면 (스펙 §5). 상한은 낮춰서 429/400 경로를 짧게 검증한다:
 * 활성 세션 2개 → 3번째 429, assistant 답변 2개 → ask 400.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest(properties = {"app.question.max-active-per-user=2", "app.question.max-qa-turns=2"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private static final String CREATE_BODY =
            "{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"title\":\"인증 흐름\","
                    + "\"question\":\"로그인은 어디서 처리되나요?\",\"model\":\"claude-sonnet-5\",\"effort\":\"medium\"}";

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long createQuestion(RequestPostProcessor as) throws Exception {
        String resp = mvc.perform(post("/api/questions").with(as).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    /** 워커가 세션을 claim하고 답변 1건을 보고 → AWAITING_INPUT. */
    private void workerAnswers(long sid, String text) throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.kind").value("QUESTION"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"" + text + "\",\"claudeSessionId\":\"sess-q\",\"kind\":\"question\",\"costUsd\":0.1}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void POST_creates_queued_question_for_any_authenticated_user() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/questions/")))
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.requesterId").value("user1"))
                .andExpect(jsonPath("$.repoAlias").value("Netis7.0"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"));
    }

    @Test
    void POST_over_active_limit_returns_429() throws Exception {
        createQuestion(userJwt("user1"));
        createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isTooManyRequests());
    }

    /** question은 킥오프 프롬프트에 그대로 삽입되므로 @Size(max=20000) 상한이 있어야 한다 (final-review minor). */
    @Test
    void POST_with_question_over_length_limit_returns_400() throws Exception {
        String tooLong = "a".repeat(20001);
        String body = json.writeValueAsString(Map.of(
                "repoCatalogId", 1,
                "githubBranch", "main",
                "title", "인증 흐름",
                "question", tooLong));
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_detail_owner_and_admin_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.description").value("로그인은 어디서 처리되나요?"));
        mvc.perform(get("/api/questions/" + id).with(adminJwt("admin1"))).andExpect(status().isOk());
        mvc.perform(get("/api/questions/" + id).with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_stream_owner_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_list_mine_only_unless_admin_asks_all() throws Exception {
        createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions").with(userJwt("user1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/questions").with(userJwt("user2"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").with(adminJwt("admin1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").param("all", "true").with(adminJwt("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requesterId").value("user1"));
        // 일반 사용자의 all=true는 무시 — 본인 것만
        mvc.perform(get("/api/questions").param("all", "true").with(userJwt("user2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cross_kind_access_is_404_in_both_directions() throws Exception {
        long qid = createQuestion(userJwt("user1"));
        long iid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1",
                List.of(), "claude-opus-5", "high")).getId();
        mvc.perform(get("/api/interviews/" + qid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/interviews/" + qid + "/cancel").with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/questions/" + iid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions/" + iid + "/close").with(adminJwt("admin1"))).andExpect(status().isNotFound());
    }

    @Test
    void worker_answer_then_owner_asks_followup_requeues_without_phase() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "AuthController에서 처리합니다");
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.statusName").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.currentPhase").isEmpty())   // null — brainstorming 표기 없음
                .andExpect(jsonPath("$.turns", hasSize(1)));
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"토큰 검증은요?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.turns", hasSize(2)));
        // 타인은 추가 질문 불가
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user2"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ask_at_qa_turn_cap_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"더?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk());
        workerAnswers(id, "답변 2");   // assistant 답변 2개 = 상한
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"또?\",\"replyToSeq\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("최대 문답 수")));
    }

    @Test
    void worker_plan_on_question_session_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1}"))
                .andExpect(status().isBadRequest());
        // 세션은 그대로 RUNNING — 인터뷰 서비스가 이어서 답변 보고 가능
        assertThat(sessionRepo.findById(id).orElseThrow().getStatus().name()).isEqualTo("RUNNING");
    }

    @Test
    void close_by_owner_moves_to_cancelled_and_touches_no_task() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("CANCELLED"));
        assertThat(taskRepo.count()).isZero();   // taskId null → mirrorTask no-op, task 없음 유지
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1"))).andExpect(status().isConflict());
    }

    /** 프론트 새 질문 입력창은 제목을 보내지 않는다 — 서버가 첫 줄로 생성 (스펙 2026-09-05 §2). */
    @Test
    void create_without_title_derives_title_from_question() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\","
                                + "\"question\":\"로그인은 어디서 처리되나요?\\n상세 설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("로그인은 어디서 처리되나요?"));
    }

    /** 워커가 /question에 컨텍스트 스냅샷을 보고하면 상세·목록 응답에 그대로 노출된다 (스펙 2026-09-05 §4.2). */
    @Test
    void worker_context_snapshot_is_exposed_on_detail_and_list() throws Exception {
        String created = mvc.perform(post("/api/questions").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();

        mvc.perform(post("/worker/interviews/claim").param("workerId", "w1").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/question").param("workerId", "w1")
                        .header("X-Worker-API-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"content\":\"AuthController입니다\",\"claudeSessionId\":\"sess-1\",\"kind\":\"question\","
                                + "\"costUsd\":0.01,\"inputTokens\":4,\"outputTokens\":120,\"cacheCreationTokens\":30000,"
                                + "\"cacheReadTokens\":46000,\"contextTokens\":76004,\"contextWindow\":200000}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextTokens").value(76004))
                .andExpect(jsonPath("$.contextWindow").value(200000));
        mvc.perform(get("/api/questions").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contextTokens").value(76004));
    }
}
