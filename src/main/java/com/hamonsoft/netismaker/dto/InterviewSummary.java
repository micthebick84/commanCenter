package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.time.OffsetDateTime;

/** 활성 인터뷰 목록 항목(경량). status=한글 dbValue(표시), statusName=영문 enum(로직). InterviewResponse와 동일 명명. */
public record InterviewSummary(
        Long id,
        String status,
        String statusName,
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
                s.getStatus().dbValue(),
                s.getStatus().name(),
                s.getTitle(),
                s.getGithubRepo(),
                s.getGithubBranch(),
                s.getCurrentPhase(),
                s.getLastActivityAt(),
                s.getCreatedAt());
    }
}
