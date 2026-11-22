package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.controller.InterviewWorkerController;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 후보 계산이 실패해도 답변 보고는 저장된다 (스펙 2026-11-22 §6.2) - 실제 프록시·트랜잭션 경로에서.
 * 목 빈이 따로 컨텍스트를 만들므로 QuestionSuggestFlowIntegrationTest와 분리했다.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionSuggestFailureIntegrationTest {

    @MockitoBean private QuestionSuggestCandidates candidates;
    @Autowired private InterviewWorkerController workerController;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewTurnRepository turnRepo;
    @Autowired private QuestionSuggestionRepository suggestionRepo;

    @Test
    void 후보_계산이_실패해도_답변은_저장된다() {
        when(candidates.forSession(any())).thenThrow(new IllegalStateException("boom"));
        InterviewSession s = InterviewSession.createQuestion("acme/x", "main", "t", "q?", "user1", List.of(), null, null);
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId("w-suggest-fail");
        s = sessionRepo.saveAndFlush(s);
        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 1L, "이유", null));

        workerController.question(s.getId(), "w-suggest-fail", new WorkerQuestionRequest("답", "sess", "question",
                BigDecimal.ZERO, null, null, null, null, null, null, null, sug));

        assertThat(turnRepo.findBySessionIdOrderBySeqAsc(s.getId()))
                .anySatisfy(t -> assertThat(t.getRole()).isEqualTo("assistant"));
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(suggestionRepo.findBySessionIdOrderByIdAsc(s.getId())).isEmpty();
    }
}
