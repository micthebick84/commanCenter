package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 워커가 분석/구현/배포 후 백엔드에 업로드하는 결과 페이로드.
 *
 *   status=COMPLETED             → markdownResult, subtasksJson 필수 (분석)
 *   status=FAILED                → failureReason 필수 (분석)
 *   status=PR_CREATED            → prUrl, headBranch 필수 (구현, envTemplate 선택) / 또는 UNDEPLOY 성공 복귀
 *   status=IMPLEMENTATION_FAILED → failureReason 필수 (구현)
 *   status=DEPLOYED              → deployUrl, deployContainerId, deployHostPort 필수 (배포)
 *   status=DEPLOY_FAILED         → failureReason 필수 (배포)
 *   status=DESIGN_REVIEW         → designMarkdown, mockupFilesJson 필수 (디자인 → 승인 대기)
 *   status=DESIGN_FAILED         → failureReason 필수 (디자인)
 */
public record WorkerResultRequest(
        @NotNull String workerId,
        @NotNull TaskStatus status,
        // 분석
        String markdownResult,
        String subtasksJson,
        String claudeLog,
        Long durationMs,
        String failureReason,
        // 구현
        String prUrl,
        Integer prNumber,
        String headBranch,
        String headSha,
        String implementationLog,
        // 배포
        String deployUrl,
        String deployContainerId,
        Integer deployHostPort,
        String deployImage,
        String deployLog,
        // 디자인
        String designMarkdown,
        String mockupFilesJson,
        String designProjectId,
        String designUrl,
        // 사용량 (전 phase 공용, 스펙 §4.1 — null이면 미수집/구버전 워커)
        UsageReport usage,
        // 구현 성공 시 추출한 배포 env 템플릿 (스펙 2026-10-07 §5 — null이면 미보고/구버전 워커)
        List<EnvTemplateItem> envTemplate
) {
    /**
     * envTemplate 도입 전 22개 인자 생성자 — 구현 성공 보고 외에는 템플릿이 없다.
     * Jackson은 레코드의 정규 생성자(23개)를 쓰므로 역직렬화와 무관하다(WorkerResultRequestJsonTest).
     */
    public WorkerResultRequest(String workerId, TaskStatus status,
                               String markdownResult, String subtasksJson, String claudeLog,
                               Long durationMs, String failureReason,
                               String prUrl, Integer prNumber, String headBranch, String headSha,
                               String implementationLog,
                               String deployUrl, String deployContainerId, Integer deployHostPort,
                               String deployImage, String deployLog,
                               String designMarkdown, String mockupFilesJson, String designProjectId,
                               String designUrl,
                               UsageReport usage) {
        this(workerId, status, markdownResult, subtasksJson, claudeLog, durationMs, failureReason,
                prUrl, prNumber, headBranch, headSha, implementationLog,
                deployUrl, deployContainerId, deployHostPort, deployImage, deployLog,
                designMarkdown, mockupFilesJson, designProjectId, designUrl, usage, null);
    }

    /**
     * 자유 텍스트 필드를 전부 마스킹한 복사본.
     *
     * 워커가 API로 올리는 텍스트(claude 로그·배포 로그·분석/디자인 마크다운·실패 사유·env 템플릿 설명)는
     * 작업 상세 화면에 그대로 렌더되므로, 그 안에 섞여 들어온 인증 URL을 여기서 한 번에 가린다.
     * 식별자·열거형·숫자·결과 URL(prUrl/deployUrl/designUrl 등)은 자유 텍스트가 아니라 그대로 둔다.
     * 적용 지점은 {@code ResultReporter.reportTerminal} 한 곳(전송 + dead-letter 기록 공통).
     */
    public WorkerResultRequest masked() {
        return new WorkerResultRequest(
                workerId, status,
                m(markdownResult), m(subtasksJson), m(claudeLog), durationMs, m(failureReason),
                prUrl, prNumber, headBranch, headSha, m(implementationLog),
                deployUrl, deployContainerId, deployHostPort, deployImage, m(deployLog),
                m(designMarkdown), m(mockupFilesJson), designProjectId, designUrl,
                usage, maskTemplate(envTemplate));
    }

    /** 템플릿 설명은 Claude가 쓴 자유 텍스트 — 다른 자유 텍스트와 같이 가린다. KEY·플래그는 그대로. */
    private static List<EnvTemplateItem> maskTemplate(List<EnvTemplateItem> items) {
        if (items == null) return null;
        return items.stream()
                .map(i -> i == null ? null
                        : new EnvTemplateItem(i.key(), m(i.description()), i.secret(), i.required()))
                .toList();
    }

    private static String m(String text) {
        return com.hamonsoft.netismaker.git.GitRemotes.mask(text);
    }

    /** 단계 1회 실행분의 토큰/비용. 백엔드가 task_stage_usage에 누적한다. */
    public record UsageReport(java.math.BigDecimal costUsd, Long inputTokens, Long outputTokens,
                              Long cacheCreationTokens, Long cacheReadTokens) {
        /** 모든 필드가 null/0이면 기록할 것이 없다. */
        public boolean isEmpty() {
            return (costUsd == null || costUsd.signum() == 0)
                    && zero(inputTokens) && zero(outputTokens)
                    && zero(cacheCreationTokens) && zero(cacheReadTokens);
        }
        private static boolean zero(Long v) { return v == null || v == 0L; }
    }

    /** 배포 성공 보고. */
    public static WorkerResultRequest deployed(String workerId, String deployUrl,
                                               String containerId, int hostPort,
                                               String image, Long durationMs, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOYED,
                null, null, null, durationMs, null,
                null, null, null, null, null,
                deployUrl, containerId, hostPort, image, deployLog,
                null, null, null, null, null);
    }

    /** 배포 실패 보고. */
    public static WorkerResultRequest deployFailed(String workerId, String reason, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOY_FAILED,
                null, null, null, null, reason,
                null, null, null, null, null,
                null, null, null, null, deployLog,
                null, null, null, null, null);
    }

    /** 배포 중지(undeploy) 성공 → PR생성 복귀 보고. */
    public static WorkerResultRequest undeployed(String workerId, String undeployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.PR_CREATED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, undeployLog,
                null, null, null, null, null);
    }

    /** 디자인 생성 성공 → 승인 대기 보고. */
    public static WorkerResultRequest designReview(String workerId, String designMarkdown,
                                                   String mockupFilesJson, String designProjectId,
                                                   String designUrl, String claudeLog, Long durationMs,
                                                   UsageReport usage) {
        return new WorkerResultRequest(workerId, TaskStatus.DESIGN_REVIEW,
                null, null, claudeLog, durationMs, null,
                null, null, null, null, null,
                null, null, null, null, null,
                designMarkdown, mockupFilesJson, designProjectId, designUrl, usage);
    }

    /** 디자인 생성 실패 보고. */
    public static WorkerResultRequest designFailed(String workerId, String reason, String claudeLog,
                                                   UsageReport usage) {
        return new WorkerResultRequest(workerId, TaskStatus.DESIGN_FAILED,
                null, null, claudeLog, null, reason,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, usage);
    }
}
