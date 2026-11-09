package com.hamonsoft.netismaker.dto;

import java.time.OffsetDateTime;

/**
 * Claude Code 응답 한 번의 사용량. 내용·도구 입력·cwd·경로 칸은 일부러 없다(스펙 §10).
 * category는 SYMPHONY | DIRECT.
 */
public record ClaudeCodeUsageEvent(
        String eventKey,
        OffsetDateTime occurredAt,
        String category,
        String model,
        String speed,
        String serviceTier,
        String entrypoint,
        String sessionId,
        Boolean sidechain,
        long inputTokens,
        long outputTokens,
        long cacheWrite5mTokens,
        long cacheWrite1hTokens,
        long cacheReadTokens
) {}
