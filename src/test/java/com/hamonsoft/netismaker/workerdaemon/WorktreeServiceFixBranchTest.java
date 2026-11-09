package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수정 회차는 새 브랜치를 따지 않고 원격 브랜치 끝에서 이어 고친 뒤 같은 브랜치에 fast-forward push만 한다
 * (스펙 2026-11-03 §7). 실제 git 캐시·worktree와 file:// bare 원격으로 본다(네트워크 없음).
 */
class WorktreeServiceFixBranchTest {

    private static final String FEATURE = "netismaker/task-42-x";

    @TempDir Path tmp;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, new WorkerProperties.RepoCacheCleanup(false, null, 30), null);
    }

    private static String git(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    private static void commit(File dir, String file, String text, String msg) throws Exception {
        Files.writeString(dir.toPath().resolve(file), text);
        git(dir, "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", msg);
    }

    /** main 커밋 1개 + 그 위에 구현 커밋 1개가 있는 작업 브랜치(FEATURE). */
    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        commit(seed.toFile(), "a.txt", "hello", "init");
        git(seed.toFile(), "checkout", "-b", FEATURE);
        commit(seed.toFile(), "impl.txt", "구현", "feat: 구현");
        git(seed.toFile(), "checkout", "main");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    private record Setup(GitRepoCache cache, WorktreeService worktrees, RepoRef ref, File cacheDir, Path bare) {}

    /** 처음 구현처럼 main으로 캐시를 만들고, 배포처럼 작업 브랜치만 fetch한다. */
    private Setup setup() throws Exception {
        WorkerProperties p = props();
        GitRepoCache cache = new GitRepoCache(p);
        Path bare = bareRemote();
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        cache.ensureFresh(ref, "main");
        File cacheDir = cache.fetchOnly(ref, FEATURE).dir();
        return new Setup(cache, new WorktreeService(p, cache), ref, cacheDir, bare);
    }

    private static String remoteTip(Path bare) throws Exception {
        return git(bare.toFile(), "rev-parse", "refs/heads/" + FEATURE);
    }

    @Test
    void createOnBranch는_원격_브랜치_끝에서_detached() throws Exception {
        Setup s = setup();

        WorktreeService.CreatedWorktree wt = s.worktrees().createOnBranch(s.cacheDir(),
                GitRemotes.localKey(s.ref()), FEATURE, 42);

        assertThat(wt.branchName()).isEqualTo(FEATURE);
        assertThat(wt.dir().toPath()).isEqualTo(s.worktrees().worktreeDir(GitRemotes.localKey(s.ref()),
                WorktreeKind.TASK, 42));
        assertThat(git(wt.dir(), "rev-parse", "HEAD")).isEqualTo(remoteTip(s.bare()));
        assertThat(git(wt.dir(), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("HEAD");   // detached
        assertThat(wt.dir().toPath().resolve("impl.txt")).hasContent("구현");
        // 새 브랜치를 따지 않는다 — 캐시에 로컬 브랜치가 생기지 않는다
        assertThat(git(s.cacheDir(), "branch", "--list")).doesNotContain("task-42");
    }

    @Test
    void 같은_브랜치로_fast_forward_push된다() throws Exception {
        Setup s = setup();
        WorktreeService.CreatedWorktree wt = s.worktrees().createOnBranch(s.cacheDir(),
                GitRemotes.localKey(s.ref()), FEATURE, 42);
        GitOpsService gitOps = new GitOpsService(props());
        String tip = gitOps.headSha(wt.dir());
        assertThat(tip).isEqualTo(remoteTip(s.bare())).hasSize(40);
        Files.writeString(wt.dir().toPath().resolve("impl.txt"), "고친 구현");

        String pushed = gitOps.commitAndPush(wt.dir(), s.ref(), FEATURE, "fix: 수정 1회차 — x", tip);

        assertThat(pushed).hasSize(40).isEqualTo(remoteTip(s.bare()));
        assertThat(git(s.bare().toFile(), "rev-parse", pushed + "^")).isEqualTo(tip);
    }

    @Test
    void 원격이_앞서_있으면_push가_거절된다() throws Exception {
        Setup s = setup();
        WorktreeService.CreatedWorktree wt = s.worktrees().createOnBranch(s.cacheDir(),
                GitRemotes.localKey(s.ref()), FEATURE, 42);
        GitOpsService gitOps = new GitOpsService(props());
        String tip = gitOps.headSha(wt.dir());
        // worktree를 만든 뒤 사람이 같은 브랜치에 다른 커밋을 올렸다
        Path other = tmp.resolve("other");
        git(tmp.toFile(), "clone", "--branch", FEATURE, s.bare().toUri().toString(), other.toString());
        commit(other.toFile(), "human.txt", "사람이 고침", "사람 커밋");
        git(other.toFile(), "push", "origin", FEATURE);
        String humanTip = remoteTip(s.bare());
        Files.writeString(wt.dir().toPath().resolve("impl.txt"), "고친 구현");

        assertThatThrownBy(() -> gitOps.commitAndPush(wt.dir(), s.ref(), FEATURE, "fix: 수정 1회차 — x", tip))
                .isInstanceOf(IOException.class);
        assertThat(remoteTip(s.bare())).isEqualTo(humanTip);   // force 없음 — 사람 커밋이 그대로
    }

    /** 같은 작업의 처음 구현이 실패해 남긴 worktree(task-42)가 있어도 지우고 회차 브랜치 끝으로 다시 만든다. */
    @Test
    void 남은_구현_worktree가_있어도_회차_브랜치_끝으로_다시_만든다() throws Exception {
        Setup s = setup();
        String key = GitRemotes.localKey(s.ref());
        File old = s.worktrees().create(s.cacheDir(), key, "main", 42, "x").dir();
        Files.writeString(old.toPath().resolve("leftover.txt"), "이전 시도의 남은 파일");

        WorktreeService.CreatedWorktree wt = s.worktrees().createOnBranch(s.cacheDir(), key, FEATURE, 42);

        assertThat(wt.dir()).isEqualTo(old);
        assertThat(git(wt.dir(), "rev-parse", "HEAD")).isEqualTo(remoteTip(s.bare()));
        assertThat(wt.dir().toPath().resolve("leftover.txt")).doesNotExist();
    }

    @Test
    void headSha는_작업_폴더가_아니면_IOException() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain"));

        assertThatThrownBy(() -> new GitOpsService(props()).headSha(plain.toFile()))
                .isInstanceOf(IOException.class);
    }
}
