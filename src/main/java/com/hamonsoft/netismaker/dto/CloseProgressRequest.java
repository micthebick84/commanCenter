package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.CloseStep;
import jakarta.validation.constraints.NotNull;

/**
 * 워커의 종료 단계 진행 보고 — POST /worker/tasks/{id}/close-progress.
 * 단계가 끝날 때마다 한 번. mergeCommitSha는 MERGED, notePath(볼트 상대 경로)·noteFolder는 NOTE_WRITTEN에서만 의미가 있다.
 * noteFolder = 카탈로그에 기록할 노트 폴더 — 워커가 이번에 정했을 때만 싣는다(스펙 2026-10-28 close-note-folder §5).
 */
public record CloseProgressRequest(
        @NotNull String workerId,
        @NotNull CloseStep step,
        String mergeCommitSha,
        String notePath,
        String noteFolder
) {
    /** 노트 폴더 도입 전 4개 인자 — 기존 호출부·테스트 호환. */
    public CloseProgressRequest(String workerId, CloseStep step, String mergeCommitSha, String notePath) {
        this(workerId, step, mergeCommitSha, notePath, null);
    }
}
