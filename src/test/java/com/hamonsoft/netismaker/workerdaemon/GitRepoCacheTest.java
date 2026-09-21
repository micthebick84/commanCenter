package com.hamonsoft.netismaker.workerdaemon;

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
    void existing_clone_gets_its_origin_url_refreshed_before_fetch() throws Exception {
        Path bare = bareRemote();
        Path reposDir = Files.createDirectories(tmp.resolve("repos"));
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        cache.ensureFresh(ref, "main");
        Path dir = reposDir.resolve("acme").resolve("widgets");
        git(dir.toFile(), "remote", "set-url", "origin", "file:///nonexistent/stale.git");

        cache.ensureFresh(ref, "main");   // stale origin이면 fetch가 실패했을 것

        String origin = ProcessRunner.requireSuccess(dir.toFile(),
                List.of("git", "remote", "get-url", "origin"), 30).trim();
        assertThat(origin).isEqualTo(bare.toUri().toString());
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
