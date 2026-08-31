package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 단계별 토큰/비용 누적 (스펙 §3). 쓰기는 전부 TaskStageUsageRepository.accumulate
 * (네이티브 ON CONFLICT upsert) — 이 엔티티는 조회 전용 매핑이다.
 */
@Entity
@Table(name = "task_stage_usage", schema = "com")
@IdClass(TaskStageUsage.Key.class)
@Getter
public class TaskStageUsage {

    public static final String STAGE_INTERVIEW = "INTERVIEW";
    public static final String STAGE_ANALYSIS = "ANALYSIS";
    public static final String STAGE_DESIGN = "DESIGN";
    public static final String STAGE_IMPLEMENTATION = "IMPLEMENTATION";

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Id
    @Column(name = "stage", length = 20)
    private String stage;

    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cache_creation_tokens", nullable = false)
    private long cacheCreationTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 복합 PK. equals/hashCode 필수 (JPA IdClass 계약). */
    public static class Key implements Serializable {
        private Long taskId;
        private String stage;

        public Key() {}
        public Key(Long taskId, String stage) { this.taskId = taskId; this.stage = stage; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return java.util.Objects.equals(taskId, k.taskId)
                    && java.util.Objects.equals(stage, k.stage);
        }
        @Override public int hashCode() { return java.util.Objects.hash(taskId, stage); }
    }
}
