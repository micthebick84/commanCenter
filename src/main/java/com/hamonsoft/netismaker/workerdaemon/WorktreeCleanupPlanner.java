package com.hamonsoft.netismaker.workerdaemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * worktree 정리 판정(순수 함수) — WorktreeCleanupJob 전용. 파일 시스템·락은 잡이 다룬다.
 * 모르면 안 지운다: 진행 중이거나, 보존 기간 안이거나, 시각을 못 읽으면 남긴다.
 */
final class WorktreeCleanupPlanner {

    enum Verdict { REMOVE, ACTIVE, TOO_NEW, UNKNOWN }

    private WorktreeCleanupPlanner() {}

    /**
     * @param lastActivity  {@link #lastActivity(Path)} 결과. null이면 UNKNOWN
     * @param retentionDays 1 이상(WorkerProperties.WorktreeCleanup이 보장)
     */
    static Verdict judge(long taskId, Set<Long> activeTaskIds, Instant lastActivity, Instant now, int retentionDays) {
        if (activeTaskIds.contains(taskId)) return Verdict.ACTIVE;
        if (lastActivity == null) return Verdict.UNKNOWN;
        if (lastActivity.isAfter(now.minus(Duration.ofDays(retentionDays)))) return Verdict.TOO_NEW;
        return Verdict.REMOVE;
    }

    /**
     * 폴더와 그 안 .git 파일의 수정 시각 중 늦은 쪽. worktree는 작업 시작 때 만들어지므로 사실상 '만든 시각'.
     * 폴더 시각을 못 읽거나 .git이 부재 외의 이유로 안 읽히면 null(판단 불가). .git이 없으면 폴더 시각만.
     */
    static Instant lastActivity(Path worktreeDir) {
        Instant dir;
        try {
            dir = Files.getLastModifiedTime(worktreeDir, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            return null;
        }
        try {
            Instant git = Files.getLastModifiedTime(worktreeDir.resolve(".git"), LinkOption.NOFOLLOW_LINKS).toInstant();
            return git.isAfter(dir) ? git : dir;
        } catch (NoSuchFileException e) {
            return dir;
        } catch (IOException e) {
            return null;
        }
    }
}
