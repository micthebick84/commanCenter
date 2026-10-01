package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WorktreeService 제거 절차를 실제 git(file:// bare 원격)으로 검증. */
class WorktreeServiceDiscardTest {

    @TempDir Path tmp;

    private WorkerProperties props;
    private GitRepoCache cache;
    private WorktreeService worktrees;
    private RepoRef ref;
    private String key;
    private File cacheDir;

    private static void git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private static String gitOut(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());

        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, null);
        cache = new GitRepoCache(props);
        worktrees = new WorktreeService(props, cache);
        ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        key = GitRemotes.localKey(ref);
        cacheDir = cache.ensureFresh(ref, "main").dir();
    }

    @Test
    void discards_a_task_worktree_its_metadata_and_its_local_branch() throws Exception {
        WorktreeService.CreatedWorktree wt = worktrees.create(cacheDir, key, "main", 7, "Add login");

        worktrees.discard(key, WorktreeKind.TASK, 7);

        assertThat(wt.dir()).doesNotExist();
        assertThat(gitOut(cacheDir, "worktree", "list", "--porcelain")).doesNotContain("task-7");
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-7*")).isBlank();
    }

    @Test
    void deletes_only_this_tasks_branches() throws Exception {
        worktrees.create(cacheDir, key, "main", 1, "one");
        worktrees.create(cacheDir, key, "main", 12, "twelve");

        worktrees.discard(key, WorktreeKind.TASK, 1);

        assertThat(worktrees.worktreeDir(key, WorktreeKind.TASK, 1)).doesNotExist();
        assertThat(worktrees.worktreeDir(key, WorktreeKind.TASK, 12)).exists();
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-12-twelve")).contains("task-12-twelve");
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-1-one")).isBlank();
    }

    @Test
    void discards_a_deploy_worktree() throws Exception {
        File wt = worktrees.createForDeploy(cacheDir, key, "main", 3);

        worktrees.discard(key, WorktreeKind.DEPLOY, 3);

        assertThat(wt).doesNotExist();
        assertThat(gitOut(cacheDir, "worktree", "list", "--porcelain")).doesNotContain("deploy-3");
    }

    @Test
    void keeps_a_git_locked_worktree() throws Exception {
        WorktreeService.CreatedWorktree wt = worktrees.create(cacheDir, key, "main", 5, "keep me");
        git(cacheDir, "worktree", "lock", wt.dir().getAbsolutePath());

        WorktreeService.DiscardResult r = cache.withRepoLock(key,
                () -> worktrees.discardUnderLock(key, WorktreeKind.TASK, 5));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.GIT_LOCKED);
        assertThat(wt.dir()).exists();
    }

    @Test
    void absent_worktree_is_reported_absent() throws Exception {
        WorktreeService.DiscardResult r = cache.withRepoLock(key,
                () -> worktrees.discardUnderLock(key, WorktreeKind.DESIGN, 99));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.ABSENT);
    }

    @Test
    void without_a_cache_the_folder_is_deleted_directly() throws Exception {
        Path orphan = Files.createDirectories(worktrees.worktreeDir("ghost/repo", WorktreeKind.TASK, 4));
        Files.writeString(orphan.resolve("left.txt"), "x");

        WorktreeService.DiscardResult r = cache.withRepoLock("ghost/repo",
                () -> worktrees.discardUnderLock("ghost/repo", WorktreeKind.TASK, 4));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.REMOVED);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void discard_never_throws() {
        // 캐시도 폴더도 없는 키 — 경고만 남기고 조용히 끝나야 한다
        worktrees.discard("nobody/nothing", WorktreeKind.TASK, 1);
    }
}
