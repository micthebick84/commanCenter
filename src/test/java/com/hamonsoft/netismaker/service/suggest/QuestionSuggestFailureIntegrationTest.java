package com.hamonsoft.netismaker.service.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 후보 계산이 실패해도 답변 보고는 저장된다 (스펙 2026-11-22 §6.2) - 실제 보안 필터·프록시·트랜잭션 경로에서.
 * 컨트롤러가 클래스 단위 @PreAuthorize(ROLE_WORKER)라 빈을 직접 부르지 않고 워커처럼 X-Worker-API-Key로 HTTP 요청을 보낸다.
 * 목 빈이 따로 컨텍스트를 만들므로 QuestionSuggestFlowIntegrationTest와 분리했다.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionSuggestFailureIntegrationTest {

    @MockitoBean private QuestionSuggestCandidates candidates;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewTurnRepository turnRepo;
    @Autowired private QuestionSuggestionRepository suggestionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @Test
    void 후보_계산이_실패해도_답변은_저장된다() throws Exception {
        when(candidates.forSession(any())).thenThrow(new IllegalStateException("boom"));
        InterviewSession s = InterviewSession.createQuestion("acme/x", "main", "t", "q?", "user1", List.of(), null, null);
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId("w-suggest-fail");
        s = sessionRepo.saveAndFlush(s);
        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 1L, "이유", null));
        String body = json.writeValueAsString(new WorkerQuestionRequest("답", "sess", "question",
                BigDecimal.ZERO, null, null, null, null, null, null, null, sug));

        mvc.perform(post("/worker/interviews/" + s.getId() + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w-suggest-fail")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        assertThat(turnRepo.findBySessionIdOrderBySeqAsc(s.getId()))
                .anySatisfy(t -> assertThat(t.getRole()).isEqualTo("assistant"));
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(suggestionRepo.findBySessionIdOrderByIdAsc(s.getId())).isEmpty();
    }
}
