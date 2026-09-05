package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.ClaudeRateLimit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** GET /api/usage/claude — 프론트 `composables/claudeUsage.ts ClaudeUsageResponse`와 필드명 동기. */
public record ClaudeUsageResponse(List<LimitView> limits) {

    public record LimitView(String limitType, String status, BigDecimal utilization,
                            OffsetDateTime resetsAt, boolean usingOverage,
                            String reportedBy, OffsetDateTime updatedAt) {
        public static LimitView of(ClaudeRateLimit r) {
            return new LimitView(r.getLimitType(), r.getStatus(), r.getUtilization(),
                    r.getResetsAt(), r.isUsingOverage(), r.getReportedBy(), r.getUpdatedAt());
        }
    }
}
