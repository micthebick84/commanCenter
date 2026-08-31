package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;

/** 워커가 보고하는 질문 턴. kind: question | design | gate | note. */
public record WorkerQuestionRequest(
        String content,
        String claudeSessionId,
        String kind,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens
) {}
