package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionRouteLog;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.service.route.JevClient;
import com.hamonsoft.netismaker.service.route.RouteDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V47(추천 기록 테이블)과 최근 질문 레포 쿼리 (스펙 2026-11-20 §6.1·§7) — candidates JSONB 왕복, 최근 순서·질문 세션만 집계,
 * 카탈로그 행 삭제 때 기록의 레포 칸 NULL(ON DELETE SET NULL).
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionRouteQueriesIntegrationTest {

    @Autowired private QuestionRouteLogRepository logRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        logRepo.deleteAll();
    }

    private Long newRepo() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return repoCatalogRepo.save(RepoCatalogEntry.create("추천-" + suffix,
                "https://github.com/acme/route-" + suffix + ".git", "github", "acme/route-" + suffix,
                "main", null, "admin1")).getId();
    }

    private InterviewSession session(boolean question, String user, Long repoId, OffsetDateTime createdAt) {
        InterviewSession s = question
                ? InterviewSession.createQuestion("acme/x", "main", "t", "q", user, List.of(), null, null)
                : InterviewSession.create("acme/x", "main", "t", "d", user, List.of(), null, null);
        s.setRepoCatalogId(repoId);
        ReflectionTestUtils.setField(s, "createdAt", createdAt);
        return sessionRepo.saveAndFlush(s);
    }

    @Test
    void V47이_적용되고_추천_기록을_저장하고_읽는다() {
        Long repo = newRepo();
        RouteDecision.Decision d = new RouteDecision.Decision(RouteDecision.RouteState.CONFIDENT, repo,
                List.of(new RouteDecision.Candidate(repo, 0.8)), true);
        JevClient.JevAnswers a = new JevClient.JevAnswers("jev-1.14.0", Map.of(repo, 0.8), 0.9, 0.8, 0.7);

        Long id = logRepo.saveAndFlush(QuestionRouteLog.of("user1", "발췌", d, a, 120)).getId();

        QuestionRouteLog got = logRepo.findById(id).orElseThrow();
        assertThat(got.getState()).isEqualTo("CONFIDENT");
        assertThat(got.getCandidates()).containsExactly(new RouteDecision.Candidate(repo, 0.8));
        assertThat(got.getRecommendedRepoCatalogId()).isEqualTo(repo);
        assertThat(got.getModel()).isEqualTo("jev-1.14.0");
        assertThat(got.getLatencyMs()).isEqualTo(120);
        assertThat(got.getChosenAt()).isNull();

        got.markChosen(repo, null);
        assertThat(logRepo.saveAndFlush(got).getChosenAt()).isNotNull();
    }

    @Test
    void 최근_레포는_마지막으로_쓴_순서이고_질문_세션만_센다() {
        Long r8 = newRepo();
        Long r3 = newRepo();
        Long rOther = newRepo();
        OffsetDateTime now = OffsetDateTime.now();
        // interview_session.requester_id는 com."user" FK — 다른 시험의 질문 세션과 섞이지 않게 이 시험만의 사용자를 만든다.
        String user = "u-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO com.\"user\" (user_id, user_name, password) VALUES (?, ?, 'x')", user, user);
        session(true, user, r8, now.minusHours(5));
        session(true, user, r3, now.minusHours(3));
        session(true, user, r8, now.minusHours(1));
        session(false, user, rOther, now);

        List<Long> recent = sessionRepo.findRecentQuestionRepoCatalogIds(user, PageRequest.of(0, 10));

        assertThat(recent).containsExactly(r8, r3);
    }

    @Test
    void 카탈로그_행을_지우면_기록의_레포_칸이_NULL이_된다() {
        Long repo = newRepo();
        RouteDecision.Decision d = new RouteDecision.Decision(RouteDecision.RouteState.CONFIDENT, repo,
                List.of(new RouteDecision.Candidate(repo, 0.8)), false);
        QuestionRouteLog l = QuestionRouteLog.of("user1", "발췌", d, null, null);
        l.markChosen(repo, null);
        Long id = logRepo.saveAndFlush(l).getId();

        jdbc.update("DELETE FROM com.repo_catalog WHERE id = ?", repo);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT recommended_repo_catalog_id, chosen_repo_catalog_id FROM com.question_route_log WHERE id = ?", id);
        assertThat(row.get("recommended_repo_catalog_id")).isNull();
        assertThat(row.get("chosen_repo_catalog_id")).isNull();
    }
}
