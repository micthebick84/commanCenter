package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.time.OffsetDateTime;

/**
 * 인터뷰 세션 목록 행 (경량). status/statusName 이원화는 InterviewResponse와 동일 —
 * status는 한글 dbValue(표시용), statusName은 영문 enum name(프론트 로직용).
 * turns/plan은 싣지 않는다 (목록은 경량 — 상세는 GET /api/interviews/{id}).
 */
public record InterviewSummaryResponse(
        Long id,
        String status,        // 한글 dbValue (사용자 표시)
        String statusName,    // 영문 enum name (InterviewStatus.name())
        String currentPhase,
        String model,
        String effort,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static InterviewSummaryResponse of(InterviewSession s) {
        return new InterviewSummaryResponse(
                s.getId(), s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(),
                s.getModel(), s.getEffort(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
