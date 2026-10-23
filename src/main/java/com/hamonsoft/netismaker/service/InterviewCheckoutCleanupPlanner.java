package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인터뷰·질문 체크아웃 폴더 정리 판정 (순수 함수, 파일시스템/DB I/O 없음 → 단위 테스트 용이). AttachmentCleanupPlanner와 같은 모양.
 * 스펙 2026-10-22 §6.
 *
 *  대상  {루트}/{a}/{b}/session-{id} : 그 세션의 작업 폴더.
 *  참조  {루트}/_refs/{id}           : 그 세션의 읽기 전용 참조 repo 폴더들(안에 카탈로그 id별 폴더).
 *  두 종류는 같은 세션 id의 것이므로 같은 세션 행으로 판정한다.
 *
 *  행이 있다   : 끝난 상태(등록됨·취소됨·만료됨·인터뷰실패 — 이후 전이가 없다)이고 last_activity_at(종결 전이가 touch한
 *                시각)이 보존 기간보다 오래됐을 때만 삭제. 진행 중(대기·진행·입력대기·플랜완료)이면 나이와 무관하게 유지.
 *  행이 없다   : 고아. id가 테이블 최대 id 이하(= 이 DB의 시퀀스가 실제로 발급했던 id)이고 폴더 수정 시각이 보존 기간보다
 *                오래됐을 때만 삭제 — 빈/엉뚱한 DB에 붙었을 때의 대량 오판정을 막는다.
 *
 * 판정이 애매하면(값 없음 등) 항상 유지 쪽으로 기운다 — 모르면 안 지운다.
 */
public final class InterviewCheckoutCleanupPlanner {

    /** 정규형만 허용 — 선행 0·부호·접미사 붙은 이름은 앱이 만든 폴더가 아니다. 18자리로 long 오버플로 차단. */
    private static final String ID = "([1-9][0-9]{0,17})";
    private static final Pattern TARGET_NAME = Pattern.compile(Pattern.quote(InterviewWorkPaths.SESSION_DIR_PREFIX) + ID);
    private static final Pattern REFS_NAME = Pattern.compile(ID);

    /**
     * 끝난 세션 상태. InterviewService 기준 이 넷에서 나가는 전이가 없다(재큐는 입력대기에서만, 취소·만료·실패·등록은
     * 진행 상태에서만). 여기 없는 상태는 — 새로 생기더라도 — 진행 중으로 보아 유지한다.
     */
    static final Set<InterviewStatus> CLOSED = EnumSet.of(
            InterviewStatus.REGISTERED, InterviewStatus.CANCELLED, InterviewStatus.EXPIRED, InterviewStatus.FAILED);

    private InterviewCheckoutCleanupPlanner() {}

    public enum Kind { TARGET, REFS }

    /** 정리 후보 폴더 1개. modifiedAt = 폴더 자체 mtime(링크 미추적) — 고아 판정에만 쓴다. */
    public record CheckoutDir(Path path, Kind kind, long sessionId, Instant modifiedAt) {}

    /** 소유 세션 행. */
    public record SessionRow(InterviewStatus status, OffsetDateTime lastActivityAt) {}

    public record Policy(Duration retention) {}

    public enum Verdict {
        DELETE_CLOSED(true, "끝난 세션 — 보존 기간 경과"),
        DELETE_ORPHAN(true, "소유 행 없음(고아) — 보존 기간 경과"),
        KEEP_OPEN(false, "진행 중 세션"),
        KEEP_RETENTION(false, "끝난 세션 — 보존 기간 이내"),
        KEEP_ORPHAN_RECENT(false, "소유 행 없음(고아) — 보존 기간 이내"),
        KEEP_ORPHAN_UNALLOCATED(false, "소유 행 없음 — 최대 id 초과(미할당 id, 다른 DB 의심)");

        private final boolean delete;
        private final String label;

        Verdict(boolean delete, String label) {
            this.delete = delete;
            this.label = label;
        }

        public boolean delete() { return delete; }

        public String label() { return label; }
    }

    /** 폴더 이름이 session-{id} 정규형이면 대상 후보, 아니면 empty(정리 대상 아님). 자리(루트 아래 두 단계)는 호출한 쪽이 보장한다. */
    public static Optional<CheckoutDir> parseTarget(Path dir, Instant modifiedAt) {
        String name = nameOf(dir);
        if (name == null) return Optional.empty();
        Matcher m = TARGET_NAME.matcher(name);
        if (!m.matches()) return Optional.empty();
        return Optional.of(new CheckoutDir(dir, Kind.TARGET, Long.parseLong(m.group(1)), modifiedAt));
    }

    /** `_refs` 바로 아래이고 이름이 {id} 정규형이면 참조 후보, 아니면 empty. */
    public static Optional<CheckoutDir> parseRefs(Path dir, Instant modifiedAt) {
        String name = nameOf(dir);
        if (name == null || !InterviewWorkPaths.REFS_DIR.equals(nameOf(dir.getParent()))) return Optional.empty();
        Matcher m = REFS_NAME.matcher(name);
        if (!m.matches()) return Optional.empty();
        return Optional.of(new CheckoutDir(dir, Kind.REFS, Long.parseLong(m.group(1)), modifiedAt));
    }

    /**
     * @param rows         스캔된 세션 id들의 DB 스냅샷. 맵에 없는 id = 행 없음(고아)
     * @param maxSessionId 테이블 최대 id(빈 테이블이면 null) — 고아 판정 가드
     */
    static Verdict decide(CheckoutDir d, Map<Long, SessionRow> rows, Long maxSessionId, Instant now, Policy p) {
        SessionRow row = rows.get(d.sessionId());
        if (row == null) return orphan(d, maxSessionId, now, p);
        if (!CLOSED.contains(row.status())) return Verdict.KEEP_OPEN;
        return olderThan(row.lastActivityAt(), now, p.retention()) ? Verdict.DELETE_CLOSED : Verdict.KEEP_RETENTION;
    }

    private static Verdict orphan(CheckoutDir d, Long maxSessionId, Instant now, Policy p) {
        if (maxSessionId == null || d.sessionId() > maxSessionId) return Verdict.KEEP_ORPHAN_UNALLOCATED;
        if (d.modifiedAt() == null || !d.modifiedAt().isBefore(now.minus(p.retention()))) return Verdict.KEEP_ORPHAN_RECENT;
        return Verdict.DELETE_ORPHAN;
    }

    /** at이 now - retention보다 엄격히 이전일 때만 true. 값이 없으면 판정 불가 → false(유지). */
    private static boolean olderThan(OffsetDateTime at, Instant now, Duration retention) {
        return at != null && at.toInstant().isBefore(now.minus(retention));
    }

    private static String nameOf(Path p) {
        if (p == null || p.getFileName() == null) return null;
        return p.getFileName().toString();
    }
}
