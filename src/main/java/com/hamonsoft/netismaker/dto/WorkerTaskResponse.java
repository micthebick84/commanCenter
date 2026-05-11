package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 * 분석에 필요한 최소 필드만.
 */
public record WorkerTaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description
) {
    public static WorkerTaskResponse of(Task t) {
        return new WorkerTaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getGithubBranch(),
                t.getTitle(),
                t.getDescription()
        );
    }
}
