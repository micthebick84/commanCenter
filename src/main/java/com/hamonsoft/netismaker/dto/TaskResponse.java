package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.entity.TaskStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
        List<AttachmentView> attachments,
        /** 항상 non-null. 목록 엔드포인트는 항상 [] — 실데이터는 상세 응답만 (스펙 §7). */
        List<StageUsageView> stageUsage,
        /** 단계별 usage 총합(USD). usage 행이 하나도 없으면 null(미수집) — 0이 아니다. */
        BigDecimal totalCostUsd,
        /** input+output 토큰 합(캐시 제외). usage 행이 하나도 없으면 null(미수집). */
        Long totalTokens,
        /** 종료 단계 표시. 종료 상태이거나 종료 기록이 있을 때만 non-null (스펙 2026-10-13 §5). */
        CloseView close,
        /** 레포 카탈로그 id — 승인 다이얼로그 DB 피커가 목록을 부를 때 쓴다 (스펙 2026-10-14 §4). 레거시 작업은 null. */
        Long repoCatalogId,
        /** 승인 때 고른 DB 연결 id (스펙 2026-10-14 §4). */
        List<Long> dbConnectionIds,
        /** 구현 방식 DIRECT|REVIEW|SDD (스펙 2026-10-15 §5.2). */
        String implementationMode,
        /** 구현 시도 번호(1부터). */
        int implementationAttempt,
        /** 워커가 마지막으로 가져간 시각 — 결과 보고 뒤에도 남으므로 화면은 구현중일 때만 쓴다. */
        OffsetDateTime claimedAt,
        /** 이번 시도의 구현 상한(분) — claim 때 기록, 그 전에는 null. */
        Integer implementationTimeoutMinutes,
        /**
         * 읽기 전용 참조 repo 칩 (스펙 2026-10-24 §4.9). 항상 non-null. 작업의 참조 — 단 인터뷰 단계 상세 응답은
         * 진행 중 세션의 참조(고정·실패 상태 포함)로 바꿔 싣는다(TaskController.get).
         */
        List<InterviewResponse.RefRepoView> refRepos,
        /** 이 작업을 등록한 질문 세션 id. 질문에서 등록하지 않았으면 null. */
        Long sourceQuestionId
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

    /** 단계별 토큰/비용 (스펙 §7). */
    public record StageUsageView(
            String stage,
            BigDecimal costUsd,
            long inputTokens,
            long outputTokens,
            long cacheCreationTokens,
            long cacheReadTokens
    ) {}

    /**
     * progress는 끝난 CloseStep 이름 — enum 순서. artifactMode는 null | REQUIRED | SKIPPED,
     * artifact·mail은 없으면 null (스펙 2026-10-14 §8). 수신 주소는 싣지 않는다.
     */
    public record CloseView(List<String> progress, String mergeCommitSha, String notePath,
                            String artifactMode, ArtifactView artifact, MailView mail) {}

    public record ArtifactView(String name, long sizeBytes, String sha256, OffsetDateTime uploadedAt) {}

    public record MailView(String status, OffsetDateTime at) {}

    private static final Set<TaskStatus> CLOSE_PHASE = EnumSet.of(
            TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING,
            TaskStatus.CLOSED, TaskStatus.CLOSE_FAILED);

    private static CloseView closeViewOf(Task t) {
        boolean recorded = t.getCloseProgress() != null || t.getMergeCommitSha() != null || t.getCloseNotePath() != null
                || t.getArtifactName() != null;
        if (!recorded && !CLOSE_PHASE.contains(t.getStatus())) return null;
        return new CloseView(
                CloseStep.parse(t.getCloseProgress()).stream().map(Enum::name).toList(),
                t.getMergeCommitSha(), t.getCloseNotePath(),
                t.getCloseArtifactMode(),
                t.getArtifactName() == null ? null : new ArtifactView(t.getArtifactName(),
                        t.getArtifactSize() == null ? 0 : t.getArtifactSize(), t.getArtifactSha256(),
                        t.getArtifactUploadedAt()),
                t.getCloseMailStatus() == null ? null : new MailView(t.getCloseMailStatus(), t.getCloseMailAt()));
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
        return build(t, a, d, interviewSessionId, attachments, List.of(), null, null);
    }

    /** 상세 응답 — usage rows를 뷰로 변환하고 총합 계산. rows 비면 총합 null (미수집, 스펙 §7). */
    public static TaskResponse ofWithUsage(Task t, TaskAnalysis a, TaskDesign d,
                                           Long interviewSessionId,
                                           List<TaskAttachment> attachments,
                                           List<TaskStageUsage> usage) {
        List<StageUsageView> views = usage == null ? List.of()
                : usage.stream().map(u -> new StageUsageView(u.getStage(), u.getCostUsd(),
                        u.getInputTokens(), u.getOutputTokens(),
                        u.getCacheCreationTokens(), u.getCacheReadTokens())).toList();
        BigDecimal totalCost = views.isEmpty() ? null
                : views.stream().map(StageUsageView::costUsd)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long totalTokens = views.isEmpty() ? null
                : views.stream().mapToLong(v -> v.inputTokens() + v.outputTokens()).sum();
        return build(t, a, d, interviewSessionId, attachments, views, totalCost, totalTokens);
    }

    /** 목록 응답 — 총비용만 채워 재조립 (stageUsage는 [] 유지). record라 wither가 없어 전 컴포넌트 나열. */
    public static TaskResponse withTotalCost(TaskResponse r, BigDecimal totalCostUsd) {
        return new TaskResponse(
                r.id(),
                r.githubRepo(),
                r.repoAlias(),
                r.githubBranch(),
                r.title(),
                r.description(),
                r.status(),
                r.statusLabel(),
                r.requesterId(),
                r.retryCount(),
                r.maxRetry(),
                r.failureReason(),
                r.mcpsExtra(),
                r.envVars(),
                r.createdAt(),
                r.updatedAt(),
                r.model(),
                r.effort(),
                r.designRequested(),
                r.analysis(),
                r.design(),
                r.implementation(),
                r.deployment(),
                r.interviewSessionId(),
                r.attachments(),
                r.stageUsage(),
                totalCostUsd,
                r.totalTokens(),
                r.close(),
                r.repoCatalogId(),
                r.dbConnectionIds(),
                r.implementationMode(),
                r.implementationAttempt(),
                r.claimedAt(),
                r.implementationTimeoutMinutes(),
                r.refRepos(),
                r.sourceQuestionId()
        );
    }

    /** 참조 칩만 바꾼 사본 — 인터뷰 단계 상세가 진행 중 세션의 참조를 싣는다(스펙 §4.9). null이면 []. */
    public TaskResponse withRefRepos(List<RefRepo> refs) {
        return new TaskResponse(id, githubRepo, repoAlias, githubBranch, title, description, status, statusLabel,
                requesterId, retryCount, maxRetry, failureReason, mcpsExtra, envVars, createdAt, updatedAt,
                model, effort, designRequested, analysis, design, implementation, deployment, interviewSessionId,
                attachments, stageUsage, totalCostUsd, totalTokens, close, repoCatalogId, dbConnectionIds,
                implementationMode, implementationAttempt, claimedAt, implementationTimeoutMinutes,
                refViews(refs), sourceQuestionId);
    }

    private static List<InterviewResponse.RefRepoView> refViews(List<RefRepo> refs) {
        return refs == null ? List.of() : refs.stream().map(InterviewResponse.RefRepoView::of).toList();
    }

    /**
     * 비밀 env 값을 비운 복사본 — 관리자가 아닌 사용자 응답용 (스펙 2026-10-07 §7). 배포 다이얼로그가 같은 레포의
     * 직전 배포 값(비밀 포함)을 옮겨 오므로, 작업 등록자에게 다른 작업의 비밀이 보이지 않게 한다.
     */
    public TaskResponse redactSecretValues() {
        List<EnvVar> redacted = envVars == null ? List.of() : envVars.stream()
                .map(e -> e.secret() ? new EnvVar(e.key(), "", true) : e)
                .toList();
        return new TaskResponse(id, githubRepo, repoAlias, githubBranch, title, description, status, statusLabel,
                requesterId, retryCount, maxRetry, failureReason, mcpsExtra, redacted, createdAt, updatedAt,
                model, effort, designRequested, analysis, design, implementation, deployment, interviewSessionId,
                attachments, stageUsage, totalCostUsd, totalTokens, close, repoCatalogId, dbConnectionIds,
                implementationMode, implementationAttempt, claimedAt, implementationTimeoutMinutes,
                refRepos, sourceQuestionId);
    }

    private static TaskResponse build(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId,
                                      List<TaskAttachment> attachments,
                                      List<StageUsageView> stageUsage, BigDecimal totalCostUsd,
                                      Long totalTokens) {
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
                        : attachments.stream().map(AttachmentView::from).toList(),
                stageUsage == null ? List.of() : stageUsage,
                totalCostUsd,
                totalTokens,
                closeViewOf(t),
                t.getRepoCatalogId(),
                t.getDbConnectionIds() == null ? List.of() : List.copyOf(t.getDbConnectionIds()),
                (t.getImplementationMode() == null ? ImplementationMode.DIRECT : t.getImplementationMode()).name(),
                t.getImplementationAttempt(),
                t.getClaimedAt(),
                t.getImplementationTimeoutMinutes(),
                refViews(t.getRefRepos()),
                t.getSourceQuestionId()
        );
    }
}
