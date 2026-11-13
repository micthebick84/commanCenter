package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 배포 뒤 수정 요청 한 건 (스펙 2026-11-03 §4·§6·§8). 작업과는 task_id 컬럼으로만 잇는다(연관 매핑 없음, 삭제는 DB CASCADE).
 * 상태 전이·회차 확정은 서비스 레이어가 맡고 이 엔티티는 값만 든다.
 */
@Entity
@Table(name = "task_fix_request", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskFixRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 수정 회차 번호 — 승인 때 확정한다(승인 전·반려·철회는 null). 1부터. */
    private Integer round;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FixRequestStatus status;

    /** 이 회차의 구현 방식 — 승인 때 정한다(스펙 §6.2). 그 전에는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "implementation_mode", length = 10)
    private ImplementationMode implementationMode;

    /** 요청 시점의 작업 상태 — 반려·철회 때 되돌아갈 직전 상태. */
    @Column(name = "from_status", nullable = false, length = 30)
    @Convert(converter = TaskStatusConverter.class)
    private TaskStatus fromStatus;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /** 회차 안의 구현 시도 번호 — 구현 재시도마다 +1, 브랜치는 그대로(스펙 §7). */
    @Column(nullable = false)
    private int attempt = 1;

    /** 회차가 닫힌 결과. 열려 있는 동안(승인 전 포함)은 null. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FixOutcome outcome;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** 회차 종류 — 기존 행·수정 요청은 FIX, 기준 브랜치 반영은 BASE_SYNC(스펙 2026-11-13). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FixRequestKind kind = FixRequestKind.FIX;

    /** 등록자·관리자의 새 수정 요청 — 승인 대기 상태로 만든다. 회차·구현 방식은 승인 때 채운다. */
    public static TaskFixRequest pending(Long taskId, String body, TaskStatus fromStatus, String requestedBy) {
        TaskFixRequest r = new TaskFixRequest();
        r.taskId = taskId;
        r.body = body;
        r.status = FixRequestStatus.PENDING;
        r.fromStatus = fromStatus;
        r.requestedBy = requestedBy;
        r.requestedAt = OffsetDateTime.now();
        return r;
    }

    /**
     * 기준 브랜치 반영 회차 — 관리자가 종료 승인 때 바로 연다(승인 단계 없음). 직접 구현, 승인 상태로 만든다.
     * 회차 번호는 호출자가 정한 값, 본문은 고정 문구.
     */
    public static TaskFixRequest baseSync(Long taskId, int round, String baseBranch, TaskStatus fromStatus, String adminId) {
        TaskFixRequest r = new TaskFixRequest();
        r.taskId = taskId;
        r.kind = FixRequestKind.BASE_SYNC;
        r.round = round;
        r.body = "기준 브랜치 " + baseBranch + " 반영";
        r.status = FixRequestStatus.APPROVED;
        r.implementationMode = ImplementationMode.DIRECT;
        r.fromStatus = fromStatus;
        r.requestedBy = adminId;
        r.requestedAt = OffsetDateTime.now();
        r.decidedBy = adminId;
        r.decidedAt = r.requestedAt;
        return r;
    }
}
