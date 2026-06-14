package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 인터뷰 산출물 (1 session : 1 plan). TaskAnalysis 미러.
 * writing-plans 완료 시 design+plan harvest 결과를 영속화. 등록 시 TaskAnalysis로 프리필.
 */
@Entity
@Table(name = "interview_plan", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewPlan {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "design_markdown", columnDefinition = "TEXT")
    private String designMarkdown;

    @Column(name = "plan_markdown", columnDefinition = "TEXT")
    private String planMarkdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", nullable = false, columnDefinition = "jsonb")
    private String planJson;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "total_cost_usd")
    private BigDecimal totalCostUsd;

    @Column(name = "completed_at", nullable = false)
    private OffsetDateTime completedAt;

    public static InterviewPlan create(long sessionId, String designMarkdown, String planMarkdown,
                                       String planJson, Long durationMs, BigDecimal totalCostUsd) {
        InterviewPlan p = new InterviewPlan();
        p.sessionId = sessionId;
        p.designMarkdown = designMarkdown;
        p.planMarkdown = planMarkdown;
        p.planJson = planJson == null ? "[]" : planJson;
        p.durationMs = durationMs;
        p.totalCostUsd = totalCostUsd;
        p.completedAt = OffsetDateTime.now();
        return p;
    }
}
