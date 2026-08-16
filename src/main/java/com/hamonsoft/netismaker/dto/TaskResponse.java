package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record TaskResponse(
        Long id,
        String githubRepo,
        String repoAlias,
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
        List<EnvVar> envVars,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String model,
        String effort,
        boolean designRequested,
        AnalysisView analysis,
        DesignView design,
        ImplementationView implementation,
        DeploymentView deployment,
        Long interviewSessionId,
        /** 항상 non-null. 목록 엔드포인트는 항상 [] — 실데이터는 상세 응답만 (스펙 §5.2). */
        List<AttachmentView> attachments
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

    /** 디자인 산출물. 상세 조회에서만 non-null (목록에서는 항상 null). */
    public record DesignView(
            String designMarkdown,
            String mockupFilesJson,
            String designProjectId,
            String designUrl,
            int rejectCount,
            String feedbackHistoryJson,
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

    /** DEPLOYED 또는 배포 시도 이후에만 의미 있음. null은 배포 이력 없음. */
    public record DeploymentView(
            String deployUrl,
            Integer deployHostPort,
            String deployImage,
            OffsetDateTime deployedAt,
            String deployLog
    ) {}

    public record AttachmentView(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            OffsetDateTime createdAt
    ) {
        static AttachmentView from(TaskAttachment a) {
            return new AttachmentView(a.getId(), a.getOriginalFilename(),
                    a.getContentType(), a.getSizeBytes(), a.getCreatedAt());
        }
    }

    public static TaskResponse of(Task t, TaskAnalysis a) {
        return of(t, a, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d) {
        return of(t, a, d, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId) {
        return of(t, a, d, interviewSessionId, List.of());
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId,
                                  List<TaskAttachment> attachments) {
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
        boolean hasDeploy = t.getDeployUrl() != null || t.getDeployLog() != null
                || t.getStatus() == TaskStatus.DEPLOYING
                || t.getStatus() == TaskStatus.DEPLOY_PENDING
                || t.getStatus() == TaskStatus.UNDEPLOY_PENDING;
        DeploymentView dv = !hasDeploy ? null : new DeploymentView(
                t.getDeployUrl(),
                t.getDeployHostPort(),
                t.getDeployImage(),
                t.getDeployedAt(),
                t.getDeployLog()
        );
        DesignView designV = (d == null) ? null : new DesignView(
                d.getDesignMarkdown(), d.getMockupFilesJson(), d.getDesignProjectId(),
                d.getDesignUrl(), d.getRejectCount(), d.getFeedbackHistoryJson(),
                d.isApproved(), d.getApprovedBy(), d.getApprovedAt(), d.getCompletedAt());
        return new TaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getRepoAlias(),
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
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getModel(),
                t.getEffort(),
                t.isDesignRequested(),
                av,
                designV,
                iv,
                dv,
                interviewSessionId,
                attachments == null ? List.of()
                        : attachments.stream().map(AttachmentView::from).toList()
        );
    }
}
