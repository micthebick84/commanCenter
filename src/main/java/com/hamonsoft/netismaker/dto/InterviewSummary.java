package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.time.OffsetDateTime;

/** 활성 인터뷰 목록 항목(경량). statusName=영문 enum, statusLabel=한글 dbValue. */
public record InterviewSummary(
        Long id,
        String statusName,
        String statusLabel,
        String title,
        String githubRepo,
        String githubBranch,
        String currentPhase,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt
) {
    public static InterviewSummary of(InterviewSession s) {
        return new InterviewSummary(
                s.getId(),
                s.getStatus().name(),
                s.getStatus().dbValue(),
                s.getTitle(),
                s.getGithubRepo(),
                s.getGithubBranch(),
                s.getCurrentPhase(),
                s.getLastActivityAt(),
                s.getCreatedAt());
    }
}
