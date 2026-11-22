package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.SuggestionView;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.QuestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답변 보고의 제안이 저장되고 질문 스냅샷에 PENDING으로 나오며, 세션의 참조는 그대로인지 (스펙 2026-11-22 §6.2).
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionSuggestFlowIntegrationTest {

    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository catalogRepo;
    @Autowired private InterviewService interviewService;
    @Autowired private QuestionService questionService;

    private RepoCatalogEntry newRepo(String alias) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return catalogRepo.save(RepoCatalogEntry.create(alias + "-" + suffix,
                "https://github.com/acme/suggest-" + suffix + ".git", "github", "acme/suggest-" + suffix,
                "main", null, "admin1"));
    }

    @Test
    void 참조_제안_보고는_스냅샷에_PENDING으로_나오고_세션의_참조는_비어_있다() {
        RepoCatalogEntry target = newRepo("대상");
        RepoCatalogEntry other = newRepo("다른");
        InterviewSession s = InterviewSession.createQuestion("acme/x", "main", "t", "q?", "user1", List.of(), null, null);
        s.setRepoCatalogId(target.getId());
        // claim은 다른 시험이 남긴 대기 세션을 집을 수 있어 RUNNING 상태를 직접 만든다
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId("w-suggest");
        s = sessionRepo.saveAndFlush(s);

        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, other.getId(), "이 레포도 봐야 합니다", null));
        interviewService.recordQuestion(s.getId(), "w-suggest", new WorkerQuestionRequest("답", "sess", "question",
                BigDecimal.ZERO, null, null, null, null, null, null, null, sug));

        InterviewResponse r = questionService.get(s.getId(), "user1", false);
        assertThat(r.suggestions()).hasSize(1);
        SuggestionView v = r.suggestions().get(0);
        assertThat(v.status()).isEqualTo("PENDING");
        assertThat(v.kind()).isEqualTo("REF_REPO");
        assertThat(v.repoCatalogId()).isEqualTo(other.getId());
        assertThat(v.repoAlias()).isEqualTo(other.getAlias());
        assertThat(v.defaultBranch()).isEqualTo("main");
        assertThat(v.refSlotsLeft()).isEqualTo(3);
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getRefRepos()).isEmpty();
    }
}
