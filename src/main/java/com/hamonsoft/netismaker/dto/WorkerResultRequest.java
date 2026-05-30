package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 워커가 분석/구현/배포 후 백엔드에 업로드하는 결과 페이로드.
 *
 *   status=COMPLETED             → markdownResult, subtasksJson 필수 (분석)
 *   status=FAILED                → failureReason 필수 (분석)
 *   status=PR_CREATED            → prUrl, headBranch 필수 (구현) / 또는 UNDEPLOY 성공 복귀
 *   status=IMPLEMENTATION_FAILED → failureReason 필수 (구현)
 *   status=DEPLOYED              → deployUrl, deployContainerId, deployHostPort 필수 (배포)
 *   status=DEPLOY_FAILED         → failureReason 필수 (배포)
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
        String deployLog
) {
    /** 배포 성공 보고. */
    public static WorkerResultRequest deployed(String workerId, String deployUrl,
                                               String containerId, int hostPort,
                                               String image, Long durationMs, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOYED,
                null, null, null, durationMs, null,
                null, null, null, null, null,
                deployUrl, containerId, hostPort, image, deployLog);
    }

    /** 배포 실패 보고. */
    public static WorkerResultRequest deployFailed(String workerId, String reason, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOY_FAILED,
                null, null, null, null, reason,
                null, null, null, null, null,
                null, null, null, null, deployLog);
    }

    /** 배포 중지(undeploy) 성공 → PR생성 복귀 보고. */
    public static WorkerResultRequest undeployed(String workerId, String undeployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.PR_CREATED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, undeployLog);
    }
}
