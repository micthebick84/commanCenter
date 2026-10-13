package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.CloseStep;
import jakarta.validation.constraints.NotNull;

/**
 * 워커의 종료 단계 진행 보고 — POST /worker/tasks/{id}/close-progress.
 * 단계가 끝날 때마다 한 번. mergeCommitSha는 MERGED, notePath(볼트 상대 경로)는 NOTE_WRITTEN에서만 의미가 있다.
 */
public record CloseProgressRequest(
        @NotNull String workerId,
        @NotNull CloseStep step,
        String mergeCommitSha,
        String notePath
) {}
