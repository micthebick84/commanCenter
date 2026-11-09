package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V41의 interview_mockup 표와 task 두 칸 (스펙 2026-11-03 §5·§7) — (세션, n) 유일, 버리지 않은 행에만 거는 (세션, 이름, 버전) 유일,
 * 목록(html 없음)·묶기·버리기가 NULL 행만 건드리는지, 세션 삭제 CASCADE.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewMockupRepositoryIntegrationTest {

    @Autowired private InterviewMockupRepository repo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long sid;

    @BeforeEach
    void seed() {
        sessionRepo.deleteAll();   // interview_mockup은 CASCADE
        sid = newSession();
    }

    private Long newSession() {
        return sessionRepo.save(InterviewSession.create("hamonsoft/netis-backend", "main",
                "목업", "목업 시험", "user1", List.of(), "claude-opus-5-5", "high")).getId();
    }

    private InterviewMockup save(Long session, int n, String name, int version) {
        return repo.saveAndFlush(InterviewMockup.create(session, n, name, version, "제목 " + n, null, "<p>" + n + "</p>"));
    }

    @Test
    void 같은_n은_이름있는_제약으로_거절() {
        save(sid, 1, "a", 1);

        assertThatThrownBy(() -> save(sid, 1, "b", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_interview_mockup_n");
        Long other = newSession();
        save(other, 1, "a", 1);   // 다른 세션의 n=1은 별개
    }

    @Test
    void 버린_버전은_같은_버전을_다시_쓸_수_있다() {
        save(sid, 1, "a", 1);
        assertThat(repo.discardUnbound(sid)).isEqualTo(1);

        save(sid, 2, "a", 1);   // 버린 v1 뒤에 같은 이름·버전을 다시 저장할 수 있다

        assertThatThrownBy(() -> save(sid, 3, "a", 1))   // 안 버린 v1이 둘이면 거절
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_interview_mockup_name_version");
        save(sid, 3, "a", 2);
        save(sid, 4, "b", 1);   // 이름이 다르면 같은 버전도 된다
    }

    @Test
    void 목록은_버린것_제외_n순() {
        save(sid, 3, "c", 1);
        save(sid, 1, "a", 1);
        save(sid, 2, "b", 1);
        repo.discardUnbound(sid);
        save(sid, 5, "e", 1);
        save(sid, 4, "d", 2);
        save(newSession(), 1, "other", 1);   // 다른 세션은 안 섞인다

        List<InterviewMockupSummary> list = repo.findSummaries(sid);

        assertThat(list).extracting(InterviewMockupSummary::n).containsExactly(4, 5);
        InterviewMockupSummary d = list.get(0);
        assertThat(d.id()).isNotNull();
        assertThat(d.name()).isEqualTo("d");
        assertThat(d.version()).isEqualTo(2);
        assertThat(d.title()).isEqualTo("제목 4");
        assertThat(d.description()).isNull();
        assertThat(d.turnSeq()).isNull();
        assertThat(d.turnKind()).isNull();
        assertThat(d.createdAt()).isNotNull();
    }

    @Test
    void 묶기와_버리기는_NULL_행만() {
        InterviewMockup bound = save(sid, 1, "a", 1);
        assertThat(repo.bindUnbound(sid, 4, "question")).isEqualTo(1);
        InterviewMockup dead = save(sid, 2, "b", 1);
        repo.discardUnbound(sid);
        InterviewMockup x = save(sid, 3, "c", 1);
        InterviewMockup y = save(sid, 4, "d", 1);
        Long other = newSession();
        InterviewMockup foreign = save(other, 1, "z", 1);

        assertThat(repo.bindUnbound(sid, 7, "question")).as("묶인 행·버린 행·다른 세션은 그대로").isEqualTo(2);

        assertThat(repo.findById(bound.getId()).orElseThrow().getTurnSeq()).isEqualTo(4);
        InterviewMockup deadAfter = repo.findById(dead.getId()).orElseThrow();
        assertThat(deadAfter.getTurnSeq()).isNull();
        assertThat(deadAfter.isDiscarded()).isTrue();
        for (InterviewMockup m : List.of(x, y)) {
            InterviewMockup after = repo.findById(m.getId()).orElseThrow();
            assertThat(after.getTurnSeq()).isEqualTo(7);
            assertThat(after.getTurnKind()).isEqualTo("question");
        }
        assertThat(repo.findById(foreign.getId()).orElseThrow().getTurnSeq()).isNull();

        InterviewMockup z = save(sid, 5, "e", 1);
        assertThat(repo.discardUnbound(sid)).as("묶인 행은 못 버린다 — 새로 올라온 한 행만").isEqualTo(1);
        assertThat(repo.findById(z.getId()).orElseThrow().isDiscarded()).isTrue();
        for (InterviewMockup m : List.of(bound, x, y)) {
            assertThat(repo.findById(m.getId()).orElseThrow().isDiscarded()).isFalse();
        }
        assertThat(repo.findById(foreign.getId()).orElseThrow().isDiscarded()).isFalse();
    }

    @Test
    void maxN은_버린_행도_세고_maxVersion은_버린_행을_뺀다() {
        assertThat(repo.maxN(sid)).as("목업이 없으면 0").isZero();
        assertThat(repo.maxVersion(sid, "a")).isZero();

        save(sid, 1, "a", 1);
        save(sid, 2, "a", 2);
        repo.discardUnbound(sid);

        assertThat(repo.maxN(sid)).as("버린 번호는 다시 쓰지 않는다").isEqualTo(2);
        assertThat(repo.maxVersion(sid, "a")).as("버린 버전은 다시 쓸 수 있다").isZero();
        save(sid, 3, "a", 1);
        save(sid, 4, "b", 5);
        assertThat(repo.maxVersion(sid, "a")).isEqualTo(1);
        assertThat(repo.maxVersion(sid, "b")).isEqualTo(5);
        assertThat(repo.maxVersion(newSession(), "a")).isZero();
        assertThat(repo.countBySessionIdAndDiscardedFalse(sid)).isEqualTo(2);
    }

    @Test
    void 단건과_id목록_조회는_버린_행과_다른_세션을_거른다() {
        InterviewMockup a = save(sid, 1, "a", 1);
        repo.discardUnbound(sid);
        InterviewMockup b = save(sid, 2, "b", 1);
        InterviewMockup c = save(sid, 3, "c", 1);
        Long other = newSession();
        InterviewMockup foreign = save(other, 1, "z", 1);

        assertThat(repo.findByIdAndSessionIdAndDiscardedFalse(b.getId(), sid)).isPresent();
        assertThat(repo.findByIdAndSessionIdAndDiscardedFalse(a.getId(), sid)).as("버린 행").isEmpty();
        assertThat(repo.findByIdAndSessionIdAndDiscardedFalse(foreign.getId(), sid)).as("다른 세션").isEmpty();

        assertThat(repo.findByIdInAndDiscardedFalseOrderByNAsc(List.of(c.getId(), a.getId(), b.getId())))
                .extracting(InterviewMockup::getN).containsExactly(2, 3);
    }

    @Test
    void 세션을_지우면_목업도_지워진다() {
        save(sid, 1, "a", 1);
        save(sid, 2, "b", 1);
        Long other = newSession();
        save(other, 1, "a", 1);

        sessionRepo.deleteById(sid);
        sessionRepo.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM com.interview_mockup WHERE session_id = ?", Long.class, sid)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM com.interview_mockup WHERE session_id = ?", Long.class, other)).isEqualTo(1);
    }

    @Test
    void task_두_칸은_NOT_NULL_기본값_빈배열() {
        for (String column : List.of("implementation_mockup_ids", "implementation_mockup_excluded")) {
            Map<String, Object> col = jdbc.queryForMap(
                    "SELECT data_type, is_nullable, column_default FROM information_schema.columns"
                            + " WHERE table_schema = 'com' AND table_name = 'task' AND column_name = ?", column);
            assertThat(col.get("data_type")).as(column).isEqualTo("jsonb");
            assertThat(col.get("is_nullable")).as(column).isEqualTo("NO");
            assertThat((String) col.get("column_default")).as(column).contains("'[]'");
        }

        Long id = taskRepo.save(Task.create("acme/mockup", "main", "목업", "d", "user1", 3, List.of(), "claude-opus-5-5", "high")).getId();
        try {
            // 엔티티 초기값도 빈 목록 — 기본값 그대로 저장된다
            Task fresh = taskRepo.findById(id).orElseThrow();
            assertThat(fresh.getImplementationMockupIds()).isEmpty();
            assertThat(fresh.getImplementationMockupExcluded()).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT jsonb_array_length(implementation_mockup_ids) FROM com.task WHERE id = ?", Integer.class, id)).isZero();

            fresh.setImplementationMockupIds(List.of(3L, 5L));
            fresh.setImplementationMockupExcluded(List.of(2));
            taskRepo.saveAndFlush(fresh);

            assertThat(jdbc.queryForObject(
                    "SELECT jsonb_array_length(implementation_mockup_ids) FROM com.task WHERE id = ?", Integer.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT implementation_mockup_excluded ->> 0 FROM com.task WHERE id = ?", String.class, id)).isEqualTo("2");
        } finally {
            taskRepo.deleteById(id);
        }
    }
}
