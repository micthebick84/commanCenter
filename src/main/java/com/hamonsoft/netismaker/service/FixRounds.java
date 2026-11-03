package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;

import java.time.OffsetDateTime;

/**
 * 수정 회차 헬퍼 (스펙 2026-11-03 §4·§7) — 회차를 찾고, 닫고, 이력 문구를 만든다. 상태 전이와 저장은 호출자가 맡는다.
 *
 * 회차를 끝내는 모든 길(자동 재배포 완료·실패, 배포중 회수, 수정 중단)은 {@link #close}를 불러 task.active_fix_request_id를 비운다 —
 * 남겨 두면 배포 컨테이너가 GC 보호 목록에 영원히 남는다.
 */
public final class FixRounds {

    private FixRounds() {
    }

    /**
     * 작업이 가리키는 열린 회차의 요청 기록 — 표시가 없으면 null, 기록이 없거나 열린 회차가 아니면(깨진 데이터) 역시 null.
     * 표시만 있고 null이면 깨진 회차다: 호출자는 {@code t.getActiveFixRequestId() != null}로 둘을 가른다.
     */
    public static TaskFixRequest openRequest(TaskFixRequestRepository repo, Task t) {
        if (t.getActiveFixRequestId() == null) return null;
        return repo.findById(t.getActiveFixRequestId()).filter(FixRounds::isOpen).orElse(null);
    }

    /**
     * 열린 회차로 다룰 수 있는 기록 — 승인돼 회차 번호가 정해졌고 아직 닫히지 않았다(outcome 없음). 닫힌 요청을 표시가 가리키면
     * 깨진 데이터다 — 다시 닫으면 그 결과를 덮어쓴다.
     */
    public static boolean isOpen(TaskFixRequest r) {
        return r != null && r.getStatus() == FixRequestStatus.APPROVED && r.getRound() != null && r.getOutcome() == null;
    }

    /** 회차의 구현 방식 — 수정 회차는 직접·리뷰만이라 비어 있으면(있을 수 없다) 작업 방식이 아니라 기본값 리뷰로 본다(§6.2). */
    public static ImplementationMode modeOf(TaskFixRequest r) {
        return r != null && r.getImplementationMode() != null ? r.getImplementationMode() : ImplementationMode.REVIEW;
    }

    /**
     * 회차를 닫는다 — 요청에 결과·닫힌 시각을 넣고 작업의 회차 표시를 비운다. req가 null이면(기록이 없거나 깨진 회차) 표시만 비운다.
     * 요청 저장은 호출자가 한다(관리 엔티티면 커밋 때 반영되지만 다른 진입점처럼 save를 부른다).
     */
    public static void close(Task t, TaskFixRequest req, FixOutcome outcome, OffsetDateTime now) {
        if (req != null) {
            req.setOutcome(outcome);
            req.setClosedAt(now);
        }
        t.setActiveFixRequestId(null);
    }

    /**
     * 작업의 열린 회차를 찾아 닫고 저장한 뒤 닫은 요청을 돌려준다 — 자동 재배포 결과·배포중 회수·수정 중단이 함께 쓴다.
     * 표시가 가리키는 기록이 없거나 열린 회차가 아니면 기록은 건드리지 않고 표시만 지운 뒤 null을 돌려준다.
     */
    public static TaskFixRequest closeActive(TaskFixRequestRepository repo, Task t, FixOutcome outcome) {
        TaskFixRequest r = openRequest(repo, t);
        close(t, r, outcome, OffsetDateTime.now());
        if (r != null) repo.save(r);
        return r;
    }

    /** 이력 문구의 회차 이름 — {@code 수정 2회차}. 회차 번호를 모르면 {@code 수정 회차}. */
    public static String label(TaskFixRequest r) {
        return r == null || r.getRound() == null ? "수정 회차" : "수정 " + r.getRound() + "회차";
    }

    /** 회차를 닫은 전이의 이력 사유 끝에 붙이는 말 — {@code  (수정 2회차 닫힘)}. */
    public static String closedSuffix(TaskFixRequest r) {
        return " (" + label(r) + " 닫힘)";
    }

    /** 이력에 쓰는 짧은 커밋 — 앞 7자. */
    static String shortSha(String sha) {
        String s = sha == null ? "" : sha.strip();
        return s.length() <= 7 ? s : s.substring(0, 7);
    }
}
