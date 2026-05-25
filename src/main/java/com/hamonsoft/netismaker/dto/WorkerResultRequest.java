package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 워커가 분석/구현 후 백엔드에 업로드하는 결과 페이로드.
 *
 *   status=COMPLETED            → markdownResult, subtasksJson 필수 (분석)
 *   status=FAILED               → failureReason 필수 (분석)
 *   status=PR_CREATED           → prUrl, prNumber, headBranch, headSha 필수 (구현)
 *   status=IMPLEMENTATION_FAILED→ failureReason 필수 (구현)
 *   그 외 상태 거부
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
        String implementationLog
) {}
