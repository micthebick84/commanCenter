package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewWorkerApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewTurnRepository turnRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private Long sid;

    @BeforeEach void seed() {
        sessionRepo.deleteAll();
        InterviewSession s = InterviewSession.create("hamonsoft/netis-backend", "main",
                "RBAC", "권한 추가", "user1", List.of(), "claude-opus-4-8", "high");
        sid = sessionRepo.save(s).getId();
    }

    @Test
    void claim_returns_session_and_sets_RUNNING() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.githubRepo").value("hamonsoft/netis-backend"))
                .andExpect(jsonPath("$.workDir").exists());  // claim이 work_dir 할당/반환
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.RUNNING);
    }

    @Test
    void claim_no_queued_returns_204() throws Exception {
        sessionRepo.deleteAll();
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void question_persists_turn_and_sets_AWAITING_INPUT() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"어떤 인증을 쓰나요?\",\"claudeSessionId\":\"sess-1\",\"kind\":\"question\",\"costUsd\":0.02}"))
                .andExpect(status().isNoContent());
        var s = sessionRepo.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-1");
        assertThat(turnRepo.findBySessionIdOrderBySeqAsc(sid)).hasSize(1);
    }

    @Test
    void question_blank_claude_session_id_is_not_stored() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"질문\",\"claudeSessionId\":\"   \",\"kind\":\"question\"}"))
                .andExpect(status().isNoContent());
        // blank-guard: 빈/공백 claude_session_id는 저장하지 않음
        assertThat(sessionRepo.findById(sid).orElseThrow().getClaudeSessionId()).isNull();
    }

    @Test
    void plan_persists_and_sets_PLAN_READY() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[{\\\"task\\\":\\\"a\\\"}]\",\"costUsd\":0.5,\"durationMs\":12000}"))
                .andExpect(status().isNoContent());
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
    }

    @Test
    void question_보고는_토큰을_세션에_누적한다() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                .param("workerId", "iw-1")).andExpect(status().isOk());

        mvc.perform(post("/worker/interviews/" + sid + "/question")
                        .header("X-Worker-API-Key", apiKey).param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":"Q1","claudeSessionId":"cs-1","kind":"question",
                                 "costUsd":0.12,"inputTokens":1000,"outputTokens":250,
                                 "cacheCreationTokens":30,"cacheReadTokens":8000}
                                """))
                .andExpect(status().isNoContent());

        InterviewSession s = sessionRepo.findById(sid).orElseThrow();
        assertThat(s.getInputTokens()).isEqualTo(1000);
        assertThat(s.getOutputTokens()).isEqualTo(250);
        assertThat(s.getCacheCreationTokens()).isEqualTo(30);
        assertThat(s.getCacheReadTokens()).isEqualTo(8000);
    }

    @Test
    void claim_requires_api_key_returns_401_or_403() throws Exception {
        mvc.perform(post("/worker/interviews/claim").param("workerId", "iw-1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void activity_returns_204_and_does_not_touch_session_state() throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/activity").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[{\"seq\":1,\"type\":\"tool\",\"label\":\"Read\",\"detail\":\"a.ts\"},"
                                + "{\"seq\":2,\"type\":\"text\",\"content\":\"이제…\"}]}"))
                .andExpect(status().isNoContent());
        // transient 계약: 세션 상태/턴 무변화 (QUEUED 그대로, 턴 0건)
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(turnRepo.findBySessionIdOrderBySeqAsc(sid)).isEmpty();
    }

    @Test
    void activity_rejects_oversized_batch_with_400() throws Exception {
        String events = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "{\"seq\":" + i + ",\"type\":\"text\",\"content\":\"x\"}")
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/worker/interviews/" + sid + "/activity").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[" + events + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activity_without_worker_key_is_rejected() throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/activity")
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[{\"seq\":1,\"type\":\"text\",\"content\":\"x\"}]}"))
                .andExpect(status().is4xxClientError()); // InterviewSecuritySurfaceTest의 claim 테스트와 동일 기대
    }
}
