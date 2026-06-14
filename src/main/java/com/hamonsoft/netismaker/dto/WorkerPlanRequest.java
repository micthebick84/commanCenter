package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;

/** 워커가 writing-plans 완료 시 보고하는 산출물. */
public record WorkerPlanRequest(
        String designMarkdown,
        String planMarkdown,
        String planJson,
        BigDecimal costUsd,
        Long durationMs
) {}
