package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 *  레포 영구 캐시.
 *
 *   ~/netis-maker/repos/{owner}/{repo}/  (DESIGN P8)
 *
 *   첫 회: git clone https://oauth2:$PAT@github.com/{owner}/{repo}.git
 *   이후: cd <dir> && git fetch && git checkout <branch> && git pull
 *
 *  다중 워커 안전성 (Phase 2.1):
 *   - 같은 머신에서 워커 N 프로세스가 동시에 같은 repo를 fetch하면 `.git/index.lock` 충돌
 *   - 해결: per-repo FileLock (`~/netis-maker/repos/{owner}/{repo}.lock`)
 *   - 같은 JVM 내에서도 두 번 락 시도하지 않게 ReentrantLock 추가 (성능 최적화)
 *   - ensureFresh + worktree add 둘 다 같은 락 안에서 직렬화
 *     → WorktreeService도 withRepoLock(repoKey, ...)을 통해 호출
 */
@Component
@Profile("worker")
@Slf4j
public class GitRepoCache {

    private static final long GIT_TIMEOUT_SECONDS = 600;

    private final WorkerProperties props;
    /** 같은 JVM 내 동시 호출 직렬화. FileLock이 cross-process는 처리하지만 in-process는 ReentrantLock이 가벼움. */
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> inProcessLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public GitRepoCache(WorkerProperties props) {
        this.props = props;
    }

    public CheckedOutRepo ensureFresh(String githubRepo, String branch) throws IOException, InterruptedException {
        if (!githubRepo.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) {
            throw new IllegalArgumentException("invalid github_repo: " + githubRepo);
        }
        return withRepoLock(githubRepo, () -> doEnsureFresh(githubRepo, branch));
    }

    /**
     * 브랜치 checkout 없이 fetch만 수행 (배포용).
     *
     * ensureFresh는 {@code git checkout <branch>}를 하는데, 배포 대상 head 브랜치는
     * 보통 구현 단계의 보존된 worktree가 이미 점유 중이라 checkout이 거부된다
     * ("already used by worktree"). 배포는 {@code git worktree add --detach origin/<head>}로
     * 분리 체크아웃하므로 공유 클론에서 브랜치를 checkout할 필요가 없다 — fetch만 하면 된다.
     *
     * @return 캐시 디렉토리 (commitSha는 캐시의 현재 HEAD — 배포에선 의미 없음)
     */
    public CheckedOutRepo fetchOnly(String githubRepo) throws IOException, InterruptedException {
        if (!githubRepo.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) {
            throw new IllegalArgumentException("invalid github_repo: " + githubRepo);
        }
        return withRepoLock(githubRepo, () -> {
            Path target = Paths.get(props.reposDir(), githubRepo);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target.resolve(".git"))) {
                // 배포는 분석/구현을 거친 repo 대상이라 보통 이미 캐시됨. 없으면 기본 브랜치로 clone.
                run(target.getParent().toFile(), "git", "clone",
                        repoUrl(githubRepo), target.getFileName().toString());
            } else {
                run(target.toFile(), "git", "fetch", "--all", "--prune");
            }
            String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
            return new CheckedOutRepo(target.toFile(), sha);
        });
    }

    private CheckedOutRepo doEnsureFresh(String githubRepo, String branch) throws IOException, InterruptedException {
        Path target = Paths.get(props.reposDir(), githubRepo);
        Files.createDirectories(target.getParent());

        if (!Files.exists(target.resolve(".git"))) {
            String url = repoUrl(githubRepo);
            run(target.getParent().toFile(), "git", "clone", "--depth=1", "--branch", branch, url, target.getFileName().toString());
        } else {
            run(target.toFile(), "git", "fetch", "--all", "--prune");
            run(target.toFile(), "git", "checkout", branch);
            run(target.toFile(), "git", "reset", "--hard", "origin/" + branch);
        }

        String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
        return new CheckedOutRepo(target.toFile(), sha);
    }

    /**
     * repoKey별 cross-process 락. 같은 머신 다중 워커 프로세스가 동일 repo에서
     * fetch/clone/worktree-add 동시 호출하는 것을 방지.
     *
     *  - 락 파일: ~/netis-maker/repos/{owner}/{repo}.lock (repo 디렉토리 옆 형제)
     *  - JVM 내 추가 직렬화: ReentrantLock per repoKey (cheaper than file lock contention)
     *  - 락 보유 중 예외 발생해도 finally에서 안전 해제
     */
    public <T> T withRepoLock(String githubRepo, RepoOp<T> op) throws IOException, InterruptedException {
        ReentrantLock jvmLock = inProcessLocks.computeIfAbsent(githubRepo, k -> new ReentrantLock());
        jvmLock.lock();
        try {
            Path lockPath = Paths.get(props.reposDir(), githubRepo + ".lock");
            Files.createDirectories(lockPath.getParent());
            try (FileChannel ch = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fileLock = acquireWithTimeout(ch, githubRepo)) {
                return op.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /** 60초 타임아웃 안에서 락 획득 시도. 실패 시 IOException. */
    private FileLock acquireWithTimeout(FileChannel ch, String repoKey) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            FileLock tried = ch.tryLock();
            if (tried != null) return tried;
            log.debug("repo lock 대기 중: {}", repoKey);
            Thread.sleep(500);
        }
        throw new IOException("repo lock timeout (60s): " + repoKey);
    }

    @FunctionalInterface
    public interface RepoOp<T> {
        T run() throws IOException, InterruptedException;
    }

    // PAT가 있으면 인증 URL, 없으면 익명 URL (public repo).
    // 빈 비밀번호 형태 'https://oauth2:@github.com/...'는 GitHub가 거부하므로 plain URL로 폴백.
    private String repoUrl(String githubRepo) {
        String pat = props.githubPat();
        if (pat == null || pat.isBlank()) {
            return "https://github.com/" + githubRepo + ".git";
        }
        return "https://oauth2:" + pat + "@github.com/" + githubRepo + ".git";
    }

    private void run(File dir, String... command) throws IOException, InterruptedException {
        log.debug("git exec ({}): {}", dir, String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("git timeout: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git failed (" + p.exitValue() + "): " + out);
        }
    }

    private String capture(File dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(false).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return out.toString();
    }

    public record CheckedOutRepo(File dir, String commitSha) {}
}
