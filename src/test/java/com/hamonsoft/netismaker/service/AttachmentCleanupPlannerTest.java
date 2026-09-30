package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Owner;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.OwnerDir;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Owners;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Policy;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.SessionRow;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.TaskRow;
import com.hamonsoft.netismaker.service.AttachmentCleanupPlanner.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentCleanupPlannerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T03:30:00Z");
    private static final Policy POLICY = new Policy(Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(24));

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(days)), ZoneOffset.ofHours(9));
    }

    private static OwnerDir taskDir(long id) {
        return new OwnerDir("task-" + id, Owner.TASK, id, NOW.minus(Duration.ofDays(400)));
    }

    private static OwnerDir questionDir(long id) {
        return new OwnerDir("question-" + id, Owner.QUESTION, id, NOW.minus(Duration.ofDays(400)));
    }

    private static Owners tasks(Map<Long, TaskRow> rows) {
        return new Owners(rows, 1000L, Map.of(), 1000L);
    }

    private static Owners sessions(Map<Long, SessionRow> rows) {
        return new Owners(Map.of(), 1000L, rows, 1000L);
    }

    private static Verdict decide(OwnerDir dir, Owners owners) {
        return AttachmentCleanupPlanner.decide(dir, owners, NOW, POLICY);
    }

    // ── parse ──────────────────────────────────────────────

    @Test
    void 정규형_이름만_소유_디렉터리로_인식한다() {
        assertThat(AttachmentCleanupPlanner.parse("task-12", NOW))
                .hasValueSatisfying(d -> {
                    assertThat(d.owner()).isEqualTo(Owner.TASK);
                    assertThat(d.id()).isEqualTo(12L);
                    assertThat(d.modifiedAt()).isEqualTo(NOW);
                });
        assertThat(AttachmentCleanupPlanner.parse("question-7", NOW))
                .hasValueSatisfying(d -> {
                    assertThat(d.owner()).isEqualTo(Owner.QUESTION);
                    assertThat(d.id()).isEqualTo(7L);
                });
        assertThat(AttachmentCleanupPlanner.parse("task-999999999999999999", NOW)).isPresent();   // 18자리
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-0", "task-007", "task-", "task-12a", "task--1", "task-+1", "Task-1",
            "tasks-1", "task-1.bak", " task-1", "question-", "question-x", "question-1/..", "..",
            ".DS_Store", "interview-3", "task-1234567890123456789"})   // 마지막: 19자리 — long 오버플로 방지
    void 정규형이_아닌_이름은_대상이_아니다(String name) {
        assertThat(AttachmentCleanupPlanner.parse(name, NOW)).isEmpty();
    }

    // ── task-{id} ──────────────────────────────────────────

    @Test
    void 삭제되지_않은_작업은_아주_오래돼도_유지() {
        OwnerDir ancient = new OwnerDir("task-1", Owner.TASK, 1, Instant.EPOCH);
        assertThat(decide(ancient, tasks(Map.of(1L, new TaskRow(null))))).isEqualTo(Verdict.KEEP_TASK_ACTIVE);
    }

    @Test
    void 삭제된_작업은_보존_기간이_지나야_삭제() {
        assertThat(decide(taskDir(1), tasks(Map.of(1L, new TaskRow(daysAgo(31))))))
                .isEqualTo(Verdict.DELETE_TASK_DELETED);
        assertThat(decide(taskDir(1), tasks(Map.of(1L, new TaskRow(daysAgo(29))))))
                .isEqualTo(Verdict.KEEP_TASK_RETENTION);
        // 경계: 정확히 30일은 아직 "보다 오래됨"이 아니다
        assertThat(decide(taskDir(1), tasks(Map.of(1L, new TaskRow(daysAgo(30))))))
                .isEqualTo(Verdict.KEEP_TASK_RETENTION);
    }

    @Test
    void 작업_판정은_디렉터리_mtime이_아니라_deleted_at_기준() {
        OwnerDir freshDir = new OwnerDir("task-1", Owner.TASK, 1, NOW);
        assertThat(decide(freshDir, tasks(Map.of(1L, new TaskRow(daysAgo(31))))))
                .isEqualTo(Verdict.DELETE_TASK_DELETED);
    }

    // ── question-{sid} ─────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class, names = {"CANCELLED", "EXPIRED", "FAILED"})
    void 종결된_질문은_보존_기간이_지나야_삭제(InterviewStatus closed) {
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.QUESTION, closed, daysAgo(91))))))
                .isEqualTo(Verdict.DELETE_QUESTION_CLOSED);
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.QUESTION, closed, daysAgo(89))))))
                .isEqualTo(Verdict.KEEP_QUESTION_RETENTION);
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.QUESTION, closed, daysAgo(90))))))
                .isEqualTo(Verdict.KEEP_QUESTION_RETENTION);
    }

    /** 진행 중·답변 완료(AWAITING_INPUT) 등 비종결 상태는 활동이 아무리 오래전이어도 절대 삭제하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class, names = {"CANCELLED", "EXPIRED", "FAILED"},
            mode = EnumSource.Mode.EXCLUDE)
    void 비종결_질문은_아무리_오래돼도_유지(InterviewStatus open) {
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.QUESTION, open, daysAgo(3650))))))
                .isEqualTo(Verdict.KEEP_QUESTION_OPEN);
    }

    @Test
    void 질문_세션이_아닌_행이면_유지() {
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.INTERVIEW, InterviewStatus.CANCELLED, daysAgo(365))))))
                .isEqualTo(Verdict.KEEP_NOT_QUESTION);
    }

    @Test
    void 마지막_활동_시각이_없으면_판정_불가라_유지() {
        assertThat(decide(questionDir(5), sessions(Map.of(5L,
                new SessionRow(InterviewKind.QUESTION, InterviewStatus.EXPIRED, null)))))
                .isEqualTo(Verdict.KEEP_QUESTION_RETENTION);
    }

    // ── 고아(행 없음) ──────────────────────────────────────

    @Test
    void 고아는_유예가_지나고_발급된_id일_때만_삭제() {
        Owners none = new Owners(Map.of(), 50L, Map.of(), 50L);
        OwnerDir oldTask = new OwnerDir("task-7", Owner.TASK, 7, NOW.minus(Duration.ofHours(25)));
        OwnerDir oldQuestion = new OwnerDir("question-7", Owner.QUESTION, 7, NOW.minus(Duration.ofHours(25)));
        assertThat(decide(oldTask, none)).isEqualTo(Verdict.DELETE_ORPHAN);
        assertThat(decide(oldQuestion, none)).isEqualTo(Verdict.DELETE_ORPHAN);

        // 방금 생성 중(등록 tx 미커밋)일 수 있는 디렉터리
        OwnerDir fresh = new OwnerDir("task-7", Owner.TASK, 7, NOW.minus(Duration.ofHours(23)));
        assertThat(decide(fresh, none)).isEqualTo(Verdict.KEEP_ORPHAN_GRACE);
        OwnerDir exactlyGrace = new OwnerDir("task-7", Owner.TASK, 7, NOW.minus(Duration.ofHours(24)));
        assertThat(decide(exactlyGrace, none)).isEqualTo(Verdict.KEEP_ORPHAN_GRACE);
        OwnerDir unknownMtime = new OwnerDir("task-7", Owner.TASK, 7, null);
        assertThat(decide(unknownMtime, none)).isEqualTo(Verdict.KEEP_ORPHAN_GRACE);
    }

    @Test
    void 테이블_최대_id보다_큰_고아나_빈_테이블이면_유지() {
        OwnerDir old = new OwnerDir("task-51", Owner.TASK, 51, Instant.EPOCH);
        assertThat(decide(old, new Owners(Map.of(), 50L, Map.of(), 50L))).isEqualTo(Verdict.KEEP_ORPHAN_UNALLOCATED);
        // 빈 DB(엉뚱한 datasource 등)에 붙었을 때 전부 고아로 보여 대량 삭제되는 사고 방지
        assertThat(decide(old, new Owners(Map.of(), null, Map.of(), null))).isEqualTo(Verdict.KEEP_ORPHAN_UNALLOCATED);
        OwnerDir oldQuestion = new OwnerDir("question-3", Owner.QUESTION, 3, Instant.EPOCH);
        assertThat(decide(oldQuestion, new Owners(Map.of(), 50L, Map.of(), null)))
                .isEqualTo(Verdict.KEEP_ORPHAN_UNALLOCATED);
    }

    @Test
    void 작업과_질문의_id_공간은_섞이지_않는다() {
        // task-5 판정은 세션 5와 무관 — 작업 행이 없으면 고아
        Owners onlySession = new Owners(Map.of(), 50L,
                Map.of(5L, new SessionRow(InterviewKind.QUESTION, InterviewStatus.RUNNING, daysAgo(1))), 50L);
        assertThat(decide(taskDir(5), onlySession)).isEqualTo(Verdict.DELETE_ORPHAN);
        // question-5 판정은 작업 5와 무관
        Owners onlyTask = new Owners(Map.of(5L, new TaskRow(null)), 50L, Map.of(), 50L);
        assertThat(decide(questionDir(5), onlyTask)).isEqualTo(Verdict.DELETE_ORPHAN);
    }

    @Test
    void plan은_입력_순서대로_판정을_돌려준다() {
        Owners owners = new Owners(Map.of(1L, new TaskRow(null), 2L, new TaskRow(daysAgo(40))), 10L,
                Map.of(3L, new SessionRow(InterviewKind.QUESTION, InterviewStatus.AWAITING_INPUT, daysAgo(200))), 10L);
        var decisions = AttachmentCleanupPlanner.plan(List.of(taskDir(1), taskDir(2), questionDir(3)), owners, NOW, POLICY);
        assertThat(decisions).extracting(d -> d.verdict())
                .containsExactly(Verdict.KEEP_TASK_ACTIVE, Verdict.DELETE_TASK_DELETED, Verdict.KEEP_QUESTION_OPEN);
        assertThat(decisions).extracting(d -> d.verdict().delete()).containsExactly(false, true, false);
    }
}
