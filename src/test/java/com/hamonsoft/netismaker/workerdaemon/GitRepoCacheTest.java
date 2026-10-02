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

class GitRepoCacheTest {

    @TempDir Path tmp;

    private WorkerProperties props(Path reposDir) {
        return props(reposDir, null);
    }

    private WorkerProperties props(Path reposDir, Path worktreeRoot) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null,
                worktreeRoot == null ? null : worktreeRoot.toString(),
                null, null, null, null, null, null, null, deploy, null, null);
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    /** file:// bare 레포를 만들고 main 브랜치에 커밋 1개. */
    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    @Test
    void gitlab_ref_is_cloned_into_flattened_two_level_dir_with_lock_outside_the_repo() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        // gitUrl이 file:// 이면 SCHEME(https?)에 안 걸려 토큰이 끼워지지 않는다 → 로컬 clone 가능
        RepoRef ref = new RepoRef("gitlab", "product/netis/web/netis-v7.0", bare.toUri().toString());

        GitRepoCache.CheckedOutRepo repo = new GitRepoCache(props(reposDir)).ensureFresh(ref, "main");

        Path expected = reposDir.resolve("_gitlab").resolve("product+netis+web+netis-v7.0");
        assertThat(repo.dir().toPath()).isEqualTo(expected);
        assertThat(expected.resolve("a.txt")).exists();
        assertThat(reposDir.resolve("_gitlab").resolve("product+netis+web+netis-v7.0.lock")).exists();
        assertThat(repo.commitSha()).hasSize(40);
    }

    @Test
    void existing_clone_fetches_by_url_and_leaves_the_plain_git_url_in_origin() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");
        Path dir = reposDir.resolve("acme").resolve("widgets");
        git(dir.toFile(), "remote", "set-url", "origin", "file:///nonexistent/stale.git");

        // fetch가 origin이 아니라 URL 인자를 쓰므로 stale origin이어도 성공해야 한다
        cache.ensureFresh(ref, "main");

        String origin = ProcessRunner.requireSuccess(dir.toFile(),
                List.of("git", "remote", "get-url", "origin"), 30).trim();
        assertThat(origin).isEqualTo(bare.toUri().toString());
    }

    /**
     * 캐시 config에는 자격증명이 절대 남지 않는다 — worktree가 이 config를 공유하므로
     * 워크트리에서 도는 세션이 `git remote -v`로 토큰을 읽을 수 있기 때문.
     * 취약 버전이 남긴 인증 origin(레거시)도 다음 ensureFresh에서 씻겨야 한다.
     */
    @Test
    void cache_config_never_contains_credentials() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");
        Path dir = reposDir.resolve("acme").resolve("widgets");
        // 취약 버전이 만들어 둔 캐시 시뮬레이션
        git(dir.toFile(), "remote", "set-url", "origin",
                "https://oauth2:glpat-LEGACY@gitlab.invalid.example/g/p.git");

        cache.ensureFresh(ref, "main");

        String config = Files.readString(dir.resolve(".git").resolve("config"));
        assertThat(config).doesNotContain("oauth2:").doesNotContain("glpat-LEGACY");
    }

    /** main + develop 두 브랜치를 가진 file:// bare 레포. develop에만 b.txt가 있다. */
    private Path bareRemoteWithDevelop(String name) throws Exception {
        Path seed = Files.createDirectories(tmp.resolve(name + "-seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        git(seed.toFile(), "checkout", "-b", "develop");
        Files.writeString(seed.resolve("b.txt"), "dev");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "dev");
        git(seed.toFile(), "checkout", "main");
        Path bare = tmp.resolve(name + ".git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    /**
     * 캐시는 최초 브랜치 하나짜리 단일 브랜치 clone이라 구성된 refspec이 그 브랜치뿐이다.
     * {@code git checkout <B>}의 추적 브랜치 자동 생성(DWIM)은 구성된 refspec만 보므로,
     * 명시 refspec으로 origin/B를 받아 와도 "pathspec did not match"로 실패했다(실측).
     */
    @Test
    void existing_clone_switches_to_a_branch_the_single_branch_clone_never_had() throws Exception {
        Path bare = bareRemoteWithDevelop("remote3");
        Path reposDir = Files.createDirectories(tmp.resolve("repos3"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");

        GitRepoCache.CheckedOutRepo repo = cache.ensureFresh(ref, "develop");

        String develop = ProcessRunner.requireSuccess(repo.dir(),
                List.of("git", "rev-parse", "refs/remotes/origin/develop"), 30).trim();
        assertThat(repo.commitSha()).isEqualTo(develop);
        assertThat(repo.dir().toPath().resolve("b.txt")).exists();
    }

    /**
     * 대상 브랜치를 워크트리가 점유 중이어도 캐시는 최신화돼야 한다.
     * 로컬 브랜치를 만들거나 옮기는 checkout은 "already used by worktree"로 거부된다 —
     * 캐시는 HEAD sha와 작업 트리만 쓰이므로 origin/B를 분리(detached) 체크아웃한다.
     */
    @Test
    void existing_clone_refreshes_even_when_a_worktree_holds_the_branch() throws Exception {
        Path bare = bareRemoteWithDevelop("remote4");
        Path reposDir = Files.createDirectories(tmp.resolve("repos4"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        File dir = cache.ensureFresh(ref, "main").dir();
        git(dir, "fetch", bare.toUri().toString(), "+refs/heads/develop:refs/remotes/origin/develop");
        git(dir, "worktree", "add", "-b", "develop", tmp.resolve("wt4").toString(), "origin/develop");

        GitRepoCache.CheckedOutRepo repo = cache.ensureFresh(ref, "develop");

        assertThat(repo.dir().toPath().resolve("b.txt")).exists();
        String holder = ProcessRunner.requireSuccess(tmp.resolve("wt4").toFile(),
                List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), 30).trim();
        assertThat(holder).isEqualTo("develop");
    }

    /**
     * 배포 경로(fetchOnly): 캐시는 {@code --depth=1 --branch <base>} 단일 브랜치 shallow clone이라
     * 구성된 refspec은 최초 브랜치 하나뿐이다. head 브랜치를 URL + 명시 refspec으로 fetch해
     * {@code origin/<head>}를 만들어야 WorktreeService.createForDeploy의
     * {@code git worktree add --detach origin/<head>}가 성립한다. (예전 {@code git fetch --all --prune}는
     * 이 ref를 만들지 못했다 — 실측.) origin은 그 뒤에도 평문이어야 한다.
     */
    @Test
    void fetch_only_creates_the_tracking_ref_for_a_branch_the_clone_never_had() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed2"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        git(seed.toFile(), "checkout", "-b", "netismaker/task-9");
        Files.writeString(seed.resolve("b.txt"), "구현 산출물");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "impl");
        git(seed.toFile(), "checkout", "main");
        Path bare = tmp.resolve("remote2.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());

        Path reposDir = Files.createDirectories(tmp.resolve("repos2"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");

        GitRepoCache.CheckedOutRepo repo = cache.fetchOnly(ref, "netismaker/task-9");

        String tracked = ProcessRunner.requireSuccess(repo.dir(),
                List.of("git", "rev-parse", "refs/remotes/origin/netismaker/task-9"), 30).trim();
        assertThat(tracked).hasSize(40);
        String origin = ProcessRunner.requireSuccess(repo.dir(),
                List.of("git", "remote", "get-url", "origin"), 30).trim();
        assertThat(origin).isEqualTo(bare.toUri().toString());
    }

    /**
     * https 경로(실제 네트워크 불가)는 명령 인자 조립만으로 가드한다:
     * 전송(clone/fetch)은 인증 URL을 쓰되 자격증명 헬퍼는 비활성화한다
     * (운영자 머신의 osxkeychain/manager가 토큰을 저장하지 못하게).
     */
    @Test
    void https_ref_transports_with_the_token_and_no_credential_helper() {
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.hamon.vip/g/p.git");
        String authUrl = new GitRemotes("ghp_SECRETPAT", "glpat-SECRETTOKEN").authenticatedUrl(ref);
        assertThat(authUrl).isEqualTo("https://oauth2:glpat-SECRETTOKEN@gitlab.hamon.vip/g/p.git");

        assertThat(GitRepoCache.cloneArgs(authUrl, "main", "p"))
                .containsSequence("git", "-c", "credential.helper=")
                .contains(authUrl);
        assertThat(GitRepoCache.fetchArgs(authUrl, "main"))
                .containsSequence("git", "-c", "credential.helper=")
                .contains(authUrl)
                .contains("+refs/heads/main:refs/remotes/origin/main");
        assertThat(GitRepoCache.fetchTrackingArgs(authUrl, "netismaker/task-1"))
                .containsSequence("git", "-c", "credential.helper=")
                .contains(authUrl)
                .contains("netismaker/task-1:refs/remotes/origin/netismaker/task-1");
    }

    /**
     * origin에 어떤 URL이 들어가는지의 **선택** 자체를 가드한다.
     * scrubArgs는 RepoRef만 받고 토큰(GitRemotes)에 접근할 수 없어야 하며,
     * https ref에 대해서도 평문 URL만 실어야 한다 — file:// 실git 테스트는
     * 인증 URL == 평문 URL이라 이 회귀를 잡지 못한다.
     */
    @Test
    void scrub_args_choose_the_plain_url_even_for_an_https_ref_with_a_token_configured() {
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.hamon.vip/g/p.git");

        assertThat(GitRepoCache.scrubArgs(ref))
                .containsExactly("git", "remote", "set-url", "origin", "https://gitlab.hamon.vip/g/p.git");
        assertThat(String.join(" ", GitRepoCache.scrubArgs(ref)))
                .doesNotContain("oauth2:")
                .doesNotContain("glpat-SECRETTOKEN")
                .doesNotContain("ghp_SECRETPAT");
    }

    /**
     * 캐시를 쓰는 모든 경로(ensureFresh·fetchOnly·worktree 생성)가 마지막 사용 마커를 갱신해야
     * RepoCacheCleanupJob이 쓰이는 캐시를 미사용으로 오판하지 않는다.
     */
    @Test
    void every_cache_use_refreshes_the_last_used_marker() throws Exception {
        Path bare = bareRemoteWithDevelop("remote5");
        Path reposDir = Files.createDirectories(tmp.resolve("repos5"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        WorkerProperties p = props(reposDir, tmp.resolve("worktrees5"));
        GitRepoCache cache = new GitRepoCache(p);
        Path marker = reposDir.resolve("acme/widgets" + GitRepoCache.LAST_USED_SUFFIX);
        java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(40)));

        File dir = cache.ensureFresh(ref, "main").dir();
        assertThat(marker).exists();

        Files.setLastModifiedTime(marker, old);
        cache.fetchOnly(ref, "develop");
        assertThat(Files.getLastModifiedTime(marker)).isGreaterThan(old);

        Files.setLastModifiedTime(marker, old);
        cache.ensureFresh(ref, "main");
        assertThat(Files.getLastModifiedTime(marker)).isGreaterThan(old);

        Files.setLastModifiedTime(marker, old);
        new WorktreeService(p, cache).createForDesign(dir, "acme/widgets", "main", 1);
        assertThat(Files.getLastModifiedTime(marker)).isGreaterThan(old);

        // 정리 잡의 비차단 락은 마커를 건드리지 않는다
        Files.setLastModifiedTime(marker, old);
        assertThat(cache.tryWithRepoLock("acme/widgets", () -> "ok")).contains("ok");
        assertThat(Files.getLastModifiedTime(marker)).isEqualTo(old);
    }

    /**
     * 같은 JVM의 다른 채널이 FileLock을 쥐고 있으면(대소문자 무시 파일시스템에서 표기만 다른 키 등)
     * tryLock이 OverlappingFileLockException을 던진다 — 작업을 즉시 실패시키지 말고 풀릴 때까지 기다려야 한다.
     */
    @Test
    void blocking_lock_waits_while_another_channel_in_this_jvm_holds_the_file_lock() throws Exception {
        Path reposDir = Files.createDirectories(tmp.resolve("repos6"));
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        Files.createDirectories(reposDir.resolve("acme"));
        java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(reposDir.resolve("acme/widgets.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock held = ch.lock();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(700);
                held.release();
                ch.close();
            } catch (Exception ignore) {
                // 테스트 보조 스레드
            }
        });
        releaser.start();

        String result = cache.withRepoLock("acme/widgets", () -> "acquired");

        releaser.join();
        assertThat(result).isEqualTo("acquired");
    }

    @Test
    void failure_message_never_contains_the_token() {
        Path reposDir = tmp.resolve("repos");
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        assertThatThrownBy(() -> new GitRepoCache(props(reposDir)).ensureFresh(ref, "main"))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("glpat-SECRETTOKEN").doesNotContain("oauth2:glpat"));
    }
}
