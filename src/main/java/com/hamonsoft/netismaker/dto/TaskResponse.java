package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record TaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        TaskStatus status,
        String statusLabel,
        String requesterId,
        int retryCount,
        int maxRetry,
        String failureReason,
        List<TaskMcpSpec> mcpsExtra,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        AnalysisView analysis,
        ImplementationView implementation
) {
    public record AnalysisView(
            String markdownResult,
            String subtasksJson,
            Long durationMs,
            boolean approved,
            String approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime completedAt
    ) {}

    /** PR_CREATED 또는 IMPLEMENTATION_FAILED 이후에만 의미 있음. null은 아직 구현 단계 진입 전. */
    public record ImplementationView(
            String prUrl,
            Integer prNumber,
            String headBranch,
            String headSha,
            String implementationLog
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
        // implementation_log는 List 뷰에서 무겁고 민감하니 detail/요약 모두 노출.
        // (Phase 1: 풀로 노출. Phase 2에 List에서 prune 가능.)
        ImplementationView iv = (t.getPrUrl() == null && t.getImplementationLog() == null) ? null
                : new ImplementationView(
                        t.getPrUrl(),
                        t.getPrNumber(),
                        t.getHeadBranch(),
                        t.getHeadSha(),
                        t.getImplementationLog()
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
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                av,
                iv
        );
    }
}
