package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * kind=CLOSE claim에 실리는 노트 재료 (스펙 2026-10-13 §4 ③). API가 DB에서 조립한다.
 * 비어 있을 수 있는 값(플랜·분석·구현 로그·배포 정보)은 null.
 */
public record CloseContext(
        String planMarkdown,
        String analysisMarkdown,
        /** implementation_log의 끝부분(결과 요약이 보통 끝에 있다). */
        String implementationTail,
        String mergeCommitSha,
        String deployUrl,
        OffsetDateTime deployedAt,
        List<HistoryLine> history,
        List<UsageLine> usage
) {
    /** 상태 이력 한 줄 — from/to는 DB 값(한글 상태명), 시간순(오래된 것 먼저). */
    public record HistoryLine(OffsetDateTime at, String from, String to, String actor, String reason) {}

    /** 단계별 사용량 한 줄. */
    public record UsageLine(String stage, BigDecimal costUsd, long inputTokens, long outputTokens) {}
}
