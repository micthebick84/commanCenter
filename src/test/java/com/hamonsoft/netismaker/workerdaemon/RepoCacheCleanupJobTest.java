package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJob.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        init(enabled, null);
    }

    private void init(Boolean enabled, String cron) throws IOException {
        reposDir = Files.createDirectories(tmp.resolve("repos"));
        worktreeRoot = Files.createDirectories(tmp.resolve("worktrees"));
        p = props(reposDir.toString(), enabled, cron);
        cache = new GitRepoCache(p);
        job = new RepoCacheCleanupJob(p, cache);
    }

    private WorkerProperties props(String reposDirValue, Boolean enabled, String cron) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDirValue, null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, new WorkerProperties.RepoCacheCleanup(enabled, cron, 30));
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
        // 마커도, 읽을 git 메타도 없는 캐시(소유 표식은 있음) / .git 없는 디렉터리
        Path ghost = Files.createDirectories(reposDir.resolve("acme/ghost/.git"));
        Files.createFile(reposDir.resolve("acme/ghost" + GitRepoCache.LOCK_SUFFIX));
        Path notRepo = Files.createDirectories(reposDir.resolve("acme/not-a-repo"));
        Files.writeString(notRepo.resolve("x.txt"), "x");

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry("acme/ghost", Outcome.UNKNOWN)
                .containsEntry("acme/not-a-repo", Outcome.UNKNOWN);
        assertThat(ghost).exists();
        assertThat(notRepo.resolve("x.txt")).exists();
    }

    /**
     * REPOS_DIR를 운영자 레포 폴더로 잘못 잡은 경우 — netisMaker가 만든 적 없는 레포({localKey}.lock 표식 없음)는
     * 오래됐어도 지우지 않고, 락 파일도 새로 만들지 않는다.
     */
    @Test
    void repo_without_the_netismaker_lock_marker_is_never_touched() throws Exception {
        init(true);
        Path own = Files.createDirectories(reposDir.resolve("myorg/private-notes"));
        git(own.toFile(), "init", "-b", "main");
        Files.writeString(own.resolve("n.md"), "local only");
        git(own.toFile(), "add", "-A");
        git(own.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "local only");
        for (String rel : GIT_META) {
            Path f = own.resolve(rel);
            if (Files.exists(f)) Files.setLastModifiedTime(f, daysAgo(90));
        }

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("myorg/private-notes", Outcome.UNKNOWN);
        assertThat(own.resolve("n.md")).exists();
        assertThat(reposDir.resolve("myorg/private-notes" + GitRepoCache.LOCK_SUFFIX)).doesNotExist();
    }

    /** REPOS_DIR= (빈 값)은 JVM 현재 디렉터리로 풀린다 — 홈·루트와 함께 정리 자체를 거부한다. */
    @Test
    void refuses_to_clean_a_blank_home_or_root_repos_dir() throws Exception {
        init(true);
        List<String> refused = new java.util.ArrayList<>(List.of("", "  ", tmp.getRoot().toString()));
        String home = System.getProperty("user.home");
        if (Files.isDirectory(Path.of(home))) refused.add(home);

        for (String dir : refused) {
            RepoCacheCleanupJob j = new RepoCacheCleanupJob(props(dir, true, null), cache);
            assertThatThrownBy(j::runOnce).as("repos-dir='%s'", dir).isInstanceOf(IllegalStateException.class);
        }
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

    /**
     * 현재 운영 흐름의 한계를 고정한다: 구현 성공(PR 생성)·배포 worktree는 지우는 곳이 없어 캐시가 계속 보존된다.
     * 대신 회차 결과에 붙잡고 있는 worktree 경로가 남아, 그 worktree를 치우면 다음 회차에 회수된다.
     */
    @Test
    void reports_which_worktrees_hold_a_cache_and_reclaims_it_once_they_are_removed() throws Exception {
        init(true);
        RepoRef impl = new RepoRef("github", "acme/impl", bareRemote("r17a").toUri().toString());
        RepoRef deploy = new RepoRef("github", "acme/deploy", bareRemote("r17b").toUri().toString());
        WorktreeService worktrees = new WorktreeService(p, cache);
        File implCache = cache.ensureFresh(impl, "main").dir();
        File implWt = worktrees.create(implCache, GitRemotes.localKey(impl), "main", 21, "성공 경로").dir();
        File deployCache = cache.fetchOnly(deploy, "netismaker/task-1").dir();
        File deployWt = worktrees.createForDeploy(deployCache, GitRemotes.localKey(deploy), "netismaker/task-1", 22);
        age(impl, 365);
        age(deploy, 365);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes())
                .containsEntry("acme/impl", Outcome.WORKTREE)
                .containsEntry("acme/deploy", Outcome.WORKTREE);
        assertThat(r.freedBytes()).isZero();
        assertThat(realPaths(r.worktreeHolders().get("acme/impl"))).containsExactly(implWt.toPath().toRealPath());
        assertThat(realPaths(r.worktreeHolders().get("acme/deploy"))).containsExactly(deployWt.toPath().toRealPath());

        // 보존 worktree를 치우면(향후 보존 정책의 몫) 다음 회차에 회수된다
        worktrees.remove(implCache, implWt);
        age(impl, 365);
        RepoCacheCleanupJob.Result next = job.runOnce();
        assertThat(next.outcomes())
                .containsEntry("acme/impl", Outcome.DELETED)
                .containsEntry("acme/deploy", Outcome.WORKTREE);
        assertThat(next.worktreeHolders()).containsOnlyKeys("acme/deploy");
        assertThat(next.freedBytes()).isPositive();
    }

    private static List<Path> realPaths(List<String> paths) throws IOException {
        var out = new java.util.ArrayList<Path>();
        for (String s : paths) out.add(Path.of(s).toRealPath());
        return out;
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
        assertThat(r.worktreeHolders().get("acme/widgets")).singleElement().asString().endsWith("(git worktree lock)");
        assertThat(cacheDir(ref)).exists();
    }

    /**
     * 보존 worktree 경로를 권한 문제로 stat할 수 없을 때(예: launchd로 뜬 워커에 권한이 없는 보호 폴더)
     * '없음'으로 보면 push 안 된 커밋이 담긴 캐시를 지우게 된다 — 판단할 수 없으면 살아 있다고 본다.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX 권한")
    void worktree_that_cannot_be_stat_due_to_permissions_protects_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r14"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 12, "perm");
        age(ref, 60);
        Path parent = wt.dir().toPath().getParent();
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(parent);
        Files.setPosixFilePermissions(parent, Set.of());
        RepoCacheCleanupJob.Result r;
        try {
            Path gitFile = wt.dir().toPath().resolve(".git");
            assumeTrue(!Files.exists(gitFile) && !Files.notExists(gitFile), "권한으로 막히지 않는 환경(root 등)");
            r = job.runOnce();
        } finally {
            Files.setPosixFilePermissions(parent, before);
        }

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(ProcessRunner.requireSuccess(wt.dir(), List.of("git", "status", "--porcelain"), 30)).isEmpty();
    }

    /** 캐시 .git 안을 못 읽으면 사용 시각도 worktree 여부도 모른다 — 지우지 않는다. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX 권한")
    void cache_whose_git_dir_cannot_be_read_is_not_deleted() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r15"));
        File dir = cache.ensureFresh(ref, "main").dir();
        new WorktreeService(p, cache).create(dir, GitRemotes.localKey(ref), "main", 13, "perm");
        age(ref, 60);
        Path gitDir = cacheDir(ref).resolve(".git");
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(gitDir);
        Files.setPosixFilePermissions(gitDir, Set.of());
        RepoCacheCleanupJob.Result r;
        try {
            Path head = gitDir.resolve("HEAD");
            assumeTrue(!Files.exists(head) && !Files.notExists(head), "권한으로 막히지 않는 환경(root 등)");
            r = job.runOnce();
        } finally {
            Files.setPosixFilePermissions(gitDir, before);
        }

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.UNKNOWN);
        assertThat(cacheDir(ref).resolve("a.txt")).exists();
    }

    /**
     * 확인된 부재(NoSuchFile)만 '없음'이다. 경로 중간이 파일이면 ENOTDIR — 권한 오류처럼 부재를 확정할 수 없는
     * 경우의 root 무관 대역이다(root로 도는 환경에선 위 권한 테스트가 건너뛰어진다).
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "경로 중간이 파일일 때의 오류 코드가 POSIX(ENOTDIR)와 다르다")
    void worktree_path_that_cannot_be_confirmed_absent_protects_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r16"));
        File dir = cache.ensureFresh(ref, "main").dir();
        new WorktreeService(p, cache).create(dir, GitRemotes.localKey(ref), "main", 14, "enotdir");
        Path notADir = Files.writeString(tmp.resolve("not-a-dir"), "x");
        Files.writeString(cacheDir(ref).resolve(".git/worktrees/task-14/gitdir"),
                notADir.resolve("task-14/.git") + "\n");
        age(ref, 60);

        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(cacheDir(ref)).exists();
    }

    /**
     * worktree-root가 있는 외장·네트워크 볼륨이 잠시 분리돼 있으면 worktree 경로가 통째로 NoSuchFile이다 —
     * '없음'이 아니라 '안 보임'이라 캐시를 지우면 볼륨이 돌아와도 그 worktree의 git이 끊긴다.
     */
    @Test
    void worktree_on_a_detached_volume_protects_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r18"));
        File dir = cache.ensureFresh(ref, "main").dir();
        WorktreeService.CreatedWorktree wt = new WorktreeService(p, cache)
                .create(dir, GitRemotes.localKey(ref), "main", 15, "volume");
        age(ref, 60);
        Path unmounted = tmp.resolve("worktrees-unmounted");
        Files.move(worktreeRoot, unmounted);            // 볼륨 분리: worktree-root 자체가 안 보인다

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(r.worktreeHolders().get("acme/widgets")).singleElement().asString()
                .contains("worktree-root 접근 불가");
        Files.move(unmounted, worktreeRoot);            // 볼륨 복귀 — worktree가 그대로 동작해야 한다
        assertThat(ProcessRunner.requireSuccess(wt.dir(), List.of("git", "status", "--porcelain"), 30)).isEmpty();
    }

    /** worktree-root는 보이지만 gitdir이 그 밖(끊긴 링크 너머·옛 WORKTREE_ROOT)의 없는 경로를 가리키면 보존한다. */
    @Test
    void missing_worktree_outside_the_worktree_root_protects_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r19"));
        File dir = cache.ensureFresh(ref, "main").dir();
        new WorktreeService(p, cache).create(dir, GitRemotes.localKey(ref), "main", 16, "elsewhere");
        Files.writeString(cacheDir(ref).resolve(".git/worktrees/task-16/gitdir"),
                tmp.resolve("gone-volume/wt/acme/widgets/task-16/.git") + "\n");
        age(ref, 60);

        RepoCacheCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry("acme/widgets", Outcome.WORKTREE);
        assertThat(r.worktreeHolders().get("acme/widgets")).singleElement().asString()
                .contains("worktree-root 밖");
        assertThat(cacheDir(ref)).exists();
    }

    /**
     * lastUsed의 fail-closed 분기: 사용 시각 근거 중 하나라도 부재가 아닌 이유로 못 읽으면 판단불가로 보존한다.
     * .git/logs를 파일로 바꿔 .git/logs/HEAD 조회를 ENOTDIR로 만든다 — root에서도 도는 권한 오류 대역
     * (위 cache_whose_git_dir_cannot_be_read_is_not_deleted는 root에서 건너뛰어진다).
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "경로 중간이 파일일 때의 오류 코드가 POSIX(ENOTDIR)와 다르다")
    void last_use_evidence_that_cannot_be_read_keeps_the_cache() throws Exception {
        init(true);
        RepoRef ref = github(bareRemote("r20"));
        cache.ensureFresh(ref, "main");
        age(ref, 60);
        Path logs = cacheDir(ref).resolve(".git/logs");
        assertThat(RepoCacheCleanupJob.deleteTree(logs).failures()).isZero();
        Files.writeString(logs, "not a dir");

        assertThat(RepoCacheCleanupJob.lastUsed(reposDir.toRealPath(), cacheDir(ref).toRealPath(), "acme/widgets"))
                .isEmpty();
        assertThat(job.runOnce().outcomes()).containsEntry("acme/widgets", Outcome.UNKNOWN);
        assertThat(cacheDir(ref).resolve("a.txt")).exists();
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
    void schedule_is_registered_from_the_normalized_cron() throws Exception {
        init(true, "  ");   // REPO_CACHE_CLEANUP_CRON= (빈 값) → 기본값
        ScheduledTaskRegistrar reg = new ScheduledTaskRegistrar();
        job.configureTasks(reg);
        assertThat(reg.getCronTaskList()).singleElement()
                .extracting(CronTask::getExpression).isEqualTo("0 0 3 * * *");

        // 꺼져 있거나 '-'면 등록하지 않는다
        for (RepoCacheCleanupJob off : List.of(
                new RepoCacheCleanupJob(props(reposDir.toString(), false, null), cache),
                new RepoCacheCleanupJob(props(reposDir.toString(), true, "-"), cache))) {
            ScheduledTaskRegistrar none = new ScheduledTaskRegistrar();
            off.configureTasks(none);
            assertThat(none.getCronTaskList()).isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOn {}

    /** 빈 cron env로도 워커가 뜬다 — @Scheduled 플레이스홀더는 빈 값을 기본값으로 바꾸지 않아 부팅이 실패했었다. */
    @Test
    void blank_cron_boots_with_the_default_schedule() throws Exception {
        init(true, "");
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("worker");   // @Profile("worker") — 없으면 빈 등록 자체가 빠진다
            // yml의 ${REPO_CACHE_CLEANUP_CRON:...}에 빈 값이 export된 상태 그대로
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("env",
                    Map.of("netis-maker.worker.repo-cache-cleanup.cron", "")));
            ctx.register(SchedulingOn.class);
            ctx.registerBean(RepoCacheCleanupJob.class, () -> job);
            ctx.refresh();

            assertThat(ctx.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                    .extracting(ScheduledTask::getTask)
                    .singleElement()
                    .isInstanceOfSatisfying(CronTask.class,
                            t -> assertThat(t.getExpression()).isEqualTo("0 0 3 * * *"));
        }
    }

    @Test
    void human_readable_sizes() {
        assertThat(RepoCacheCleanupJob.humanBytes(512)).isEqualTo("512 B");
        assertThat(RepoCacheCleanupJob.humanBytes(3L * 1024 * 1024 * 1024 / 2)).isEqualTo("1.5 GB");
    }

    @Test
    void worktree_holder_list_is_abbreviated_for_the_log() {
        assertThat(RepoCacheCleanupJob.abbreviate(List.of("a", "b"))).isEqualTo("a, b");
        assertThat(RepoCacheCleanupJob.abbreviate(List.of("1", "2", "3", "4", "5", "6", "7")))
                .isEqualTo("1, 2, 3, 4, 5 외 2개");
    }
}
