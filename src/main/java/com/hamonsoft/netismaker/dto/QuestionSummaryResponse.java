package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 질문 목록 항목. InterviewSummaryResponse에는 title/레포/요청자/비용이 없어 별도 DTO. */
public record QuestionSummaryResponse(
        Long id,
        String title,
        String githubRepo,
        String githubBranch,
        String repoAlias,
        String requesterId,
        String status,        // 한글 dbValue (표시용)
        String statusName,    // 영문 enum name (로직용)
        String model,
        String effort,
        BigDecimal totalCostUsd,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static QuestionSummaryResponse of(InterviewSession s) {
        return new QuestionSummaryResponse(
                s.getId(), s.getTitle(), s.getGithubRepo(), s.getGithubBranch(), s.getRepoAlias(),
                s.getRequesterId(), s.getStatus().dbValue(), s.getStatus().name(),
                s.getModel(), s.getEffort(), s.getTotalCostUsd(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
