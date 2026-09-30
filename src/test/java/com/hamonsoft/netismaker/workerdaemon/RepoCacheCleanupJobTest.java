package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJob.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레포 캐시 정리 판단을 실제 git 캐시/worktree로 검증한다(file:// bare 원격 — 네트워크 없음).
 * 미사용 기간은 마커·git 메타 파일 mtime을 과거로 돌려 흉내 낸다.
 */
class RepoCacheCleanupJobTest {

    @TempDir Path tmp;

    private Path reposDir;
    private Path worktreeRoot;

    private WorkerProperties p;
    private GitRepoCache cache;
    private RepoCacheCleanupJob job;

    private void init(Boolean enabled) throws IOException {
        reposDir = Files.createDirectories(tmp.resolve("repos"));
        worktreeRoot = Files.createDirectories(tmp.resolve("worktrees"));
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        p = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, null, null, null, null, worktreeRoot.toString(), null, null, null, null, null, null, null,
                deploy, new WorkerProperties.RepoCacheCleanup(enabled, null, 30));
        cache = new GitRepoCache(p);
        job = new RepoCacheCleanupJob(p, cache);
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    /** main + 배포용 head 브랜치(netismaker/task-1)를 가진 file:// bare 레포. */
    private Path bareRemote(String name) throws Exception {
        Path seed = Files.createDirectories(tmp.resolve(name + "-seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        git(seed.toFile(), "checkout", "-b", "netismaker/task-1");
        Files.writeString(seed.resolve("b.txt"), "impl");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "impl");
        git(seed.toFile(), "checkout", "main");
        Path bare = tmp.resolve(name + ".git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    private static final List<String> GIT_META = List.of(
            ".git/FETCH_HEAD", ".git/HEAD", ".git/index", ".git/config", ".git/logs/HEAD", ".git/worktrees");

    /** 마커 + git 메타 파일 mtime을 days일 전으로. */
    private void age(RepoRef ref, int days) throws IOException {
        ageMarker(ref, days);
        ageGitMeta(ref, days);
    }

    private void ageMarker(RepoRef ref, int days) throws IOException {
        Path marker = reposDir.resolve(GitRemotes.localKey(ref) + GitRepoCache.LAST_USED_SUFFIX);
        if (Files.exists(marker)) Files.setLastModifiedTime(marker, daysAgo(days));
    }

    private void ageGitMeta(RepoRef ref, int days) throws IOException {
        Path dir = cacheDir(ref);
        for (String rel : GIT_META) {
            Path f = dir.resolve(rel);
            if (Files.exists(f)) Files.setLastModifiedTime(f, daysAgo(days));
        }
    }

    private static FileTime daysAgo(int days) {
        return FileTime.from(Instant.now().minus(Duration.ofDays(days)));
    }

    private Path cacheDir(RepoRef ref) {
        return reposDir.resolve(GitRemotes.localKey(ref));
    }

    private static RepoRef github(Path bare) {
        return new RepoRef("github", "acme/widgets", bare.toUri().toString());
    }

    @Test
    void deletes_a_cache_unused_longer_than_the_threshold_and_keeps_the_lock_file() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r1"));
        cache.ensureFresh(ref, "main");
        age(ref, 31);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.DELETED);
        assertThat(cacheDir(ref)).doesNotExist();
        assertThat(reposDir.resolve("acme/widgets.lock")).exists();   // 락 파일은 남긴다
        assertThat(reposDir.resolve("acme/widgets" + GitRepoCache.LAST_USED_SUFFIX)).doesNotExist();
        assertThat(reposDir.resolve(RepoCacheCleanupJob.TRASH_DIR)).isEmptyDirectory();
        assertThat(r.freedBytes()).isPositive();
        assertThat(r.trashLeft()).isZero();
    }

    @Test
    void keeps_a_recently_used_cache_even_when_git_metadata_looks_old() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r2"));
        cache.ensureFresh(ref, "main");
        ageGitMeta(ref, 90);   // 마커는 방금 갱신됨

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.RECENT);
        assertThat(cacheDir(ref).resolve("a.txt")).exists();
    }

    @Test
    void cache_without_marker_is_judged_by_git_metadata() throws Exception {
        init(true);
        RepoRef recent = new RepoRef("github", "acme/recent", bareRemote("r3a").toUri().toString());
        RepoRef old = new RepoRef("github", "acme/old", bareRemote("r3b").toUri().toString());
        cache.ensureFresh(recent, "main");
        cache.ensureFresh(old, "main");
        // 마커 도입 전 캐시 흉내
        Files.delete(reposDir.resolve("acme/recent" + GitRepoCache.LAST_USED_SUFFIX));
        Files.delete(reposDir.resolve("acme/old" + GitRepoCache.LAST_USED_SUFFIX));
        ageGitMeta(old, 45);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry("acme/recent", Outcome.RECENT)
                .containsEntry("acme/old", Outcome.DELETED);
        assertThat(cacheDir(recent)).exists();
        assertThat(cacheDir(old)).doesNotExist();
    }

    @Test
    void cache_whose_last_use_cannot_be_determined_is_kept() throws Exception {
        init(true);
        // 마커도, 읽을 git 메타도 없는 캐시 / .git 없는 디렉터리
        Path ghost = Files.createDirectories(reposDir.resolve("acme/ghost/.git"));
        Path notRepo = Files.createDirectories(reposDir.resolve("acme/not-a-repo"));
        Files.writeString(notRepo.resolve("x.txt"), "x");

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry("acme/ghost", Outcome.UNKNOWN)
                .containsEntry("acme/not-a-repo", Outcome.UNKNOWN);
        assertThat(ghost).exists();
        assertThat(notRepo.resolve("x.txt")).exists();
    }

    /** 구현 실패 시 디버그용으로 보존되는 worktree(task-{id}) — 캐시를 지우면 worktree가 깨진다. */
    @Test
    void keeps_a_cache_with_a_live_implementation_worktree() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r4"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 7, "실패 보존");
        age(ref, 60);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(cacheDir(ref)).exists();
        String status = ProcessRunner.requireSuccess(wt.dir(), List.of("git", "status", "--porcelain"), 30);
        assertThat(status).isEmpty();   // worktree가 여전히 동작
    }

    /** 배포 worktree(deploy-{id}) — 재배포가 같은 경로를 다시 쓰고, 컨테이너가 살아 있는 동안 보존된다. */
    @Test
    void keeps_a_cache_with_a_live_deploy_worktree() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r5"));
        cache.ensureFresh(ref, "main");
        File dir = cache.fetchOnly(ref, "netismaker/task-1").dir();
        File wt = new WorktreeService(p, cache).createForDeploy(dir, GitRemotes.localKey(ref), "netismaker/task-1", 1);
        age(ref, 60);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(wt.toPath().resolve("b.txt")).exists();
    }

    @Test
    void stale_worktree_metadata_does_not_protect_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r6"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 8, "gone");
        // worktree 디렉터리만 수동으로 지워진 상태 — .git/worktrees/task-8/gitdir은 없는 경로를 가리킨다
        assertThat(RepoCacheCleanupJob.deleteTree(wt.dir().toPath()).failures()).isZero();
        age(ref, 60);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.DELETED);
        assertThat(cacheDir(ref)).doesNotExist();
    }

    /** worktree의 .git 파일만 사라지고 디렉터리(보존 산출물)가 남았으면 여전히 보호한다. */
    @Test
    void worktree_directory_without_its_git_file_still_protects_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r6b"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 10, "half");
        Files.delete(wt.dir().toPath().resolve(".git"));
        age(ref, 60);

        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(cacheDir(ref)).exists();
    }

    /** git 2.48+ worktree.useRelativePaths: gitdir이 메타 디렉터리 기준 상대경로로 기록된다. */
    @Test
    void relative_gitdir_is_resolved_against_the_worktree_metadata_dir() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r6c"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 11, "rel");
        Path meta = cacheDir(ref).resolve(".git/worktrees/task-11");
        Path relative = meta.relativize(wt.dir().toPath().resolve(".git"));
        assertThat(relative.isAbsolute()).isFalse();
        Files.writeString(meta.resolve("gitdir"), relative + "\n");
        age(ref, 60);

        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);

        RepoCacheCleanupJob.deleteTree(wt.dir().toPath());
        age(ref, 60);
        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.DELETED);
    }

    /** git worktree lock은 '없어져도 prune하지 말라'는 표시 — 디렉터리가 없어도 보존한다. */
    @Test
    void locked_worktree_protects_the_cache_even_when_its_directory_is_missing() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r7"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 9, "locked");
        git(dir, "worktree", "lock", wt.dir().getAbsolutePath());
        RepoCacheCleanupJob.deleteTree(wt.dir().toPath());
        age(ref, 60);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(cacheDir(ref)).exists();
    }

    /** 같은 JVM의 다른 스레드(예: 폴링 스레드의 ensureFresh)가 레포 락을 쥐고 있으면 기다리지 않고 건너뛴다. */
    @Test
    void skips_a_cache_whose_repo_lock_is_held_in_this_process_and_deletes_it_next_run() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r8"));
        cache.ensureFresh(ref, "main");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                cache.withRepoLock(GitRemotes.localKey(ref), () -> {
                    held.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
        age(ref, 60);   // withRepoLock이 마커를 갱신한 뒤에 과거로 돌린다

        long start = System.nanoTime();
        RepoCacheCleanupJob.Result busy = job.runOnce();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        release.countDown();
        holder.join();

        assertThat(busy.outcomes()).containsEntry("acme/widgets", Outcome.BUSY);
        assertThat(tookMs).isLessThan(5_000);   // 락 대기(60초) 없이 즉시
        assertThat(cacheDir(ref)).exists();

        age(ref, 60);
        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.DELETED);
    }

    /**
     * 다른 워커 프로세스가 FileLock을 쥔 상황. 같은 JVM의 별도 채널로 잡아 흉내 낸다
     * (JVM 내 ReentrantLock은 비어 있고 FileLock만 점유된 상태 — 다른 프로세스와 같은 경로를 탄다).
     */
    @Test
    void skips_a_cache_whose_file_lock_is_held_elsewhere() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r9"));
        cache.ensureFresh(ref, "main");
        age(ref, 60);

        RepoCacheCleanupJob.Result r;
        try (FileChannel ch = FileChannel.open(reposDir.resolve("acme/widgets.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            r = job.runOnce();
        }

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.BUSY);
        assertThat(cacheDir(ref)).exists();
    }

    /** 같은 repos-dir을 쓰는 다른 워커가 이미 정리 중이면 이번 회차는 통째로 건너뛴다. */
    @Test
    void whole_run_is_skipped_while_another_worker_holds_the_job_lock() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r10"));
        cache.ensureFresh(ref, "main");
        age(ref, 60);

        RepoCacheCleanupJob.Result r;
        try (FileChannel ch = FileChannel.open(reposDir.resolve(RepoCacheCleanupJob.JOB_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            r = job.runOnce();
        }

        assertThat(r.skipped()).isTrue();
        assertThat(cacheDir(ref)).exists();
    }

    @Test
    void gitlab_layout_cache_is_cleaned_and_recloned_on_next_use() throws Exception {
        init(true);
        RepoRef ref = new RepoRef("gitlab", "product/netis/web", bareRemote("r11").toUri().toString());
        cache.ensureFresh(ref, "main");
        assertThat(cacheDir(ref)).isEqualTo(reposDir.resolve("_gitlab/product+netis+web"));
        age(ref, 31);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("_gitlab/product+netis+web", Outcome.DELETED);
        assertThat(cacheDir(ref)).doesNotExist();
        assertThat(reposDir.resolve("_gitlab/product+netis+web.lock")).exists();

        // 다음 사용은 투명하게 재clone
        GitRepoCache.CheckedOutRepo again = cache.ensureFresh(ref, "main");
        assertThat(again.dir().toPath().resolve("a.txt")).exists();
        assertThat(again.commitSha()).hasSize(40);
    }

    /** 배포 경로(fetchOnly)도 캐시가 없으면 clone으로 복구돼 createForDeploy가 성립한다. */
    @Test
    void deploy_fetch_only_reclones_a_deleted_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r12"));
        cache.ensureFresh(ref, "main");
        age(ref, 31);
        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.DELETED);

        File dir = cache.fetchOnly(ref, "netismaker/task-1").dir();
        File wt = new WorktreeService(p, cache).createForDeploy(dir, GitRemotes.localKey(ref), "netismaker/task-1", 3);

        assertThat(wt.toPath().resolve("b.txt")).exists();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "심볼릭 링크 생성에 개발자 모드/관리자 권한 필요")
    void does_not_follow_symlinks_out_of_the_repos_dir() throws Exception {
        init(true);
        // repos-dir 밖의 캐시처럼 생긴 디렉터리 (git 메타가 오래됨)
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path outsideRepo = Files.createDirectories(outside.resolve("repo/.git"));
        Files.writeString(outsideRepo.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.setLastModifiedTime(outsideRepo.resolve("HEAD"), daysAgo(90));
        Files.createDirectories(reposDir.resolve("acme"));
        Files.createSymbolicLink(reposDir.resolve("acme/linked"), outside.resolve("repo"));   // 캐시 자리의 링크
        Files.createSymbolicLink(reposDir.resolve("evil"), outside);                          // owner 자리의 링크
        // 휴지통 안의 링크 — 링크만 지워지고 대상은 그대로여야 한다
        Path trashItem = Files.createDirectories(reposDir.resolve(RepoCacheCleanupJob.TRASH_DIR).resolve("x-1"));
        Files.createSymbolicLink(trashItem.resolve("link"), outside);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).doesNotContainKeys("acme/linked", "evil/repo");
        assertThat(outsideRepo.resolve("HEAD")).exists();
        assertThat(reposDir.resolve("acme/linked")).isSymbolicLink();
        assertThat(trashItem).doesNotExist();
    }

    /** 이전 회차에 삭제가 중간에 멈춘 휴지통 잔여물(읽기 전용 pack 파일 포함)을 다음 회차가 비운다. */
    @Test
    void leftover_trash_including_read_only_files_is_swept_on_the_next_run() throws Exception {
        init(true);
        Path leftover = Files.createDirectories(
                reposDir.resolve(RepoCacheCleanupJob.TRASH_DIR).resolve("acme+old-1/.git/objects/pack"));
        Path pack = leftover.resolve("pack-1.pack");
        Files.write(pack, new byte[4096]);
        assertThat(pack.toFile().setWritable(false)).isTrue();   // git이 만드는 pack처럼 읽기 전용

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(reposDir.resolve(RepoCacheCleanupJob.TRASH_DIR)).isEmptyDirectory();
        assertThat(r.freedBytes()).isGreaterThanOrEqualTo(4096);
        assertThat(r.trashLeft()).isZero();
    }

    @Test
    void disabled_job_does_nothing() throws Exception {
        init(false);
        RepoRef ref = github(bareRemote("r13"));
        cache.ensureFresh(ref, "main");
        age(ref, 365);

        job.cleanup();

        assertThat(cacheDir(ref)).exists();
    }

    @Test
    void human_readable_sizes() {
        assertThat(RepoCacheCleanupJob.humanBytes(512)).isEqualTo("512 B");
        assertThat(RepoCacheCleanupJob.humanBytes(3L * 1024 * 1024 * 1024 / 2)).isEqualTo("1.5 GB");
    }
}
