package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** fetchPatchCommits / WorktreeService.createAtCommit — file:// bare 원격. */
class GitRepoCachePatchTest {

    @TempDir Path tmp;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, new WorkerProperties.RepoCacheCleanup(false, null, 30), null);
    }

    private static String git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false",
                "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    private String commit(Path seed, String file, String text) throws Exception {
        Files.writeString(seed.resolve(file), text);
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "commit", "-m", file);
        git(seed.toFile(), "push", tmp.resolve("remote.git").toString(), "main");
        return git(seed.toFile(), "rev-parse", "HEAD");
    }

    private Path seedWithRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "commit", "-m", "init");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), tmp.resolve("remote.git").toString());
        return seed;
    }

    private RepoRef ref() {
        return new RepoRef("github", "acme/widgets", tmp.resolve("remote.git").toUri().toString());
    }

    @Test
    void fetchesAfterShaIntoMissingCacheWithParent() throws Exception {
        Path seed = seedWithRemote();
        String c1 = git(seed.toFile(), "rev-parse", "HEAD");
        String c2 = commit(seed, "b.txt", "two");
        GitRepoCache cache = new GitRepoCache(props());

        File dir = cache.fetchPatchCommits(ref(), "main", c2);

        assertThat(dir.toPath()).isEqualTo(tmp.resolve("repos").resolve(GitRemotes.localKey(ref())));
        assertThat(git(dir, "cat-file", "-t", c2)).isEqualTo("commit");
        assertThat(git(dir, "cat-file", "-t", c1)).isEqualTo("commit");
        assertThat(git(dir, "remote", "get-url", "origin")).isEqualTo(ref().gitUrl());
    }

    @Test
    void existingCacheKeepsOriginRefsAndHead() throws Exception {
        Path seed = seedWithRemote();
        GitRepoCache cache = new GitRepoCache(props());
        File dir = cache.ensureFresh(ref(), "main").dir();
        String headBefore = git(dir, "rev-parse", "HEAD");
        String originBefore = git(dir, "rev-parse", "refs/remotes/origin/main");
        String c2 = commit(seed, "b.txt", "two");

        cache.fetchPatchCommits(ref(), "main", c2);

        assertThat(git(dir, "cat-file", "-t", c2)).isEqualTo("commit");
        assertThat(git(dir, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(git(dir, "rev-parse", "refs/remotes/origin/main")).isEqualTo(originBefore);
    }

    @Test
    void missingShaFailsWithReason() throws Exception {
        seedWithRemote();
        GitRepoCache cache = new GitRepoCache(props());
        assertThatThrownBy(() -> cache.fetchPatchCommits(ref(), "main", "0123456789abcdef0123456789abcdef01234567"))
                .isInstanceOf(IOException.class)
                .hasMessage("병합 커밋을 받지 못했습니다");
    }

    @Test
    void invalidShaIsRejectedBeforeGit() {
        GitRepoCache cache = new GitRepoCache(props());
        assertThatThrownBy(() -> cache.fetchPatchCommits(ref(), "main", "--upload-pack=x"))
                .isInstanceOf(IOException.class);
        assertThat(tmp.resolve("repos")).doesNotExist();
    }

    @Test
    void createAtCommitMakesDetachedWorktreeOfKind() throws Exception {
        Path seed = seedWithRemote();
        String c1 = git(seed.toFile(), "rev-parse", "HEAD");
        String c2 = commit(seed, "b.txt", "two");
        WorkerProperties p = props();
        GitRepoCache cache = new GitRepoCache(p);
        WorktreeService worktrees = new WorktreeService(p, cache);
        File cacheDir = cache.fetchPatchCommits(ref(), "main", c2);
        String key = GitRemotes.localKey(ref());

        File before = worktrees.createAtCommit(cacheDir, key, WorktreeKind.PATCH_BEFORE, 7, c1);
        File after = worktrees.createAtCommit(cacheDir, key, WorktreeKind.PATCH_AFTER, 7, c2);

        assertThat(before.toPath()).isEqualTo(worktrees.worktreeDir(key, WorktreeKind.PATCH_BEFORE, 7));
        assertThat(git(before, "rev-parse", "HEAD")).isEqualTo(c1);
        assertThat(git(after, "rev-parse", "HEAD")).isEqualTo(c2);
        assertThat(before.toPath().resolve("b.txt")).doesNotExist();
        assertThat(after.toPath().resolve("b.txt")).exists();
        // 멱등 — 다시 만들어도 된다
        assertThat(worktrees.createAtCommit(cacheDir, key, WorktreeKind.PATCH_BEFORE, 7, c2)).isEqualTo(before);
        assertThat(git(before, "rev-parse", "HEAD")).isEqualTo(c2);
        worktrees.discard(key, WorktreeKind.PATCH_BEFORE, 7);
        assertThat(before.toPath()).doesNotExist();
    }
}
