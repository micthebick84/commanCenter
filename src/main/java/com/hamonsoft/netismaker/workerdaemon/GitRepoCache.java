package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
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
 *   ~/netis-maker/repos/{localKey}/   — GitHub: {owner}/{repo}, GitLab: _gitlab/{경로의 '/'→'+'}
 *
 *   첫 회: git clone <인증 URL> && git remote set-url origin <평문 gitUrl>
 *   이후: git remote set-url origin <평문 gitUrl>
 *         && git fetch --prune <인증 URL> +refs/heads/<branch>:refs/remotes/origin/<branch>
 *         && git checkout --force --detach origin/<branch>
 *
 *  불변식: 자격증명은 전송(clone/fetch/push)에만 쓰고 `.git/config`의 origin에는 절대 남기지 않는다.
 *   - worktree는 이 캐시 repo의 config를 공유한다 → 구현/디자인/배포 워크트리에서 도는 claude 세션이
 *     `git remote -v`만으로 토큰을 읽을 수 있기 때문.
 *   - 기존 캐시는 fetch 전에 먼저 origin을 씻으므로, 취약 버전(및 브랜치 도입 전 GitHub PAT 캐시)이
 *     남긴 인증 URL도 다음 ensureFresh에서 함께 제거된다.
 *   - 단일 브랜치 shallow clone이라 구성된 refspec은 최초 브랜치 하나뿐 → 필요한 브랜치를
 *     명시 refspec으로 fetch한다(`git fetch --all --prune` 대체).
 *
 *  다중 워커 안전성 (Phase 2.1):
 *   - 같은 머신에서 워커 N 프로세스가 동시에 같은 repo를 fetch하면 `.git/index.lock` 충돌
 *   - 해결: per-repo FileLock (`~/netis-maker/repos/{localKey}.lock`)
 *   - 같은 JVM 내에서도 두 번 락 시도하지 않게 ReentrantLock 추가 (성능 최적화)
 *   - ensureFresh + worktree add 둘 다 같은 락 안에서 직렬화
 *     → WorktreeService도 withRepoLock(repoKey, ...)을 통해 호출
 *
 *  마지막 사용 시각: withRepoLock을 잡을 때마다 `{localKey}.last-used`(락 파일 옆 형제)를 갱신한다.
 *   캐시를 쓰는 경로(ensureFresh·fetchOnly·worktree 생성)가 전부 이 락을 거치므로 한 곳에서 기록된다.
 *   RepoCacheCleanupJob이 이 mtime으로 미사용 기간을 판단하고, 삭제는 tryWithRepoLock(비차단, 마커 미갱신)으로 한다.
 */
@Component
@Profile("worker")
@Slf4j
public class GitRepoCache {

    private static final long GIT_TIMEOUT_SECONDS = 600;
    /** 락 파일 접미사: {localKey}.lock */
    static final String LOCK_SUFFIX = ".lock";
    /** 마지막 사용 시각 마커 접미사: {localKey}.last-used (판단은 mtime, 내용은 사람이 보는 용도) */
    static final String LAST_USED_SUFFIX = ".last-used";

    private final WorkerProperties props;
    private final GitRemotes remotes;
    /** 같은 JVM 내 동시 호출 직렬화. FileLock이 cross-process는 처리하지만 in-process는 ReentrantLock이 가벼움. */
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> inProcessLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public GitRepoCache(WorkerProperties props) {
        this.props = props;
        this.remotes = new GitRemotes(props.githubPat(), props.gitlabToken());
    }

    public CheckedOutRepo ensureFresh(RepoRef ref, String branch) throws IOException, InterruptedException {
        return withRepoLock(GitRemotes.localKey(ref), () -> doEnsureFresh(ref, branch));
    }

    /**
     * 브랜치 checkout 없이 fetch만 수행 (배포용).
     *
     * 배포는 {@code git worktree add --detach origin/<head>}로 분리 체크아웃하므로
     * 공유 클론의 작업 트리를 head 브랜치로 옮길 필요가 없다 — fetch만 하면 된다.
     *
     * 캐시는 보통 {@code --depth=1 --branch <base>} 단일 브랜치 shallow clone이라
     * {@code git fetch --all}만으로는 head 브랜치의 remote-tracking ref가 생기지 않는다.
     * 따라서 head 브랜치를 명시적 refspec으로 fetch해 {@code origin/<head>}를 만든다.
     * (이후 createForDeploy가 {@code git worktree add --detach origin/<head>} 사용.)
     *
     * @return 캐시 디렉토리 (commitSha는 캐시의 현재 HEAD — 배포에선 의미 없음)
     */
    public CheckedOutRepo fetchOnly(RepoRef ref, String headBranch) throws IOException, InterruptedException {
        return withRepoLock(GitRemotes.localKey(ref), () -> {
            Path target = Paths.get(props.reposDir(), GitRemotes.localKey(ref));
            Files.createDirectories(target.getParent());
            String authUrl = remotes.authenticatedUrl(ref);
            if (!Files.exists(target.resolve(".git"))) {
                // 배포는 분석/구현을 거친 repo 대상이라 보통 이미 캐시됨. 없으면 head 브랜치로 clone.
                run(target.getParent().toFile(),
                        cloneArgs(authUrl, headBranch, target.getFileName().toString()));
                scrubOrigin(target, ref);
            } else {
                scrubOrigin(target, ref);
                // head 브랜치를 origin/<head> tracking ref로 명시적 fetch (단일 브랜치 클론 대비).
                run(target.toFile(), fetchTrackingArgs(authUrl, headBranch));
            }
            String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
            return new CheckedOutRepo(target.toFile(), sha);
        });
    }

    private CheckedOutRepo doEnsureFresh(RepoRef ref, String branch) throws IOException, InterruptedException {
        Path target = Paths.get(props.reposDir(), GitRemotes.localKey(ref));
        Files.createDirectories(target.getParent());

        String authUrl = remotes.authenticatedUrl(ref);
        if (!Files.exists(target.resolve(".git"))) {
            run(target.getParent().toFile(),
                    cloneArgs(authUrl, branch, target.getFileName().toString()));
            // clone 직후 바로 씻는다 — 실패하면 인증 URL이 남으므로 예외를 그대로 전파(마스킹됨)
            scrubOrigin(target, ref);
        } else {
            scrubOrigin(target, ref);
            run(target.toFile(), fetchArgs(authUrl, branch));
            // 로컬 브랜치를 거치지 않고 origin/<branch>를 분리 체크아웃한다.
            //  - `git checkout <branch>`의 추적 브랜치 자동 생성은 구성된 refspec(최초 브랜치 하나)만 봐서
            //    다른 브랜치면 "pathspec did not match"로 실패한다.
            //  - 로컬 브랜치를 만들거나 옮기면 그 브랜치를 점유한 worktree와 충돌한다.
            //  캐시는 HEAD sha와 작업 트리만 쓰인다(worktree는 origin/<base>에서 분기). --force가 reset --hard 몫.
            run(target.toFile(), "git", "checkout", "--force", "--detach", "origin/" + branch);
        }

        String sha = capture(target.toFile(), "git", "rev-parse", "HEAD").trim();
        return new CheckedOutRepo(target.toFile(), sha);
    }

    /**
     * origin을 토큰 없는 평문 gitUrl로 맞춘다. 캐시 config(=worktree 공유 config)에 자격증명을
     * 남기지 않기 위한 단일 지점 — 취약 버전이 남긴 인증 URL도 여기서 씻긴다.
     */
    private void scrubOrigin(Path target, RepoRef ref) throws IOException, InterruptedException {
        run(target.toFile(), scrubArgs(ref));
    }

    // ── git 인자 조립 (순수 함수 — 네트워크 없이 https 경로를 테스트로 가드) ──

    /**
     * 토큰을 싣는 명령의 공통 prefix.
     * {@code credential.helper=}(빈 값)은 헬퍼 목록을 비워 운영자 머신의
     * osxkeychain/manager가 인증 URL의 토큰을 저장하지 못하게 한다.
     */
    private static final java.util.List<String> GIT_NO_HELPER =
            java.util.List.of("git", "-c", "credential.helper=");

    private static java.util.List<String> git(String... args) {
        var cmd = new java.util.ArrayList<>(GIT_NO_HELPER);
        cmd.addAll(java.util.List.of(args));
        return java.util.List.copyOf(cmd);
    }

    /** 신규 clone. 전송에만 인증 URL을 쓴다. */
    static java.util.List<String> cloneArgs(String authUrl, String branch, String dirName) {
        return git("clone", "--depth=1", "--branch", branch, authUrl, dirName);
    }

    /**
     * origin 갱신 인자. URL **선택**까지 여기서 끝낸다 — 토큰(GitRemotes)에 접근할 수 없는
     * static 순수 함수라, 인증 URL이 다시 끼어드는 회귀가 구조적으로 불가능하다.
     */
    static java.util.List<String> scrubArgs(RepoRef ref) {
        return setUrlArgs(ref.gitUrl());
    }

    /** origin 갱신. 반드시 평문 URL만 — 여기 토큰이 들어가면 config에 박힌다. */
    static java.util.List<String> setUrlArgs(String plainUrl) {
        return java.util.List.of("git", "remote", "set-url", "origin", plainUrl);
    }

    /** ensureFresh용 fetch. origin 이름 대신 인증 URL + 명시 refspec(단일 브랜치 shallow clone 대비). */
    static java.util.List<String> fetchArgs(String authUrl, String branch) {
        return git("fetch", "--prune", authUrl,
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
    }

    /** fetchOnly(배포)용 fetch. 기존 refspec 그대로, remote 이름만 인증 URL로 대체. */
    static java.util.List<String> fetchTrackingArgs(String authUrl, String headBranch) {
        return git("fetch", "--force", authUrl,
                headBranch + ":refs/remotes/origin/" + headBranch);
    }

    /**
     * repoKey별 cross-process 락. 같은 머신 다중 워커 프로세스가 동일 repo에서
     * fetch/clone/worktree-add 동시 호출하는 것을 방지.
     *
     *  - 락 파일: ~/netis-maker/repos/{localKey}.lock (repo 디렉토리 옆 형제)
     *  - JVM 내 추가 직렬화: ReentrantLock per repoKey (cheaper than file lock contention)
     *  - 락 보유 중 예외 발생해도 finally에서 안전 해제
     */
    public <T> T withRepoLock(String repoKey, RepoOp<T> op) throws IOException, InterruptedException {
        ReentrantLock jvmLock = jvmLockFor(repoKey);
        jvmLock.lock();
        try {
            Path lockPath = Paths.get(props.reposDir(), repoKey + LOCK_SUFFIX);
            Files.createDirectories(lockPath.getParent());
            try (FileChannel ch = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fileLock = acquireWithTimeout(ch, repoKey)) {
                touchLastUsed(repoKey);
                return op.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /**
     * JVM 내 락은 대소문자를 무시한 키로 공유한다. GitHub/GitLab 경로는 대소문자를 가리지 않아 같은 레포가
     * 다른 표기로 들어올 수 있고, 대소문자 무시 파일시스템(macOS 기본)에선 같은 락 파일을 가리킨다 —
     * 키가 갈리면 두 스레드가 같은 파일에 FileLock을 겹쳐 잡으려 하게 된다(정리 잡은 디스크의 실제 이름을 쓴다).
     */
    private ReentrantLock jvmLockFor(String repoKey) {
        return inProcessLocks.computeIfAbsent(repoKey.toLowerCase(java.util.Locale.ROOT), k -> new ReentrantLock());
    }

    /**
     * withRepoLock의 비차단 버전 — RepoCacheCleanupJob 전용.
     * JVM 내 ReentrantLock과 FileLock을 즉시 잡지 못하면 기다리지 않고 빈 Optional(= 사용 중, 이번 회차 건너뜀).
     * 사용 시각 마커는 갱신하지 않는다 — 정리 판단이 스스로를 '사용'으로 오인하지 않게.
     *
     * @return op 결과(null이면 안 됨). 락을 못 잡았으면 empty.
     */
    public <T> java.util.Optional<T> tryWithRepoLock(String repoKey, RepoOp<T> op)
            throws IOException, InterruptedException {
        ReentrantLock jvmLock = jvmLockFor(repoKey);
        if (!jvmLock.tryLock()) return java.util.Optional.empty();
        try {
            Path lockPath = Paths.get(props.reposDir(), repoKey + LOCK_SUFFIX);
            Files.createDirectories(lockPath.getParent());
            try (FileChannel ch = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = tryLockOrNull(ch);
                if (fileLock == null) return java.util.Optional.empty();
                try (fileLock) {
                    return java.util.Optional.of(op.run());
                }
            }
        } finally {
            jvmLock.unlock();
        }
    }

    /**
     * 마지막 사용 시각 기록. 실패해도 작업은 계속한다 — 마커가 없으면 정리 잡이 git 메타 파일의
     * mtime으로 보수적으로 판단하므로, 기록 실패가 곧바로 삭제로 이어지지 않는다.
     */
    private void touchLastUsed(String repoKey) {
        Path marker = Paths.get(props.reposDir(), repoKey + LAST_USED_SUFFIX);
        try {
            Files.writeString(marker, java.time.Instant.now().toString());
        } catch (IOException e) {
            log.warn("레포 캐시 사용 시각 기록 실패 (계속): {} — {}", marker, e.getMessage());
        }
    }

    /**
     * FileLock 즉시 시도. 다른 프로세스가 잡고 있으면 null.
     * 같은 JVM의 다른 채널이 잡고 있어도(OverlappingFileLockException — 대소문자만 다른 repoKey가
     * 대소문자 무시 파일시스템에서 같은 락 파일을 가리키는 경우 등) 예외 대신 null로 '사용 중' 취급한다.
     */
    private static FileLock tryLockOrNull(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return null;
        }
    }

    /** 60초 타임아웃 안에서 락 획득 시도. 실패 시 IOException. */
    private FileLock acquireWithTimeout(FileChannel ch, String repoKey) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            FileLock tried = tryLockOrNull(ch);
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

    private void run(File dir, String... command) throws IOException, InterruptedException {
        run(dir, java.util.List.of(command));
    }

    private void run(File dir, java.util.List<String> command) throws IOException, InterruptedException {
        log.debug("git exec ({}): {}", dir, GitRemotes.mask(String.join(" ", command)));
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
            throw new IOException("git timeout: " + GitRemotes.mask(String.join(" ", command)));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git failed (" + p.exitValue() + "): " + GitRemotes.mask(out.toString()));
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
