package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.CheckoutDir;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.Kind;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.Policy;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.SessionRow;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 체크아웃 폴더 정리 판정(순수 함수) — 스펙 2026-10-22 §6. 파일시스템·DB 없이 돈다. */
class InterviewCheckoutCleanupPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:50:00Z");
    private static final Policy POLICY = new Policy(Duration.ofDays(7));
    private static final Path ROOT = Path.of("root");
    private static final Instant ANCIENT = NOW.minus(Duration.ofDays(400));

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(days)), ZoneOffset.ofHours(9));
    }

    private static CheckoutDir target(long id, Instant mtime) {
        return new CheckoutDir(ROOT.resolve("owner/repo/session-" + id), Kind.TARGET, id, mtime);
    }

    private static CheckoutDir refs(long id, Instant mtime) {
        return new CheckoutDir(ROOT.resolve("_refs/" + id), Kind.REFS, id, mtime);
    }

    private static CheckoutDir dir(Kind kind, long id, Instant mtime) {
        return kind == Kind.TARGET ? target(id, mtime) : refs(id, mtime);
    }

    private static Verdict decide(CheckoutDir d, Map<Long, SessionRow> rows, Long maxId) {
        return InterviewCheckoutCleanupPlanner.decide(d, rows, maxId, NOW, POLICY);
    }

    private static Verdict decide(Kind kind, SessionRow row) {
        return decide(dir(kind, 5, ANCIENT), Map.of(5L, row), 1000L);
    }

    // ── 끝난 세션 ──────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class, names = {"REGISTERED", "CANCELLED", "EXPIRED", "FAILED"})
    void closed_and_older_than_retention_is_deleted(InterviewStatus st) {
        for (Kind kind : Kind.values()) {
            assertThat(decide(kind, new SessionRow(st, daysAgo(8)))).isEqualTo(Verdict.DELETE_CLOSED);
        }
    }

    @Test
    void closed_within_retention_is_kept() {
        for (Kind kind : Kind.values()) {
            assertThat(decide(kind, new SessionRow(InterviewStatus.CANCELLED, daysAgo(6))))
                    .isEqualTo(Verdict.KEEP_RETENTION);
        }
    }

    @Test
    void retention_boundary_is_exclusive() {
        // 정확히 7일은 아직 "보다 오래됨"이 아니다 — 1초라도 넘어야 지운다
        OffsetDateTime exactly = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(7)), ZoneOffset.UTC);
        assertThat(decide(Kind.TARGET, new SessionRow(InterviewStatus.EXPIRED, exactly)))
                .isEqualTo(Verdict.KEEP_RETENTION);
        assertThat(decide(Kind.TARGET, new SessionRow(InterviewStatus.EXPIRED, exactly.minusSeconds(1))))
                .isEqualTo(Verdict.DELETE_CLOSED);
    }

    @Test
    void a_closed_row_without_last_activity_is_kept() {
        // 모르면 안 지운다 (DB 컬럼은 NOT NULL이라 실제로는 오지 않는 값)
        assertThat(decide(Kind.TARGET, new SessionRow(InterviewStatus.CANCELLED, null)))
                .isEqualTo(Verdict.KEEP_RETENTION);
    }

    @Test
    void the_folder_age_does_not_matter_when_the_session_row_exists() {
        // 행이 있으면 폴더 mtime이 아니라 last_activity_at이 기준이다
        CheckoutDir freshFolder = target(5, NOW);
        assertThat(decide(freshFolder, Map.of(5L, new SessionRow(InterviewStatus.FAILED, daysAgo(30))), 1000L))
                .isEqualTo(Verdict.DELETE_CLOSED);
        CheckoutDir ancientFolder = target(5, Instant.EPOCH);
        assertThat(decide(ancientFolder, Map.of(5L, new SessionRow(InterviewStatus.FAILED, daysAgo(1))), 1000L))
                .isEqualTo(Verdict.KEEP_RETENTION);
    }

    // ── 진행 중 세션 ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class, names = {"QUEUED", "RUNNING", "AWAITING_INPUT", "PLAN_READY"})
    void keeps_open_sessions_even_when_old(InterviewStatus st) {
        for (Kind kind : Kind.values()) {
            assertThat(decide(kind, new SessionRow(st, daysAgo(30)))).isEqualTo(Verdict.KEEP_OPEN);
        }
    }

    @Test
    void closed_states_are_exactly_the_terminal_ones() {
        // 이 넷에서 나가는 전이가 없다(InterviewService) — 진행 중 4개는 절대 넣지 않는다
        assertThat(InterviewCheckoutCleanupPlanner.CLOSED).isEqualTo(EnumSet.of(
                InterviewStatus.REGISTERED, InterviewStatus.CANCELLED, InterviewStatus.EXPIRED, InterviewStatus.FAILED));
    }

    @Test
    void every_status_is_either_closed_or_kept_open() {
        // 상태가 새로 생겨도(= CLOSED에 없으면) 유지 쪽으로 기운다
        for (InterviewStatus st : InterviewStatus.values()) {
            Verdict v = decide(Kind.TARGET, new SessionRow(st, daysAgo(365)));
            assertThat(v).isEqualTo(InterviewCheckoutCleanupPlanner.CLOSED.contains(st)
                    ? Verdict.DELETE_CLOSED : Verdict.KEEP_OPEN);
        }
    }

    // ── 고아(행 없음) ──────────────────────────────────────

    @Test
    void orphan_is_deleted_only_when_old_and_id_not_above_max() {
        Instant old = NOW.minus(Duration.ofDays(8));
        Instant recent = NOW.minus(Duration.ofDays(1));
        for (Kind kind : Kind.values()) {
            assertThat(decide(dir(kind, 50, old), Map.of(), 100L)).isEqualTo(Verdict.DELETE_ORPHAN);
            assertThat(decide(dir(kind, 100, old), Map.of(), 100L)).isEqualTo(Verdict.DELETE_ORPHAN);   // id == max
            assertThat(decide(dir(kind, 50, recent), Map.of(), 100L)).isEqualTo(Verdict.KEEP_ORPHAN_RECENT);
            assertThat(decide(dir(kind, 101, old), Map.of(), 100L)).isEqualTo(Verdict.KEEP_ORPHAN_UNALLOCATED);
        }
    }

    @Test
    void orphan_boundaries_fail_closed() {
        // 정확히 보존 기간만큼 된 폴더는 아직 지우지 않는다
        assertThat(decide(target(50, NOW.minus(Duration.ofDays(7))), Map.of(), 100L))
                .isEqualTo(Verdict.KEEP_ORPHAN_RECENT);
        // 수정 시각을 모르면 유지
        assertThat(decide(target(50, null), Map.of(), 100L)).isEqualTo(Verdict.KEEP_ORPHAN_RECENT);
        // 빈 테이블(최대 id 없음) = 이 DB가 발급한 id가 없다 → 다른 DB에 붙은 것일 수 있어 전부 유지
        assertThat(decide(target(1, ANCIENT), Map.of(), null)).isEqualTo(Verdict.KEEP_ORPHAN_UNALLOCATED);
        // 판정에 쓰인 행은 session id로만 찾는다 — 다른 세션의 행이 있어도 이 폴더는 고아
        assertThat(decide(target(50, ANCIENT), Map.of(51L, new SessionRow(InterviewStatus.RUNNING, daysAgo(1))), 100L))
                .isEqualTo(Verdict.DELETE_ORPHAN);
    }

    // ── 대상과 참조는 같은 세션 행으로 판정한다 ────────────

    @Test
    void target_and_refs_of_the_same_session_get_the_same_verdict() {
        Map<Long, SessionRow> rows = Map.of(
                1L, new SessionRow(InterviewStatus.CANCELLED, daysAgo(10)),
                2L, new SessionRow(InterviewStatus.CANCELLED, daysAgo(2)),
                3L, new SessionRow(InterviewStatus.AWAITING_INPUT, daysAgo(60)));
        for (long id : new long[]{1, 2, 3, 4}) {
            Verdict t = decide(target(id, ANCIENT), rows, 100L);
            Verdict r = decide(refs(id, ANCIENT), rows, 100L);
            assertThat(r).as("session %d", id).isEqualTo(t);
        }
        assertThat(decide(refs(1, ANCIENT), rows, 100L)).isEqualTo(Verdict.DELETE_CLOSED);
        assertThat(decide(refs(2, ANCIENT), rows, 100L)).isEqualTo(Verdict.KEEP_RETENTION);
        assertThat(decide(refs(3, ANCIENT), rows, 100L)).isEqualTo(Verdict.KEEP_OPEN);
        assertThat(decide(refs(4, ANCIENT), rows, 100L)).isEqualTo(Verdict.DELETE_ORPHAN);
    }

    @Test
    void only_delete_verdicts_delete_and_every_verdict_has_a_label() {
        for (Verdict v : Verdict.values()) {
            assertThat(v.delete()).as(v.name()).isEqualTo(v.name().startsWith("DELETE_"));
            assertThat(v.label()).as(v.name()).isNotBlank();
        }
        assertThat(Verdict.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "DELETE_CLOSED", "DELETE_ORPHAN", "KEEP_OPEN", "KEEP_RETENTION",
                "KEEP_ORPHAN_RECENT", "KEEP_ORPHAN_UNALLOCATED");
    }

    // ── 이름 규칙 ──────────────────────────────────────────

    @Test
    void parses_canonical_target_and_refs_names() {
        Path t = ROOT.resolve("_gitlab/g+p/session-12");
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(t, NOW)).hasValueSatisfying(d -> {
            assertThat(d.kind()).isEqualTo(Kind.TARGET);
            assertThat(d.sessionId()).isEqualTo(12L);
            assertThat(d.path()).isEqualTo(t);
            assertThat(d.modifiedAt()).isEqualTo(NOW);
        });
        Path r = ROOT.resolve("_refs/12");
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(r, NOW)).hasValueSatisfying(d -> {
            assertThat(d.kind()).isEqualTo(Kind.REFS);
            assertThat(d.sessionId()).isEqualTo(12L);
            assertThat(d.path()).isEqualTo(r);
            assertThat(d.modifiedAt()).isEqualTo(NOW);
        });
        // 18자리까지는 long에 들어간다
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(ROOT.resolve("o/r/session-999999999999999999"), NOW))
                .isPresent();
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("_refs/999999999999999999"), NOW))
                .isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session-007", "session-1x", "abc", "0", "session-0", "session-", "session--1", "session-+1",
            "Session-1", "sessions-1", "session-1.bak", " session-1", "5", "session-1/..", "..", ".git",
            "session-1234567890123456789"})   // 마지막: 19자리 — long 오버플로 방지
    void parse_ignores_non_canonical_names(String name) {
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(ROOT.resolve("owner/repo").resolve(name), NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"007", "1x", "abc", "0", "-1", "+1", "1.bak", " 1", "session-1", "..", ".git",
            "1234567890123456789"})   // 마지막: 19자리
    void parse_refs_ignores_non_canonical_names(String name) {
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("_refs").resolve(name), NOW)).isEmpty();
    }

    @Test
    void refs_are_recognized_only_directly_under_the_refs_folder() {
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("owner/5"), NOW)).isEmpty();
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("_refs/5/12"), NOW)).isEmpty();   // 참조 폴더 안
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("_refsx/5"), NOW)).isEmpty();
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(Path.of("5"), NOW)).isEmpty();   // 부모 없음
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(null, NOW)).isEmpty();
    }

    @Test
    void a_refs_style_name_is_not_a_target_and_vice_versa() {
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(ROOT.resolve("_refs/5"), NOW)).isEmpty();
        assertThat(InterviewCheckoutCleanupPlanner.parseRefs(ROOT.resolve("_refs/session-5"), NOW)).isEmpty();
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(null, NOW)).isEmpty();
        assertThat(InterviewCheckoutCleanupPlanner.parseTarget(Path.of("/"), NOW)).isEmpty();   // 이름 없는 경로
    }
}
