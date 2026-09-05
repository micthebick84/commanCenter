package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Body for POST /worker/usage/rate-limits?workerId=… — 인터뷰 서비스가 정규화한 SDK rate_limit_info
 * (스펙 2026-09-05 §4.3: utilization 0..1, resetsAt ISO-8601 또는 null).
 */
public record WorkerRateLimitRequest(
        @NotBlank @Size(max = 20) String limitType,
        @NotBlank @Size(max = 20) String status,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal utilization,
        OffsetDateTime resetsAt,
        Boolean isUsingOverage
) {}
