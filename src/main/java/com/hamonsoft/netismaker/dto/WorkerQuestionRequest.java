package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;

/**
 * 워커가 보고하는 질문 턴. kind: question | design | gate | note.
 * contextTokens/contextWindow는 스펙 2026-09-05 §4.2 — 구버전 러너는 안 보내므로 null 허용.
 */
public record WorkerQuestionRequest(
        String content,
        String claudeSessionId,
        String kind,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens,
        /** 마지막 최상위 assistant 메시지의 input+cache_creation+cache_read. null = 미보고. */
        Long contextTokens,
        /** 주 모델의 컨텍스트 창 크기. null = 미보고. */
        Long contextWindow
) {}
