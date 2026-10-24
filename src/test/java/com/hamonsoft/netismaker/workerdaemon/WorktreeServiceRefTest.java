package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 구현용 참조 worktree — 고정 커밋 detached, 대상 worktree 밖 {worktree-root}/_refs/task-{id}/{카탈로그 id},
 * worktree 전용 pushurl 무효화, 기준선 대비 변경 검사, 별도 정리(스펙 2026-10-24 §6.2·§6.6·§6.8). 실제 git, file:// bare 원격.
 */
class WorktreeServiceRefTest {

    @TempDir Path tmp;

    private WorkerProperties props;
    private GitRepoCache cache;
    private WorktreeService worktrees;
    private Path bare;
    private String sha;
    private WorkerTaskResponse.TaskRefRepo ref;
    private String refKey;
    private Path cacheDir;

    private static void git(File dir, String... args) throws Exception {
        gitOut(dir, args);
    }

    private static String gitOut(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    private static int gitExit(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(dir, cmd, 60).exitCode();
    }

    @BeforeEach
    void setUp() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        Files.writeString(seed.resolve(".gitignore"), "build/\n");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        sha = gitOut(seed.toFile(), "rev-parse", "HEAD");
        bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());

        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, null, null);
        cache = new GitRepoCache(props);
        worktrees = new WorktreeService(props, cache);
        ref = new WorkerTaskResponse.TaskRefRepo(12L, "옛앱", "acme/widgets", bare.toUri().toString(), "github",
                "main", sha);
        refKey = GitRemotes.localKey(WorktreeService.repoRefOf(ref));
        cacheDir = tmp.resolve("repos").resolve(refKey);
    }

    private WorkerTaskResponse.TaskRefRepo refWith(String branch, String pinned) {
        return new WorkerTaskResponse.TaskRefRepo(ref.catalogId(), ref.alias(), ref.githubRepo(), ref.gitUrl(),
                ref.repoHost(), branch, pinned);
    }

    @Test
    void creates_a_detached_ref_worktree_at_the_pinned_commit_outside_target_trees() throws Exception {
        WorktreeService.RefWorktree rw = worktrees.createRefWorktree(7, ref);

        Path expected = tmp.resolve("worktrees").resolve("_refs").resolve("task-7").resolve("12");
        assertThat(rw.dir().toPath()).isEqualTo(expected);
        assertThat(worktrees.refWorktreeDir(7, 12)).isEqualTo(expected);
        assertThat(expected.startsWith(tmp.resolve("worktrees").resolve("acme"))).as("대상 worktree 폴더 밖").isFalse();
        assertThat(gitOut(rw.dir(), "rev-parse", "HEAD")).isEqualTo(sha);
        assertThat(gitExit(rw.dir(), "symbolic-ref", "-q", "HEAD")).as("detached HEAD").isNotZero();
        assertThat(rw.baseline()).isEmpty();
        assertThat(rw).extracting(WorktreeService.RefWorktree::catalogId, WorktreeService.RefWorktree::alias,
                        WorktreeService.RefWorktree::branch, WorktreeService.RefWorktree::sha,
                        WorktreeService.RefWorktree::repoKey)
                .containsExactly(12L, "옛앱", "main", sha, "acme/widgets");
    }

    @Test
    void only_the_ref_worktree_gets_the_blocked_push_url() throws Exception {
        WorktreeService.RefWorktree rw = worktrees.createRefWorktree(7, ref);
        File cacheRepo = cache.ensureFresh(WorktreeService.repoRefOf(ref), "main").dir();
        File target = worktrees.create(cacheRepo, rw.repoKey(), "main", 9, "target").dir();

        assertThat(gitOut(rw.dir(), "config", "--get", "remote.origin.pushurl"))
                .isEqualTo(WorktreeService.REF_PUSH_URL_BLOCKED);
        assertThat(gitExit(target, "config", "--get", "remote.origin.pushurl")).isEqualTo(1);
        assertThat(gitExit(cacheRepo, "config", "--get", "remote.origin.pushurl")).isEqualTo(1);

        assertThat(gitExit(rw.dir(), "push", "origin", "HEAD:refs/heads/x")).isNotZero();
        assertThat(gitOut(bare.toFile(), "branch", "--list", "x")).isEmpty();
    }

    /** 같은 키를 겹쳐 잡으면 60초 뒤 lock timeout, 다른 키를 겹쳐 잡으면 프로세스 사이 교착 — 참조는 자기 캐시 락 하나만. */
    @Test
    void holds_one_repo_lock_at_a_time() throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger maxDepth = new AtomicInteger();
        GitRepoCache recording = new GitRepoCache(props) {
            @Override
            public <T> T withRepoLock(String repoKey, RepoOp<T> op) throws IOException, InterruptedException {
                keys.add(repoKey);
                maxDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
                try {
                    return super.withRepoLock(repoKey, op);
                } finally {
                    depth.decrementAndGet();
                }
            }
        };

        new WorktreeService(props, recording).createRefWorktree(7, ref);

        assertThat(maxDepth.get()).isEqualTo(1);
        assertThat(keys).containsExactly(refKey);
    }

    @Test
    void recreates_over_a_leftover_folder() throws Exception {
        Path dir = worktrees.refWorktreeDir(7, 12);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("junk.txt"), "left over");

        WorktreeService.RefWorktree first = worktrees.createRefWorktree(7, ref);
        assertThat(dir.resolve("junk.txt")).doesNotExist();
        assertThat(first.dir().toPath().resolve("a.txt")).hasContent("hello");

        Files.writeString(dir.resolve("junk.txt"), "left over again");
        WorktreeService.RefWorktree second = worktrees.createRefWorktree(7, ref);

        assertThat(dir.resolve("junk.txt")).doesNotExist();
        assertThat(gitOut(second.dir(), "rev-parse", "HEAD")).isEqualTo(sha);
        assertThat(second.baseline()).isEmpty();
    }

    @Test
    void rejects_a_missing_sha_before_taking_the_lock() {
        assertThatThrownBy(() -> worktrees.createRefWorktree(7, refWith("main", null)))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("고정 커밋 정보");
        assertThatThrownBy(() -> worktrees.createRefWorktree(7, refWith("", sha)))
                .isInstanceOf(IOException.class)
                .hasMessage("참조 브랜치 정보가 없습니다");

        assertThat(tmp.resolve("repos").resolve(refKey + GitRepoCache.LOCK_SUFFIX)).doesNotExist();
        assertThat(worktrees.refWorktreeDir(7, 12)).doesNotExist();
    }

    @Test
    void reports_only_changes_made_after_the_baseline() throws Exception {
        WorktreeService.RefWorktree rw = worktrees.createRefWorktree(7, ref);
        assertThat(worktrees.refChangeWarning(rw)).isEmpty();

        Files.writeString(rw.dir().toPath().resolve("new.txt"), "x");
        Files.createDirectories(rw.dir().toPath().resolve("build"));
        Files.writeString(rw.dir().toPath().resolve("build").resolve("x"), "built");
        assertThat(worktrees.refChangeWarning(rw)).contains("옛앱: 파일 2개");

        git(rw.dir(), "add", "new.txt");
        git(rw.dir(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "oops");
        assertThat(worktrees.refChangeWarning(rw)).contains("옛앱: HEAD가 고정 커밋과 다름");
    }

    @Test
    void a_deleted_or_unreadable_ref_folder_is_reported() throws Exception {
        WorktreeService.RefWorktree rw = worktrees.createRefWorktree(7, ref);
        Path plain = Files.createDirectories(tmp.resolve("not-a-worktree"));
        var notGit = new WorktreeService.RefWorktree(12L, "옛앱", "main", sha, refKey, plain.toFile(), List.of());

        assertThat(worktrees.refChangeWarning(notGit)).contains("옛앱: 변경 확인 실패");

        worktrees.discardRefWorktree(7, ref);
        assertThat(worktrees.refChangeWarning(rw)).contains("옛앱: 참조 폴더가 지워짐");
    }

    @Test
    void change_warning_compares_with_the_baseline() {
        List<String> baseline = List.of(" M crlf.txt");

        assertThat(WorktreeService.changeWarning("옛앱", baseline, List.of(" M crlf.txt"), sha, sha)).isEmpty();
        assertThat(WorktreeService.changeWarning("옛앱", baseline,
                List.of(" M crlf.txt", "?? new.txt", "!! build/"), sha, sha))
                .contains("옛앱: 파일 2개");
        assertThat(WorktreeService.changeWarning("옛앱", baseline,
                List.of(" M crlf.txt", "?? new.txt"), sha, "f".repeat(40)))
                .as("HEAD가 다르면 파일 수보다 우선")
                .contains("옛앱: HEAD가 고정 커밋과 다름");
        assertThat(WorktreeService.changeWarning("옛앱", List.of(), List.of(), sha.toUpperCase(), sha))
                .as("sha 대소문자는 가리지 않는다").isEmpty();
    }

    /** 같은 참조를 두 작업이 함께 쓸 때 — 각자 폴더, 한쪽 정리가 다른 쪽 worktree·메타를 건드리지 않는다(Review Focus 2). */
    @Test
    void two_tasks_hold_separate_worktrees_of_the_same_ref() throws Exception {
        WorktreeService.RefWorktree seven = worktrees.createRefWorktree(7, ref);
        WorktreeService.RefWorktree eight = worktrees.createRefWorktree(8, ref);

        assertThat(seven.dir()).isNotEqualTo(eight.dir());
        assertThat(gitOut(seven.dir(), "rev-parse", "HEAD")).isEqualTo(sha);
        assertThat(gitOut(eight.dir(), "rev-parse", "HEAD")).isEqualTo(sha);
        String list = gitOut(cacheDir.toFile(), "worktree", "list", "--porcelain");
        assertThat(list).contains(seven.dir().toPath().toString().replace('\\', '/'))
                .contains(eight.dir().toPath().toString().replace('\\', '/'));

        worktrees.discardRefWorktree(7, ref);

        assertThat(worktrees.refWorktreeDir(8, 12)).isDirectory();
        assertThat(gitExit(eight.dir(), "status")).isZero();
        assertThat(gitOut(eight.dir(), "rev-parse", "HEAD")).isEqualTo(sha);
        assertThat(gitOut(cacheDir.toFile(), "worktree", "list", "--porcelain"))
                .contains(eight.dir().toPath().toString().replace('\\', '/'))
                .doesNotContain(seven.dir().toPath().toString().replace('\\', '/'));
        assertThat(worktrees.refChangeWarning(eight)).isEmpty();
    }

    @Test
    void discards_the_ref_worktree_its_metadata_and_the_empty_task_folder() throws Exception {
        WorktreeService.RefWorktree rw = worktrees.createRefWorktree(7, ref);
        String cacheHead = gitOut(cacheDir.toFile(), "rev-parse", "HEAD");

        worktrees.discardRefWorktree(7, ref);

        assertThat(rw.dir()).doesNotExist();
        assertThat(tmp.resolve("worktrees").resolve("_refs").resolve("task-7")).doesNotExist();
        assertThat(gitOut(cacheDir.toFile(), "worktree", "list", "--porcelain")).doesNotContain("task-7");
        assertThat(gitOut(cacheDir.toFile(), "rev-parse", "HEAD")).isEqualTo(cacheHead);
        assertThatCode(() -> worktrees.discardRefWorktree(7, ref)).doesNotThrowAnyException();
    }

    /** 원본 캐시를 모르는 남은 폴더(정리 잡) — git 없이 폴더만 지운다. */
    @Test
    void without_a_cache_only_the_folder_is_deleted() throws Exception {
        Path orphan = Files.createDirectories(worktrees.refWorktreeDir(9, 3));
        Files.writeString(orphan.resolve(".git"), "garbage");
        Files.writeString(orphan.resolve("left.txt"), "x");

        assertThat(worktrees.discardRefUnderLock(orphan, null)).isEqualTo(WorktreeService.DiscardResult.REMOVED);
        assertThat(orphan).doesNotExist();
        assertThat(worktrees.discardRefUnderLock(orphan, null)).isEqualTo(WorktreeService.DiscardResult.ABSENT);
    }

    @Test
    void repo_ref_follows_the_host_or_falls_back_to_the_snapshot_rule() {
        var github = WorktreeService.repoRefOf(refWith("main", sha));
        assertThat(github.host()).isEqualTo("github");
        assertThat(GitRemotes.localKey(github)).isEqualTo("acme/widgets");

        var blank = WorktreeService.repoRefOf(new WorkerTaskResponse.TaskRefRepo(1L, "a", "g/p",
                "https://gitlab.hamon.vip/g/p.git", null, "main", sha));
        assertThat(blank.host()).isEqualTo("gitlab");
        assertThat(GitRemotes.localKey(blank)).isEqualTo("_gitlab/g+p");
        assertThat(blank.gitUrl()).isEqualTo("https://gitlab.hamon.vip/g/p.git");
    }
}
