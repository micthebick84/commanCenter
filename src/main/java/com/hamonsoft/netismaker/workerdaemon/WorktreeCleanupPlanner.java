package com.hamonsoft.netismaker.workerdaemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * worktree 정리 판정(순수 함수)과 그 근거 읽기 — WorktreeCleanupJob 전용. 삭제·락은 잡이 다룬다.
 * 모르면 안 지운다: 진행 중이거나, 보존 기간 안이거나, 시각을 못 읽으면 남긴다.
 */
final class WorktreeCleanupPlanner {

    enum Verdict { REMOVE, ACTIVE, TOO_NEW, UNKNOWN }

    private static final String GITDIR_PREFIX = "gitdir:";

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

    /**
     * 참조 worktree({worktree-root}/_refs/task-{id}/{카탈로그 id})의 원본 레포 캐시 localKey — 폴더의 .git 파일(gitdir)이
     * 가리키는 {repos-dir}/{a}/{b}/.git/worktrees/{메타}에서 {a}/{b}. 메타 이름은 폴더 이름과 다를 수 있으므로(같은 캐시의 두 번째
     * worktree는 12 → 121처럼 번호가 붙는다) 폴더 이름으로 짐작하지 않는다(스펙 2026-10-24 §6.8).
     * 못 믿으면 empty — 호출자는 락·git 없이 폴더만 지운다. 가짜 키로 락 파일을 만들지 않게 아래를 모두 요구한다:
     *  - .git이 링크가 아닌 일반 파일이고 첫 줄이 "gitdir:" (상대 경로면 refDir 기준 — git 2.48+ relative 모드)
     *  - 그 경로의 부모가 "worktrees", 그 부모가 ".git"
     *  - 캐시 실경로가 reposRootReal 아래 정확히 2단계이고 첫 단계가 '.'으로 시작하지 않으며(.trash 등 repos-dir 내부용),
     *    캐시/.git이 링크가 아닌 디렉터리
     *
     * @param reposRootReal repos-dir 실경로. null이면(설정이 비었거나 지금 없음) empty
     * @return 디스크의 실제 이름으로 이은 "{a}/{b}"
     */
    static Optional<String> refSourceKey(Path refDir, Path reposRootReal) {
        if (refDir == null || reposRootReal == null) return Optional.empty();
        try {
            Path gitFile = refDir.resolve(".git");
            if (!Files.isRegularFile(gitFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            String first;
            try (BufferedReader r = Files.newBufferedReader(gitFile)) {
                first = r.readLine();
            }
            if (first == null || !first.startsWith(GITDIR_PREFIX)) return Optional.empty();
            String raw = first.substring(GITDIR_PREFIX.length()).trim();
            if (raw.isEmpty()) return Optional.empty();
            Path meta = refDir.resolve(raw).normalize();

            Path worktrees = meta.getParent();
            if (worktrees == null || !"worktrees".equals(String.valueOf(worktrees.getFileName()))) return Optional.empty();
            Path dotGit = worktrees.getParent();
            if (dotGit == null || !".git".equals(String.valueOf(dotGit.getFileName()))) return Optional.empty();
            Path cache = dotGit.getParent();
            if (cache == null) return Optional.empty();

            Path cacheReal = cache.toRealPath();
            if (!cacheReal.startsWith(reposRootReal)) return Optional.empty();
            Path rel = reposRootReal.relativize(cacheReal);
            if (rel.getNameCount() != 2) return Optional.empty();
            String owner = rel.getName(0).toString();
            if (owner.isEmpty() || owner.startsWith(".")) return Optional.empty();
            if (!isPlainDir(cacheReal.resolve(".git"))) return Optional.empty();
            return Optional.of(owner + "/" + rel.getName(1));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 링크·정션이 아닌 진짜 디렉터리(Windows 정션은 NOFOLLOW_LINKS에서 isOther). */
    private static boolean isPlainDir(Path p) throws IOException {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isDirectory() && !a.isSymbolicLink() && !a.isOther();
        } catch (NoSuchFileException e) {
            return false;
        }
    }
}
