package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskFixRequest;

import java.time.OffsetDateTime;

/**
 * 수정 요청 기록 한 건 (스펙 2026-11-03 §8). status는 PENDING|APPROVED|REJECTED|WITHDRAWN, implementationMode는
 * DIRECT|REVIEW(승인 전 null), outcome은 DEPLOYED|DEPLOY_FAILED|ABORTED|NO_CHANGE|SYNCED(열려 있는 동안 null), kind는 FIX|BASE_SYNC. round는 승인 때 확정.
 */
public record FixRequestView(
        Long id,
        Integer round,
        String status,
        String implementationMode,
        String body,
        String requestedBy,
        OffsetDateTime requestedAt,
        String decidedBy,
        OffsetDateTime decidedAt,
        String decisionReason,
        int attempt,
        String outcome,
        OffsetDateTime closedAt,
        String kind
) {
    public static FixRequestView of(TaskFixRequest r) {
        return new FixRequestView(
                r.getId(),
                r.getRound(),
                r.getStatus().name(),
                r.getImplementationMode() == null ? null : r.getImplementationMode().name(),
                r.getBody(),
                r.getRequestedBy(),
                r.getRequestedAt(),
                r.getDecidedBy(),
                r.getDecidedAt(),
                r.getDecisionReason(),
                r.getAttempt(),
                r.getOutcome() == null ? null : r.getOutcome().name(),
                r.getClosedAt(),
                r.getKind().name());
    }
}
