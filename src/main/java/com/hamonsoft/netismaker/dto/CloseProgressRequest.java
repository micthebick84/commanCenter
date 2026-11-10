package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.CloseStep;
import jakarta.validation.constraints.NotNull;

/**
 * 워커의 종료 단계 진행 보고 — POST /worker/tasks/{id}/close-progress.
 * 단계가 끝날 때마다 한 번. mergeCommitSha는 MERGED, notePath(볼트 상대 경로)·noteFolder는 NOTE_WRITTEN에서만 의미가 있다.
 * noteFolder = 카탈로그에 기록할 노트 폴더 — 워커가 이번에 정했을 때만 싣는다(스펙 2026-10-28 close-note-folder §5).
 * baseSha는 MERGED에서 부분 패치 기준 커밋(병합 직전 기준 브랜치 끝), patchEmpty·patchFileCount는 PATCH_BUILT에서만
 * 의미가 있다 — 변경이 없으면 patchEmpty=true (스펙 2026-11-10).
 */
public record CloseProgressRequest(
        @NotNull String workerId,
        @NotNull CloseStep step,
        String mergeCommitSha,
        String notePath,
        String noteFolder,
        String baseSha,
        Boolean patchEmpty,
        Integer patchFileCount
) {
    /** 부분 패치 도입 전 5개 인자 — 기존 호출부·테스트 호환. */
    public CloseProgressRequest(String workerId, CloseStep step, String mergeCommitSha, String notePath,
                                String noteFolder) {
        this(workerId, step, mergeCommitSha, notePath, noteFolder, null, null, null);
    }

    /** 노트 폴더 도입 전 4개 인자 — 기존 호출부·테스트 호환. */
    public CloseProgressRequest(String workerId, CloseStep step, String mergeCommitSha, String notePath) {
        this(workerId, step, mergeCommitSha, notePath, null, null, null, null);
    }
}
