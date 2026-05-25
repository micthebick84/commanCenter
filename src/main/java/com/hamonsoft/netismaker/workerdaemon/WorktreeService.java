package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
 *  cleanup은 호출자(WorkerMainLoop)가 PR 생성 후 보존 정책에 따라 결정. Phase 1은 보존(admin 디버그).
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
     * @param githubRepo   "owner/repo" — worktree path 구성용
     * @param baseBranch   분석 대상 브랜치 (origin/{baseBranch}을 분기점으로)
     * @param taskId       브랜치명에 포함
     * @param title        브랜치명 슬러그 생성용
     * @return 생성된 worktree 디렉토리 + 브랜치명
     */
    public CreatedWorktree create(File repoCacheDir, String githubRepo,
                                  String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        return repos.withRepoLock(githubRepo,
                () -> doCreate(repoCacheDir, githubRepo, baseBranch, taskId, title));
    }

    private CreatedWorktree doCreate(File repoCacheDir, String githubRepo,
                                     String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        String slug = sanitizeForBranch(title);
        String branchName = props.branchPrefix() + "task-" + taskId
                + (slug.isEmpty() ? "" : "-" + slug);

        Path worktreeDir = Paths.get(props.worktreeRoot(), githubRepo, "task-" + taskId);
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
     * worktree 제거. 실패해도 예외 던지지 않음 (보존 우선, 호출자가 best-effort 정리 시 사용).
     */
    public void remove(File repoCacheDir, File worktreeDir) {
        try {
            ProcessRunner.run(repoCacheDir,
                    List.of("git", "worktree", "remove", "--force", worktreeDir.getAbsolutePath()),
                    GIT_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("worktree remove 실패: {} ({})", worktreeDir, e.getMessage());
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
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        if (!f.delete()) {
            log.warn("파일 삭제 실패: {}", f);
        }
    }

    public record CreatedWorktree(File dir, String branchName) {}
}
