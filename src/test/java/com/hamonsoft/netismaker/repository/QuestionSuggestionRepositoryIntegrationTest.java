package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionSuggestion;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.SuggestionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V48(질문 세션 제안 표) — targets JSONB 왕복, 세션 삭제 시 CASCADE, 카탈로그 삭제 시 SET NULL, 상태별 id 순 조회.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionSuggestionRepositoryIntegrationTest {

    @Autowired private QuestionSuggestionRepository repo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long newSession() {
        return sessionRepo.saveAndFlush(
                InterviewSession.createQuestion("acme/x", "main", "t", "q", "user1", List.of(), null, null)).getId();
    }

    private Long newRepo() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return repoCatalogRepo.save(RepoCatalogEntry.create("제안-" + suffix,
                "https://github.com/acme/suggest-" + suffix + ".git", "github", "acme/suggest-" + suffix,
                "main", null, "admin1")).getId();
    }

    @Test
    void V48이_적용되고_targets가_jsonb로_왕복된다() {
        Long sid = newSession();
        Long id = repo.saveAndFlush(QuestionSuggestion.db(sid, 2, null, "설정 값이 테이블에 있다", List.of("tb_a", "tb_b"))).getId();

        QuestionSuggestion got = repo.findById(id).orElseThrow();
        assertThat(got.getTargets()).containsExactly("tb_a", "tb_b");
        assertThat(got.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(got.getCreatedAt()).isNotNull();
    }

    @Test
    void 세션을_지우면_제안도_지워진다() {
        Long sid = newSession();
        repo.saveAndFlush(QuestionSuggestion.db(sid, 1, null, "r", List.of()));

        jdbc.update("DELETE FROM com.interview_session WHERE id = ?", sid);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM com.question_suggestion WHERE session_id = ?", Integer.class, sid);
        assertThat(count).isZero();
    }

    @Test
    void 카탈로그_행을_지우면_레포_칸만_NULL이_된다() {
        Long sid = newSession();
        Long catalog = newRepo();
        Long id = repo.saveAndFlush(QuestionSuggestion.refRepo(sid, 1, catalog, "r")).getId();

        jdbc.update("DELETE FROM com.repo_catalog WHERE id = ?", catalog);

        Map<String, Object> row = jdbc.queryForMap("SELECT repo_catalog_id, session_id FROM com.question_suggestion WHERE id = ?", id);
        assertThat(row.get("repo_catalog_id")).isNull();
        assertThat(row.get("session_id")).isEqualTo(sid);
    }

    @Test
    void 상태별_조회는_id_순이다() {
        Long sid = newSession();
        Long a = repo.saveAndFlush(QuestionSuggestion.db(sid, 1, null, "a", List.of())).getId();
        QuestionSuggestion dismissed = QuestionSuggestion.db(sid, 1, null, "b", List.of());
        dismissed.decide(SuggestionStatus.DISMISSED, "user1", OffsetDateTime.now());
        repo.saveAndFlush(dismissed);
        Long c = repo.saveAndFlush(QuestionSuggestion.db(sid, 2, null, "c", List.of())).getId();

        List<QuestionSuggestion> pending = repo.findBySessionIdAndStatusOrderByIdAsc(sid, SuggestionStatus.PENDING);

        assertThat(pending).extracting(QuestionSuggestion::getId).containsExactly(a, c);
        assertThat(repo.findBySessionIdOrderByIdAsc(sid)).hasSize(3);
    }
}
