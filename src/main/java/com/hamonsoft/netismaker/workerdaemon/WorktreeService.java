package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 구현 단계의 worktree 라이프사이클.
 *
 *  레이아웃:
 *    ~/netis-maker/repos/{owner}/{repo}        ← GitRepoCache (분석 + 베이스 fetch)
 *    ~/netis-maker/worktrees/{owner}/{repo}/{slug}  ← 본 서비스
 *
 *  브랜치 컨벤션:
 *    netismaker/task-{id}-{slug-of-title}
 *
 *  멱등성: 같은 task를 다시 시도하면 기존 worktree 제거 후 새로 생성. 디스크에는 항상 단일 인스턴스.
 *  정리: 성공한 작업은 호출자가 끝에 discard로 지운다(구현=PR 생성 뒤, 배포=빌드·실행 뒤, 디자인=수확 뒤).
 *  실패분은 디버그용으로 남기고 WorktreeCleanupJob이 보존 기간(기본 7일) 뒤 지운다.
 */
@Component
@Profile("worker")
@Slf4j
public class WorktreeService {

    private static final long GIT_TIMEOUT_SECONDS = 600;

    private final WorkerProperties props;
    private final GitRepoCache repos;

    public WorktreeService(WorkerProperties props, GitRepoCache repos) {
        this.props = props;
        this.repos = repos;
    }

    /**
     * task용 worktree를 새 브랜치로 생성. 기존 path/branch가 있으면 강제로 정리 후 재생성.
     *
     * 다중 워커 동시성: GitRepoCache.withRepoLock으로 보호.
     * 같은 머신의 두 워커가 동일 repo의 다른 task를 동시에 처리할 때
     * `.git/worktrees/` 메타 파일 동시 수정 race 방지.
     *
     * @param repoCacheDir GitRepoCache.ensureFresh가 반환한 디렉토리 (origin/{baseBranch} 최신화 상태)
     * @param repoKey      GitRemotes.localKey 값 — worktree path 구성용
     * @param baseBranch   분석 대상 브랜치 (origin/{baseBranch}을 분기점으로)
     * @param taskId       브랜치명에 포함
     * @param title        브랜치명 슬러그 생성용
     * @return 생성된 worktree 디렉토리 + 브랜치명
     */
    public CreatedWorktree create(File repoCacheDir, String repoKey,
                                  String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreate(repoCacheDir, repoKey, baseBranch, taskId, title));
    }

    private CreatedWorktree doCreate(File repoCacheDir, String repoKey,
                                     String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        String slug = sanitizeForBranch(title);
        String branchName = props.branchPrefix() + "task-" + taskId
                + (slug.isEmpty() ? "" : "-" + slug);

        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.TASK, taskId);
        Files.createDirectories(worktreeDir.getParent());

        // 멱등성: 같은 task를 재시도하는 경우 기존 worktree/브랜치 정리
        if (Files.exists(worktreeDir)) {
            log.warn("기존 worktree 발견, 강제 제거: {}", worktreeDir);
            try {
                ProcessRunner.run(repoCacheDir, List.of("git", "worktree", "remove", "--force",
                        worktreeDir.toString()), GIT_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("worktree remove 실패 (계속): {}", e.getMessage());
            }
            deleteRecursively(worktreeDir.toFile());
        }
        // 같은 이름 브랜치가 캐시에 남아있으면 제거 (워크트리 없는 dangling 브랜치)
        try {
            ProcessRunner.run(repoCacheDir, List.of("git", "branch", "-D", branchName),
                    GIT_TIMEOUT_SECONDS);
        } catch (Exception ignore) {
            // 없으면 fail — 정상
        }

        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "-b", branchName,
                        worktreeDir.toString(), "origin/" + baseBranch),
                GIT_TIMEOUT_SECONDS);

        log.info("worktree 생성: task={} branch={} dir={}", taskId, branchName, worktreeDir);
        return new CreatedWorktree(worktreeDir.toFile(), branchName);
    }

    /**
     * 배포용 worktree 생성. 구현용 create와 달리 새 브랜치를 만들지 않고
     * 기존 head 브랜치(origin/{headBranch})를 detached 체크아웃한다 (빌드만 필요).
     * 멱등: 기존 deploy worktree 있으면 강제 제거 후 재생성.
     *
     * @param repoCacheDir GitRepoCache.ensureFresh가 반환한 디렉토리 (origin/{headBranch} fetch 상태)
     * @param repoKey      GitRemotes.localKey 값
     * @param headBranch   배포 대상 브랜치 (PR head)
     * @param taskId       worktree 경로 구성용
     * @return 생성된 worktree 디렉토리 (브랜치명은 의미 없어 dir만 사용)
     */
    public File createForDeploy(File repoCacheDir, String repoKey,
                                String headBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreateForDeploy(repoCacheDir, repoKey, headBranch, taskId));
    }

    private File doCreateForDeploy(File repoCacheDir, String repoKey,
                                   String headBranch, long taskId)
            throws IOException, InterruptedException {
        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.DEPLOY, taskId);
        Files.createDirectories(worktreeDir.getParent());

        if (Files.exists(worktreeDir)) {
            log.warn("기존 deploy worktree 발견, 강제 제거: {}", worktreeDir);
            try {
                ProcessRunner.run(repoCacheDir, List.of("git", "worktree", "remove", "--force",
                        worktreeDir.toString()), GIT_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("worktree remove 실패 (계속): {}", e.getMessage());
            }
            deleteRecursively(worktreeDir.toFile());
        }

        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "--force", "--detach",
                        worktreeDir.toString(), "origin/" + headBranch),
                GIT_TIMEOUT_SECONDS);

        log.info("deploy worktree 생성: task={} branch={} dir={}", taskId, headBranch, worktreeDir);
        return worktreeDir.toFile();
    }

    /**
     * 디자인용 worktree. 새 브랜치 없이 origin/{baseBranch} detached 체크아웃 (목업 생성만, push 없음).
     * 경로 design-{id} — 구현 worktree(task-{id})와 분리되어 재실행/후속 구현과 충돌 없음.
     */
    public File createForDesign(File repoCacheDir, String repoKey,
                                String baseBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreateForDesign(repoCacheDir, repoKey, baseBranch, taskId));
    }

    private File doCreateForDesign(File repoCacheDir, String repoKey,
                                   String baseBranch, long taskId)
            throws IOException, InterruptedException {
        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.DESIGN, taskId);
        Files.createDirectories(worktreeDir.getParent());
        if (Files.exists(worktreeDir)) {
            log.warn("기존 design worktree 발견, 강제 제거: {}", worktreeDir);
            try {
                ProcessRunner.run(repoCacheDir, List.of("git", "worktree", "remove", "--force",
                        worktreeDir.toString()), GIT_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("worktree remove 실패 (계속): {}", e.getMessage());
            }
            deleteRecursively(worktreeDir.toFile());
        }
        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "--force", "--detach",
                        worktreeDir.toString(), "origin/" + baseBranch),
                GIT_TIMEOUT_SECONDS);
        log.info("design worktree 생성: task={} dir={}", taskId, worktreeDir);
        return worktreeDir.toFile();
    }

    public Path worktreeDir(String repoKey, WorktreeKind kind, long taskId) {
        return Paths.get(props.worktreeRoot(), repoKey, kind.dirName(taskId));
    }

    public enum DiscardResult { REMOVED, ABSENT, GIT_LOCKED }

    /**
     * 작업이 끝난 worktree 정리(best-effort) — 레포 락 안에서 discardUnderLock.
     * 실패는 경고만 남기고 삼킨다: 정리 실패가 작업 결과를 바꾸면 안 되고, 남은 폴더는
     * WorktreeCleanupJob이 보존 기간 뒤 다시 시도한다.
     */
    public void discard(String repoKey, WorktreeKind kind, long taskId) {
        try {
            DiscardResult r = repos.withRepoLock(repoKey, () -> discardUnderLock(repoKey, kind, taskId));
            if (r == DiscardResult.GIT_LOCKED) {
                log.info("worktree 정리 건너뜀(git worktree lock): {}", worktreeDir(repoKey, kind, taskId));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("worktree 정리 실패(주기 정리가 다시 시도): {} ({})",
                    worktreeDir(repoKey, kind, taskId), e.getMessage());
        }
    }

    /**
     * 제거 절차. 호출자가 repoKey의 레포 락을 쥐고 있어야 한다(WorktreeCleanupJob은 tryWithRepoLock 안에서 부른다).
     *  1) git worktree lock이 걸려 있으면 손대지 않는다(사람이 일부러 남긴 것)
     *  2) 캐시가 있으면 git worktree remove --force
     *  3) 폴더가 남았으면 링크를 따라가지 않는 재귀 삭제(Windows 읽기 전용 포함) — 하나라도 실패하면 IOException
     *  4) 캐시가 있으면 git worktree prune, 구현 worktree면 그 작업의 로컬 브랜치 삭제(이미 원격에 푸시됨)
     */
    public DiscardResult discardUnderLock(String repoKey, WorktreeKind kind, long taskId)
            throws IOException, InterruptedException {
        Path dir = worktreeDir(repoKey, kind, taskId);
        Path cache = Paths.get(props.reposDir(), repoKey);
        boolean hasCache = Files.isDirectory(cache.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        boolean present = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);

        if (present && isGitLocked(dir)) return DiscardResult.GIT_LOCKED;
        if (present && hasCache) {
            ProcessRunner.Result r = ProcessRunner.run(cache.toFile(),
                    List.of("git", "worktree", "remove", "--force", dir.toString()), GIT_TIMEOUT_SECONDS);
            if (r.exitCode() != 0) log.debug("git worktree remove 실패(폴더 직접 삭제로 진행): {}", r.stdout());
        }
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
            if (d.failures() > 0) {
                throw new IOException("worktree 폴더 삭제 실패 " + d.failures() + "건: " + dir);
            }
        }
        if (hasCache) {
            ProcessRunner.run(cache.toFile(), List.of("git", "worktree", "prune"), GIT_TIMEOUT_SECONDS);
            if (kind == WorktreeKind.TASK) deleteTaskBranches(cache.toFile(), taskId);
        }
        if (present) log.info("worktree 정리: {}", dir);
        return present ? DiscardResult.REMOVED : DiscardResult.ABSENT;
    }

    /**
     * {branch-prefix}task-{id} 와 {branch-prefix}task-{id}-{slug} 만 지운다 — task-1 정리가 task-12-…를 지우지 않게
     * '-' 경계로 매칭한다(doCreate의 브랜치 규칙과 같다). 다른 worktree가 체크아웃 중이면 git이 거부 → 경고만.
     */
    private void deleteTaskBranches(File cacheDir, long taskId) throws IOException, InterruptedException {
        String base = "refs/heads/" + props.branchPrefix() + "task-" + taskId;
        String out = ProcessRunner.requireSuccess(cacheDir,
                List.of("git", "for-each-ref", "--format=%(refname:short)", base, base + "-*"),
                GIT_TIMEOUT_SECONDS);
        String exact = props.branchPrefix() + "task-" + taskId;
        for (String branch : out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!branch.equals(exact) && !branch.startsWith(exact + "-")) continue;
            ProcessRunner.Result r = ProcessRunner.run(cacheDir, List.of("git", "branch", "-D", branch),
                    GIT_TIMEOUT_SECONDS);
            if (r.exitCode() != 0) log.warn("로컬 브랜치 삭제 실패: {} ({})", branch, r.stdout().trim());
        }
    }

    /**
     * worktree의 .git 파일이 가리키는 메타 디렉터리에 locked 표시가 있으면 true.
     * .git이 없으면 false. 읽을 수 없거나 형식이 다르면 true — 모르면 안 지운다.
     * gitdir은 절대경로 또는(git 2.48+ relative 모드) worktree 기준 상대경로.
     */
    static boolean isGitLocked(Path worktreeDir) {
        Path gitFile = worktreeDir.resolve(".git");
        if (Files.notExists(gitFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String line = Files.readString(gitFile).trim();
            if (!line.startsWith("gitdir:")) return true;
            Path meta = worktreeDir.resolve(line.substring("gitdir:".length()).trim()).normalize();
            return !Files.notExists(meta.resolve("locked"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** 브랜치명에 들어갈 슬러그. 영문/숫자/-/_ 만 통과, 30자 제한. */
    static String sanitizeForBranch(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean lastDash = false;
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastDash = false;
            } else if (c == '-' || c == '_') {
                out.append(c);
                lastDash = (c == '-');
            } else if (Character.isWhitespace(c) || c == '/' || c == '\\') {
                if (!lastDash && out.length() > 0) {
                    out.append('-');
                    lastDash = true;
                }
            }
            // 그 외(한글, 특수문자)는 생략
            if (out.length() >= 30) break;
        }
        // 양끝 - 제거
        while (out.length() > 0 && out.charAt(0) == '-') out.deleteCharAt(0);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        // 심볼릭 링크는 타깃을 따라가지 않고 링크 자체만 제거 (워크트리 밖 삭제 방지 — WorkerMainLoop과 동일)
        boolean symlink = java.nio.file.Files.isSymbolicLink(f.toPath());
        if (!symlink && !f.exists()) return;
        if (!symlink && f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        if (!f.delete()) {
            log.warn("파일 삭제 실패: {}", f);
        }
    }

    public record CreatedWorktree(File dir, String branchName) {}
}
