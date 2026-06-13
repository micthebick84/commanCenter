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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewAnswerIdempotencyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewTurnRepository turnRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private Long sid;

    private static RequestPostProcessor userJwt(String u) {
        return jwt().jwt(b -> b.claim("username", u).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach void seed() {
        sessionRepo.deleteAll();
        sid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1", List.of())).getId();
    }

    @Test
    void duplicate_answer_with_same_replyToSeq_is_accepted_once() throws Exception {
        // claim?workerId= → question (seq=1, AWAITING_INPUT)
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                .param("workerId", "iw-1")
                .contentType(APPLICATION_JSON)
                .content("{\"content\":\"Q1\",\"claudeSessionId\":\"s1\",\"kind\":\"question\"}"));

        String answer = json.writeValueAsString(new com.hamonsoft.netismaker.dto.AnswerRequest("내 답변", 1));

        // 1st answer → 200, re-queues to QUEUED, adds user turn
        mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                .contentType(APPLICATION_JSON).content(answer)).andExpect(status().isOk());

        long afterFirst = turnRepo.findBySessionIdOrderBySeqAsc(sid).stream()
                .filter(t -> "user".equals(t.getRole())).count();

        // 2nd identical answer (same replyToSeq=1) → no new user turn
        mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                .contentType(APPLICATION_JSON).content(answer)).andExpect(status().isOk());

        long afterSecond = turnRepo.findBySessionIdOrderBySeqAsc(sid).stream()
                .filter(t -> "user".equals(t.getRole())).count();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(1); // 중복 무시
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.QUEUED);
    }
}
