package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;

/**
 * worktree 정리 잡(WorktreeCleanupJob)의 보호 목록 항목 — 워커가 claim해 worktree를 쓰는 중인 task.
 * worktree 경로는 워커가 규칙({kind}-{id})으로 유도하므로 id만 쓴다.
 */
public record ActiveWorktreeTaskSummary(Long id, TaskStatus status) {
}
