package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupJob.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** worktree 주기 정리를 실제 git 캐시/worktree로 검증한다. 나이는 폴더·.git mtime을 과거로 돌려 흉내 낸다. */
class WorktreeCleanupJobTest {

    @TempDir Path tmp;

    private Path worktreeRoot;
    private WorkerProperties props;
    private GitRepoCache cache;
    private WorktreeService worktrees;
    private WorkerHttpClient http;
    private WorktreeCleanupJob job;
    private String key;
    private File cacheDir;
    private RepoRef ref;
    private String headSha;

    private static void git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private WorkerProperties propsWith(Boolean enabled) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, worktreeRoot.toString(), null, null, null, null, null, null,
                null, deploy, null, new WorkerProperties.WorktreeCleanup(enabled, null, 7));
    }

    @BeforeEach
    void setUp() throws Exception {
        // 잡은 worktree-root 실경로 기준으로 결과 키를 만든다 — @TempDir가 단축·링크 경로여도 같은 기준이 되게
        worktreeRoot = Files.createDirectories(tmp.resolve("worktrees")).toRealPath();
        props = propsWith(true);
        cache = new GitRepoCache(props);
        worktrees = new WorktreeService(props, cache);
        http = mock(WorkerHttpClient.class);
        when(http.activeWorktreeTasks()).thenReturn(List.of());
        job = new WorktreeCleanupJob(props, cache, worktrees, http);

        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        key = GitRemotes.localKey(ref);
        GitRepoCache.CheckedOutRepo checkedOut = cache.ensureFresh(ref, "main");
        cacheDir = checkedOut.dir();
        headSha = checkedOut.commitSha();
    }

    private static void age(Path wt, int days) throws Exception {
        FileTime t = FileTime.from(Instant.now().minus(Duration.ofDays(days)));
        Path gitFile = wt.resolve(".git");
        if (Files.exists(gitFile)) Files.setLastModifiedTime(gitFile, t);
        Files.setLastModifiedTime(wt, t);
    }

    @Test
    void removes_old_worktrees_and_keeps_new_and_active_ones() throws Exception {
        Path oldTask = worktrees.create(cacheDir, key, "main", 1, "old").dir().toPath();
        Path oldDeploy = worktrees.createForDeploy(cacheDir, key, "main", 2).toPath();
        Path fresh = worktrees.create(cacheDir, key, "main", 3, "fresh").dir().toPath();
        Path active = worktrees.create(cacheDir, key, "main", 4, "running").dir().toPath();
        age(oldTask, 8);
        age(oldDeploy, 30);
        age(fresh, 6);
        age(active, 30);
        when(http.activeWorktreeTasks()).thenReturn(List.of(new ActiveWorktreeTaskSummary(4L, TaskStatus.IMPLEMENTING)));

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.skipped()).isFalse();
        assertThat(r.outcomes())
                .containsEntry(oldTask, Outcome.REMOVED)
                .containsEntry(oldDeploy, Outcome.REMOVED)
                .containsEntry(fresh, Outcome.TOO_NEW)
                .containsEntry(active, Outcome.ACTIVE);
        assertThat(oldTask).doesNotExist();
        assertThat(oldDeploy).doesNotExist();
        assertThat(fresh).exists();
        assertThat(active).exists();
    }

    @Test
    void active_lookup_failure_skips_the_whole_round() throws Exception {
        Path oldTask = worktrees.create(cacheDir, key, "main", 1, "old").dir().toPath();
        age(oldTask, 30);
        when(http.activeWorktreeTasks()).thenThrow(new RestClientException("404 Not Found"));

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.skipped()).isTrue();
        assertThat(r.outcomes()).isEmpty();
        assertThat(oldTask).exists();
    }

    @Test
    void counts_locked_worktrees_without_deleting() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 5, "keep").dir().toPath();
        git(cacheDir, "worktree", "lock", wt.toString());
        age(wt, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(wt, Outcome.GIT_LOCKED);
        assertThat(wt).exists();
    }

    @Test
    void a_busy_repo_lock_skips_that_worktree_this_round() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 6, "busy").dir().toPath();
        age(wt, 30);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                cache.withRepoLock(key, () -> { held.countDown(); release.await(); return null; });
            } catch (Exception ignored) { }
        });
        holder.start();
        held.await();
        try {
            WorktreeCleanupJob.Result r = job.runOnce();
            assertThat(r.outcomes()).containsEntry(wt, Outcome.BUSY);
            assertThat(wt).exists();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void rejudges_inside_the_repo_lock() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 7, "retry").dir().toPath();
        age(wt, 30);
        // 락 밖 1차 판정(=REMOVE)과 락 획득 사이에 재시도가 worktree를 새로 만든 상황:
        // 락을 잡기 직전에 mtime을 지금으로 바꾸는 GitRepoCache로 끼워 넣는다
        GitRepoCache racing = new GitRepoCache(props) {
            @Override
            public <T> Optional<T> tryWithRepoLock(String repoKey, RepoOp<T> op)
                    throws IOException, InterruptedException {
                Files.setLastModifiedTime(wt, FileTime.from(Instant.now()));
                return super.tryWithRepoLock(repoKey, op);
            }
        };
        WorktreeCleanupJob racingJob = new WorktreeCleanupJob(props, racing, new WorktreeService(props, racing), http);

        WorktreeCleanupJob.Result r = racingJob.runOnce();

        assertThat(r.outcomes()).containsEntry(wt, Outcome.TOO_NEW);
        assertThat(wt).exists();
    }

    @Test
    void ignores_unrecognized_names() throws Exception {
        Path odd = Files.createDirectories(worktreeRoot.resolve("acme/widgets/task-01"));
        Path notes = Files.createDirectories(worktreeRoot.resolve("acme/widgets/notes"));
        age(odd, 30);
        age(notes, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).doesNotContainKeys(odd, notes);
        assertThat(odd).exists();
        assertThat(notes).exists();
    }

    @Test
    void cleans_repos_whose_name_starts_with_a_dot_but_not_dot_entries_at_the_root() throws Exception {
        // owner/.github 같은 점으로 시작하는 레포 이름도 정리 대상 — localKey는 레포 경로 그대로다
        Path dotRepo = Files.createDirectories(worktreeRoot.resolve("acme/.github/task-5"));
        Files.writeString(dotRepo.resolve("left.txt"), "x");
        age(dotRepo, 30);
        // worktree-root 직하의 점 항목은 잡 내부용(락 파일 등)이라 후보에서 뺀다
        Path rootDot = Files.createDirectories(worktreeRoot.resolve(".internal/repo/task-6"));
        age(rootDot, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(dotRepo, Outcome.REMOVED).doesNotContainKey(rootDot);
        assertThat(dotRepo).doesNotExist();
        assertThat(rootDot).exists();
    }

    @Test
    void ignores_symlinks() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside/keep"));
        Files.writeString(outside.resolve("precious.txt"), "x");
        Path link = worktreeRoot.resolve("acme/widgets/task-77");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            assumeTrue(false, "심볼릭 링크 권한 없음(Windows 개발자 모드 꺼짐)");
        }

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).doesNotContainKey(link);
        assertThat(outside.resolve("precious.txt")).exists();
    }

    @Test
    void orphan_worktree_without_cache_is_deleted_directly() throws Exception {
        Path orphan = Files.createDirectories(worktreeRoot.resolve("ghost/repo/design-9"));
        Files.writeString(orphan.resolve("left.txt"), "x");
        age(orphan, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(orphan, Outcome.REMOVED);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void another_process_holding_the_job_lock_skips_the_round() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 8, "x").dir().toPath();
        age(wt, 30);
        try (FileChannel ch = FileChannel.open(worktreeRoot.resolve(WorktreeCleanupJob.JOB_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            WorktreeCleanupJob.Result r = job.runOnce();
            assertThat(r.skipped()).isTrue();
        }
        assertThat(wt).exists();
    }

    @Test
    void disabled_job_does_nothing() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 9, "x").dir().toPath();
        age(wt, 30);
        WorktreeCleanupJob disabled = new WorktreeCleanupJob(propsWith(false), cache, worktrees, http);

        disabled.cleanup();

        assertThat(wt).exists();
        verify(http, never()).activeWorktreeTasks();
    }

    // ── 참조 worktree: {worktree-root}/_refs/task-{id}/{카탈로그 id} (스펙 2026-10-24 §6.8) ──

    /** setUp 캐시(acme/widgets)를 원본으로 쓰는 참조 worktree — repoHost "github"이라 캐시 키가 key와 같다. */
    private Path refWorktree(long taskId) throws Exception {
        return worktrees.createRefWorktree(taskId, new WorkerTaskResponse.TaskRefRepo(12L, "옛앱", "acme/widgets",
                ref.gitUrl(), "github", "main", headSha)).dir().toPath();
    }

    private Path refsDir(long taskId) {
        return worktreeRoot.resolve(WorktreeService.REFS_DIR).resolve("task-" + taskId);
    }

    /** 캐시가 아는 worktree 경로들(git worktree list --porcelain). */
    private List<Path> cachedWorktrees() throws Exception {
        String out = ProcessRunner.requireSuccess(cacheDir, List.of("git", "worktree", "list", "--porcelain"), 60);
        return out.lines().filter(l -> l.startsWith("worktree "))
                .map(l -> Paths.get(l.substring("worktree ".length())).normalize())
                .toList();
    }

    /** 원본 캐시를 찾을 수 없는 참조 폴더 세 모양 — .git 없음 / 캐시가 repos 밖·없음 / 형식 이상. */
    private List<Path> cachelessRefs(long taskId) throws Exception {
        Path noGit = Files.createDirectories(refsDir(taskId).resolve("3"));
        Files.writeString(noGit.resolve("left.txt"), "x");
        Path nowhere = Files.createDirectories(refsDir(taskId).resolve("4"));
        Files.writeString(nowhere.resolve(".git"),
                "gitdir: " + tmp.resolve("nowhere/.git/worktrees/4").toString().replace('\\', '/') + "\n");
        Path garbage = Files.createDirectories(refsDir(taskId).resolve("5"));
        Files.writeString(garbage.resolve(".git"), "garbage");
        return List.of(noGit, nowhere, garbage);
    }

    /** repos-dir 아래 락 파일(git 내부 .git/** 제외) — 가짜 키로 잡은 락이 없는지 본다. */
    private List<Path> repoLockFiles() throws Exception {
        Path repos = tmp.resolve("repos");
        try (var walk = Files.walk(repos)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(GitRepoCache.LOCK_SUFFIX))
                    .filter(p -> !repos.relativize(p).toString().replace('\\', '/').contains(".git/"))
                    .map(repos::relativize)
                    .toList();
        }
    }

    @Test
    void removes_old_ref_worktrees_through_their_source_cache() throws Exception {
        Path refDir = refWorktree(5);
        age(refDir, 30);
        assertThat(cachedWorktrees()).contains(refDir);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(refDir, Outcome.REMOVED);
        assertThat(refDir).doesNotExist();
        assertThat(refsDir(5)).doesNotExist();
        assertThat(cachedWorktrees()).doesNotContain(refDir);
        assertThat(tmp.resolve("repos").resolve(WorktreeService.REFS_DIR)).as("가짜 락 키 없음").doesNotExist();
    }

    @Test
    void keeps_ref_worktrees_of_active_or_recent_tasks() throws Exception {
        Path active = refWorktree(6);
        Path recent = refWorktree(7);
        age(active, 30);
        age(recent, 3);
        when(http.activeWorktreeTasks()).thenReturn(List.of(new ActiveWorktreeTaskSummary(6L, TaskStatus.IMPLEMENTING)));

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry(active, Outcome.ACTIVE)
                .containsEntry(recent, Outcome.TOO_NEW);
        assertThat(active).exists();
        assertThat(recent).exists();
    }

    /** 같은 참조를 두 작업이 쓰면 캐시 메타 이름에 번호가 붙는다(12, 121) — 한쪽 정리가 다른 쪽 worktree를 건드리지 않는다. */
    @Test
    void removing_one_tasks_ref_leaves_another_tasks_ref_of_the_same_repo() throws Exception {
        Path kept = refWorktree(5);
        Path old = refWorktree(6);
        assertThat(Files.readString(old.resolve(".git"))).as("메타 이름이 서로 다름")
                .isNotEqualTo(Files.readString(kept.resolve(".git")));
        age(kept, 3);
        age(old, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry(old, Outcome.REMOVED)
                .containsEntry(kept, Outcome.TOO_NEW);
        assertThat(old).doesNotExist();
        assertThat(cachedWorktrees()).contains(kept).doesNotContain(old);
        assertThat(ProcessRunner.requireSuccess(kept.toFile(), List.of("git", "rev-parse", "HEAD"), 60).trim())
                .isEqualTo(headSha);
    }

    @Test
    void a_busy_source_cache_skips_the_ref_this_round() throws Exception {
        Path refDir = refWorktree(5);
        age(refDir, 30);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                cache.withRepoLock(key, () -> { held.countDown(); release.await(); return null; });
            } catch (Exception ignored) { }
        });
        holder.start();
        held.await();
        try {
            WorktreeCleanupJob.Result r = job.runOnce();
            assertThat(r.outcomes()).containsEntry(refDir, Outcome.BUSY);
            assertThat(refDir).exists();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void deletes_only_the_folder_when_the_source_cache_cannot_be_found() throws Exception {
        List<Path> refs = cachelessRefs(9);
        for (Path p : refs) age(p, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        for (Path p : refs) {
            assertThat(r.outcomes()).containsEntry(p, Outcome.REMOVED);
            assertThat(p).doesNotExist();
        }
        assertThat(refsDir(9)).doesNotExist();
        assertThat(repoLockFiles()).containsExactly(Paths.get("acme", "widgets" + GitRepoCache.LOCK_SUFFIX));
        assertThat(tmp.resolve("repos").resolve(WorktreeService.REFS_DIR)).doesNotExist();
    }

    @Test
    void keeps_cacheless_ref_folders_of_active_or_recent_tasks() throws Exception {
        List<Path> active = cachelessRefs(10);
        List<Path> recent = cachelessRefs(11);
        for (Path p : active) age(p, 30);
        for (Path p : recent) age(p, 3);
        when(http.activeWorktreeTasks()).thenReturn(List.of(new ActiveWorktreeTaskSummary(10L, TaskStatus.IMPLEMENTING)));

        WorktreeCleanupJob.Result r = job.runOnce();

        for (Path p : active) {
            assertThat(r.outcomes()).containsEntry(p, Outcome.ACTIVE);
            assertThat(p).exists();
        }
        for (Path p : recent) {
            assertThat(r.outcomes()).containsEntry(p, Outcome.TOO_NEW);
            assertThat(p).exists();
        }
    }

    @Test
    void ignores_unrecognized_names_under_refs() throws Exception {
        Path refs = worktreeRoot.resolve(WorktreeService.REFS_DIR);
        List<Path> odd = List.of(
                Files.createDirectories(refs.resolve("notes")),
                Files.createDirectories(refs.resolve("task-01/1")),
                Files.createDirectories(refs.resolve("design-5/1")),
                Files.createDirectories(refs.resolve("task-5/x")));
        for (Path p : odd) age(p, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).doesNotContainKeys(odd.toArray(Path[]::new));
        for (Path p : odd) assertThat(p).exists();
    }

    @Test
    void the_target_scan_does_not_enter_refs() throws Exception {
        Path target = worktrees.create(cacheDir, key, "main", 1, "old").dir().toPath();
        Path refDir = refWorktree(1);
        // 대상 스캔이 _refs에 들어가면 이 폴더를 localKey "_refs/task-1"의 task-2로 읽어 가짜 키로 락을 잡는다
        Path lookalike = Files.createDirectories(refsDir(1).resolve("task-2"));
        age(target, 8);
        age(refDir, 8);
        age(lookalike, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry(worktreeRoot.resolve("acme/widgets/task-1"), Outcome.REMOVED)
                .containsEntry(refDir, Outcome.REMOVED)
                .doesNotContainKey(lookalike);
        assertThat(target).doesNotExist();
        assertThat(refDir).doesNotExist();
        assertThat(lookalike).as("이름이 카탈로그 id가 아님").exists();
        assertThat(tmp.resolve("repos").resolve(WorktreeService.REFS_DIR)).as("가짜 락 키 없음").doesNotExist();
    }
}
