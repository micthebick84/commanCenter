package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.time.OffsetDateTime;
import java.util.List;

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
        OffsetDateTime updatedAt,
        /** 세션 DB 연결 id — 승인 다이얼로그가 마지막 설정을 프리필할 때 쓴다 (스펙 2026-10-14 §7). */
        List<Long> dbConnectionIds,
        /**
         * 그 세션의 인터뷰 방식 — false면 간단 인터뷰(superpowers 끔). 승인 다이얼로그가 지난 세션 값을 프리필할 때 쓴다
         * (설계 2026-11-03-interview-superpowers-toggle §4).
         */
        Boolean superpowersEnabled
) {
    public static InterviewSummaryResponse of(InterviewSession s) {
        return new InterviewSummaryResponse(
                s.getId(), s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(),
                s.getModel(), s.getEffort(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getDbConnectionIds() == null ? List.of() : List.copyOf(s.getDbConnectionIds()),
                s.isSuperpowersEnabled());
    }
}
