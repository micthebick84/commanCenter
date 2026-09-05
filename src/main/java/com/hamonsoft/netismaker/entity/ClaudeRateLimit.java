package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Claude Code 구독 사용량 스냅샷 — 인터뷰 서비스가 SDK rate_limit_event를 보고할 때마다 limit_type별로 갱신
 * (스펙 2026-09-05 §4.1). 구독 계정 1개를 전체가 공유하므로 계정 키 없이 limit_type이 PK다.
 * 쓰기는 ClaudeUsageService.record만.
 */
@Entity
@Table(name = "claude_rate_limit", schema = "com")
@Getter
@Setter
@NoArgsConstructor
public class ClaudeRateLimit {

    /** five_hour | seven_day | seven_day_opus | seven_day_sonnet | overage (SDKRateLimitInfo.rateLimitType). */
    @Id
    @Column(name = "limit_type", length = 20)
    private String limitType;

    /** allowed | allowed_warning | rejected. */
    @Column(nullable = false, length = 20)
    private String status;

    /** 0..1 분수 (러너가 정규화). */
    @Column(nullable = false)
    private BigDecimal utilization = BigDecimal.ZERO;

    @Column(name = "resets_at")
    private OffsetDateTime resetsAt;

    @Column(name = "is_using_overage", nullable = false)
    private boolean usingOverage;

    /** 보고한 인터뷰 서비스 WORKER_ID. */
    @Column(name = "reported_by", nullable = false, length = 50)
    private String reportedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
