package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 첨부 디렉터리 정리 판정 (순수 함수, 파일시스템/DB I/O 없음 → 단위 테스트 용이). DockerGcPlanner 패턴.
 *
 *  task-{id}      : 소유 task가 soft-delete됐고 deleted_at이 보존 기간보다 오래됐을 때만 삭제.
 *                   삭제되지 않은 task는 나이와 무관하게 절대 지우지 않는다(복구 경로가 없어도 보수적으로).
 *  question-{sid} : 소유 세션이 QUESTION이고 종결 상태(취소·만료·실패 — 이후 전이 경로 없음)이며
 *                   last_activity_at(종결 전이가 touch한 시각)이 보존 기간보다 오래됐을 때만 삭제.
 *                   진행 중·답변 완료(AWAITING_INPUT) 세션은 인터뷰 서비스가 attachmentRoot 절대경로로
 *                   읽으므로 절대 건드리지 않는다.
 *  고아(행 없음)  : 등록 트랜잭션이 파일을 쓴 뒤 롤백된 잔재. id가 테이블 최대 id 이하(= 이 DB의 시퀀스가
 *                   실제로 발급했던 id)이고 디렉터리 mtime이 유예 기간보다 오래됐을 때만 삭제 — 진행 중인
 *                   등록(미커밋 행)과의 경합, 빈/엉뚱한 DB에 붙었을 때의 대량 오판정을 막는다.
 *
 * 판정이 애매하면(값 없음 등) 항상 유지 쪽으로 기운다 — 모르면 안 지운다(DockerGcJob의 fail-closed와 같은 취지).
 */
public final class AttachmentCleanupPlanner {

    /** 정규형만 허용 — 선행 0·부호·접미사 붙은 이름은 앱이 만든 디렉터리가 아니다. 18자리로 long 오버플로 차단. */
    private static final Pattern TASK_DIR = Pattern.compile("task-([1-9][0-9]{0,17})");
    private static final Pattern QUESTION_DIR = Pattern.compile("question-([1-9][0-9]{0,17})");

    /**
     * 질문 세션 종결 상태. InterviewService 기준 이 셋에서 나가는 전이가 없다(submitAnswer=AWAITING_INPUT,
     * claim=QUEUED, cancel/fail/expire도 진행 상태에서만). PLAN_READY/REGISTERED는 질문 세션이 도달할 수
     * 없으므로 넣지 않는다 — 들어와도 유지.
     */
    static final Set<InterviewStatus> QUESTION_CLOSED =
            EnumSet.of(InterviewStatus.CANCELLED, InterviewStatus.EXPIRED, InterviewStatus.FAILED);

    private AttachmentCleanupPlanner() {}

    public enum Owner { TASK, QUESTION }

    /** 첨부 루트 바로 아래의 소유 디렉터리 1개. modifiedAt = 디렉터리 자체 mtime(링크 미추적). */
    public record OwnerDir(String name, Owner owner, long id, Instant modifiedAt) {}

    /** 소유 task 행 — deletedAt null이면 살아있는 task. */
    public record TaskRow(OffsetDateTime deletedAt) {}

    /** 소유 세션 행. */
    public record SessionRow(InterviewKind kind, InterviewStatus status, OffsetDateTime lastActivityAt) {}

    /**
     * 스캔된 id들의 DB 스냅샷. 맵에 없는 id = 행 없음. maxTaskId/maxSessionId = 테이블 최대 id(빈 테이블이면 null).
     */
    public record Owners(Map<Long, TaskRow> tasks, Long maxTaskId,
                         Map<Long, SessionRow> sessions, Long maxSessionId) {}

    public record Policy(Duration taskDeletedRetention, Duration questionClosedRetention, Duration orphanGrace) {}

    public enum Verdict {
        DELETE_TASK_DELETED(true, "삭제된 작업 — 보존 기간 경과"),
        DELETE_QUESTION_CLOSED(true, "종료된 질문 — 보존 기간 경과"),
        DELETE_ORPHAN(true, "소유 행 없음(고아) — 유예 기간 경과"),
        KEEP_TASK_ACTIVE(false, "삭제되지 않은 작업"),
        KEEP_TASK_RETENTION(false, "삭제된 작업 — 보존 기간 이내"),
        KEEP_QUESTION_OPEN(false, "진행 중/답변 완료 질문"),
        KEEP_QUESTION_RETENTION(false, "종료된 질문 — 보존 기간 이내"),
        KEEP_NOT_QUESTION(false, "질문 세션이 아닌 행"),
        KEEP_ORPHAN_GRACE(false, "소유 행 없음(고아) — 유예 기간 이내"),
        KEEP_ORPHAN_UNALLOCATED(false, "소유 행 없음 — 테이블 최대 id 초과(미할당 id, 다른 DB 의심)");

        private final boolean delete;
        private final String label;

        Verdict(boolean delete, String label) {
            this.delete = delete;
            this.label = label;
        }

        public boolean delete() { return delete; }

        public String label() { return label; }
    }

    public record Decision(OwnerDir dir, Verdict verdict) {}

    /** 디렉터리 이름이 task-{id}/question-{id} 정규형이면 OwnerDir, 아니면 empty(정리 대상 아님). */
    public static Optional<OwnerDir> parse(String name, Instant modifiedAt) {
        if (name == null) return Optional.empty();
        Matcher m = TASK_DIR.matcher(name);
        if (m.matches()) return Optional.of(new OwnerDir(name, Owner.TASK, Long.parseLong(m.group(1)), modifiedAt));
        m = QUESTION_DIR.matcher(name);
        if (m.matches()) return Optional.of(new OwnerDir(name, Owner.QUESTION, Long.parseLong(m.group(1)), modifiedAt));
        return Optional.empty();
    }

    public static List<Decision> plan(List<OwnerDir> dirs, Owners owners, Instant now, Policy policy) {
        return dirs.stream().map(d -> new Decision(d, decide(d, owners, now, policy))).toList();
    }

    static Verdict decide(OwnerDir dir, Owners owners, Instant now, Policy policy) {
        return switch (dir.owner()) {
            case TASK -> {
                TaskRow row = owners.tasks().get(dir.id());
                if (row == null) yield orphan(dir, owners.maxTaskId(), now, policy);
                if (row.deletedAt() == null) yield Verdict.KEEP_TASK_ACTIVE;
                yield olderThan(row.deletedAt(), now, policy.taskDeletedRetention())
                        ? Verdict.DELETE_TASK_DELETED : Verdict.KEEP_TASK_RETENTION;
            }
            case QUESTION -> {
                SessionRow row = owners.sessions().get(dir.id());
                if (row == null) yield orphan(dir, owners.maxSessionId(), now, policy);
                if (row.kind() != InterviewKind.QUESTION) yield Verdict.KEEP_NOT_QUESTION;
                if (!QUESTION_CLOSED.contains(row.status())) yield Verdict.KEEP_QUESTION_OPEN;
                yield olderThan(row.lastActivityAt(), now, policy.questionClosedRetention())
                        ? Verdict.DELETE_QUESTION_CLOSED : Verdict.KEEP_QUESTION_RETENTION;
            }
        };
    }

    private static Verdict orphan(OwnerDir dir, Long maxId, Instant now, Policy policy) {
        if (maxId == null || dir.id() > maxId) return Verdict.KEEP_ORPHAN_UNALLOCATED;
        if (dir.modifiedAt() == null || !dir.modifiedAt().isBefore(now.minus(policy.orphanGrace()))) {
            return Verdict.KEEP_ORPHAN_GRACE;
        }
        return Verdict.DELETE_ORPHAN;
    }

    /** at이 now - retention보다 엄격히 이전일 때만 true. 값이 없으면 판정 불가 → false(유지). */
    private static boolean olderThan(OffsetDateTime at, Instant now, Duration retention) {
        return at != null && at.toInstant().isBefore(now.minus(retention));
    }
}
