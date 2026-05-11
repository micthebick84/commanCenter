package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;

import java.time.OffsetDateTime;

public record TaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        TaskStatus status,
        String statusLabel,
        Long requesterId,
        int retryCount,
        int maxRetry,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        AnalysisView analysis
) {
    public record AnalysisView(
            String markdownResult,
            String subtasksJson,
            Long durationMs,
            boolean approved,
            Long approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime completedAt
    ) {}

    public static TaskResponse of(Task t, TaskAnalysis a) {
        AnalysisView av = (a == null) ? null : new AnalysisView(
                a.getMarkdownResult(),
                a.getSubtasksJson(),
                a.getDurationMs(),
                a.isApproved(),
                a.getApprovedBy(),
                a.getApprovedAt(),
                a.getCompletedAt()
        );
        return new TaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getGithubBranch(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getStatus().dbValue(),
                t.getRequesterId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                av
        );
    }
}
