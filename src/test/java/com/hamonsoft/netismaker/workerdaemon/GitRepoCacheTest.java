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
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null, null, null, null, null, null, null, null, null, deploy);
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
     * 전송(clone/fetch)은 인증 URL, origin(set-url)은 평문 URL만.
     */
    @Test
    void https_ref_transports_with_the_token_but_origin_gets_only_the_plain_url() {
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.hamon.vip/g/p.git");
        String authUrl = new GitRemotes("ghp_SECRETPAT", "glpat-SECRETTOKEN").authenticatedUrl(ref);
        assertThat(authUrl).isEqualTo("https://oauth2:glpat-SECRETTOKEN@gitlab.hamon.vip/g/p.git");

        assertThat(GitRepoCache.cloneArgs(authUrl, "main", "p")).contains(authUrl);
        assertThat(GitRepoCache.fetchArgs(authUrl, "main"))
                .contains(authUrl)
                .contains("+refs/heads/main:refs/remotes/origin/main");
        assertThat(GitRepoCache.fetchTrackingArgs(authUrl, "netismaker/task-1"))
                .contains(authUrl)
                .contains("netismaker/task-1:refs/remotes/origin/netismaker/task-1");

        assertThat(GitRepoCache.setUrlArgs(ref.gitUrl()))
                .containsExactly("git", "remote", "set-url", "origin", "https://gitlab.hamon.vip/g/p.git");
        assertThat(String.join(" ", GitRepoCache.setUrlArgs(ref.gitUrl())))
                .doesNotContain("oauth2:").doesNotContain("glpat-SECRETTOKEN");
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
