package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 참조 repo의 고정 커밋 준비(prepareRefCommitUnderLock) — 실제 git, file:// bare 원격(스펙 2026-10-24 §6.2).
 * 공유 캐시의 origin/*·HEAD를 옮기지 않고, 자격증명 도우미·askpass·프롬프트 없이 받는다.
 */
class GitRepoCacheRefTest {

    private static final String MISSING_SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir Path tmp;

    private WorkerProperties props(Path reposDir, String githubPat, String gitlabToken) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, githubPat, gitlabToken, null, null, null,
                null, null, null, null, null, null, null, deploy, null, null);
    }

    private WorkerProperties props(Path reposDir) {
        return props(reposDir, "ghp_SECRETPAT", "glpat-SECRETTOKEN");
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        gitOut(dir, args);
    }

    private static String gitOut(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    private static int gitExit(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(dir, cmd, 60).exitCode();
    }

    /** 원격(bare)과 그 원격에 커밋을 더하는 seed. */
    private record Remote(Path seed, Path bare) {
        String uri() {
            return bare.toUri().toString();
        }

        /** seed에 커밋을 하나 더하고 원격 main에 올린다. 새 커밋 sha. */
        String commit(String file, String content) throws Exception {
            Files.writeString(seed.resolve(file), content);
            git(seed.toFile(), "add", "-A");
            git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", file);
            git(seed.toFile(), "push", bare.toString(), "main");
            return gitOut(seed.toFile(), "rev-parse", "HEAD");
        }
    }

    /** main에 커밋 1개짜리 file:// bare 원격. */
    private Remote remote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return new Remote(seed, bare);
    }

    private static WorkerTaskResponse.TaskRefRepo taskRef(String gitUrl, String sha) {
        return new WorkerTaskResponse.TaskRefRepo(12L, "옛앱", "acme/widgets", gitUrl, "github", "main", sha);
    }

    private static File prepare(GitRepoCache cache, RepoRef ref, String branch, String sha) throws Exception {
        return cache.withRepoLock(GitRemotes.localKey(ref),
                () -> cache.prepareRefCommitUnderLock(ref, branch, sha));
    }

    @Test
    void clones_a_missing_cache_with_a_plain_origin() throws Exception {
        Remote r = remote();
        String c1 = gitOut(r.seed().toFile(), "rev-parse", "HEAD");
        Path reposDir = tmp.resolve("repos");
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(r.uri(), c1));

        File dir = prepare(cache, ref, "main", c1);

        assertThat(dir.toPath()).isEqualTo(reposDir.resolve(GitRemotes.localKey(ref)));
        assertThat(dir.toPath().resolve(".git")).isDirectory();
        assertThat(gitOut(dir, "remote", "get-url", "origin")).isEqualTo(r.uri());
        assertThat(Files.readString(dir.toPath().resolve(".git").resolve("config"))).doesNotContain("oauth2:");
    }

    /** file:// 원격은 인증 URL이 평문 URL과 같아 위 검사가 실패할 수 없다 — 남아 있던 인증 origin이 씻기는지 따로 본다. */
    @Test
    void scrubs_a_credential_origin_left_in_an_existing_cache() throws Exception {
        Remote r = remote();
        String c1 = gitOut(r.seed().toFile(), "rev-parse", "HEAD");
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(r.uri(), c1));
        File dir = prepare(cache, ref, "main", c1);
        git(dir, "remote", "set-url", "origin", "https://oauth2:x@h/p.git");

        prepare(cache, ref, "main", c1);

        assertThat(gitOut(dir, "remote", "get-url", "origin")).isEqualTo(r.uri());
        assertThat(Files.readString(dir.toPath().resolve(".git").resolve("config"))).doesNotContain("oauth2:");
    }

    @Test
    void a_commit_already_in_the_cache_needs_no_fetch() throws Exception {
        Remote r = remote();
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(r.uri(), null));
        File dir = cache.ensureFresh(ref, "main").dir();
        String head = gitOut(dir, "rev-parse", "HEAD");
        assertThat(dir.toPath().resolve(".git").resolve("FETCH_HEAD")).as("전제: clone만 한 캐시").doesNotExist();

        assertThat(prepare(cache, ref, "main", head)).isEqualTo(dir);

        assertThat(dir.toPath().resolve(".git").resolve("FETCH_HEAD")).doesNotExist();
    }

    @Test
    void fetches_a_missing_commit_by_sha_without_moving_origin_or_head() throws Exception {
        Remote r = remote();
        String c1 = gitOut(r.seed().toFile(), "rev-parse", "HEAD");
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(r.uri(), c1));
        File dir = prepare(cache, ref, "main", c1);
        String c2 = r.commit("b.txt", "second");

        prepare(cache, ref, "main", c2);

        assertThat(gitExit(dir, "cat-file", "-e", c2 + "^{commit}")).isZero();
        assertThat(gitOut(dir, "rev-parse", "refs/remotes/origin/main")).isEqualTo(c1);
        assertThat(gitOut(dir, "rev-parse", "HEAD")).isEqualTo(c1);
        assertThat(gitExit(dir, "rev-parse", "--verify", "--quiet", GitRepoCache.REF_NAMESPACE + "main"))
                .as("sha로 받았으면 대안 이름공간은 쓰지 않는다").isNotZero();
    }

    @Test
    void falls_back_to_the_ref_namespace_when_sha_wants_are_refused() throws Exception {
        Remote r = remote();
        String c1 = gitOut(r.seed().toFile(), "rev-parse", "HEAD");
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(r.uri(), c1));
        File dir = prepare(cache, ref, "main", c1);
        git(dir, "config", "protocol.version", "0");
        git(r.bare().toFile(), "config", "uploadpack.allowTipSHA1InWant", "false");
        git(r.bare().toFile(), "config", "uploadpack.allowReachableSHA1InWant", "false");
        git(r.bare().toFile(), "config", "uploadpack.allowAnySHA1InWant", "false");
        String c2 = r.commit("b.txt", "second");
        String c3 = r.commit("c.txt", "third");
        // 전제: 광고되지 않은 커밋을 sha로 달라는 요청은 거부된다
        assertThat(gitExit(dir, "fetch", "--no-tags", "--depth=1", r.uri(), c2)).isNotZero();

        prepare(cache, ref, "main", c2);

        assertThat(gitExit(dir, "cat-file", "-e", c2 + "^{commit}")).isZero();
        assertThat(gitOut(dir, "rev-parse", GitRepoCache.REF_NAMESPACE + "main")).isEqualTo(c3);
        assertThat(gitOut(dir, "rev-parse", "refs/remotes/origin/main")).isEqualTo(c1);
        assertThat(gitOut(dir, "rev-parse", "HEAD")).isEqualTo(c1);
    }

    /**
     * 끝까지 없으면 깊이 500에서 멈춘다 — 원격 이력(502개)을 다 받지 않아 캐시는 shallow로 남고
     * 대안 이름공간의 이력은 정확히 500개다. 원격 커밋이 적으면 50 깊이에서 이력이 다 와 shallow가 풀리므로
     * fast-import로 502개짜리 원격을 만든다.
     */
    @Test
    void a_commit_found_nowhere_fails_with_a_clear_reason_and_stays_shallow() throws Exception {
        Path bare = tmp.resolve("many.git");
        git(tmp.toFile(), "init", "--bare", "-b", "main", bare.toString());
        fastImport(bare, 502);
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = WorktreeService.repoRefOf(taskRef(bare.toUri().toString(), MISSING_SHA));

        assertThatThrownBy(() -> prepare(cache, ref, "main", MISSING_SHA))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("고정 커밋 0123456")
                .hasMessageContaining("최근 500개");

        File dir = tmp.resolve("repos").resolve(GitRemotes.localKey(ref)).toFile();
        assertThat(gitOut(dir, "rev-parse", "--is-shallow-repository")).isEqualTo("true");
        assertThat(gitOut(dir, "rev-list", "--count", GitRepoCache.REF_NAMESPACE + "main")).isEqualTo("500");
    }

    /** 커밋 n개짜리 선형 이력을 bare 레포 main에 넣는다(커밋마다 git commit을 부르는 것보다 훨씬 빠르다). */
    private void fastImport(Path bare, int n) throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            String msg = "c" + i + "\n";
            String content = i + "\n";
            s.append("commit refs/heads/main\n")
                    .append("committer t <t@t> ").append(1_700_000_000L + i).append(" +0000\n")
                    .append("data ").append(msg.length()).append('\n').append(msg)
                    .append("M 644 inline f.txt\n")
                    .append("data ").append(content.length()).append('\n').append(content)
                    .append('\n');
        }
        Path stream = tmp.resolve("import.txt");
        Files.writeString(stream, s.toString());
        Process p = new ProcessBuilder("git", "fast-import", "--quiet")
                .directory(bare.toFile())
                .redirectInput(stream.toFile())
                .redirectErrorStream(true)
                .redirectOutput(tmp.resolve("import.log").toFile())
                .start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(Files.readString(tmp.resolve("import.log"))).isZero();
    }

    @Test
    void rejects_a_malformed_sha_before_running_git() throws Exception {
        Path reposDir = tmp.resolve("repos");
        GitRepoCache cache = new GitRepoCache(props(reposDir));
        RepoRef ref = new RepoRef("github", "acme/widgets", "file:///nowhere/remote.git");

        for (String bad : Arrays.asList(null, "", "--upload-pack=x", MISSING_SHA.substring(1))) {
            assertThatThrownBy(() -> prepare(cache, ref, "main", bad))
                    .as("sha=%s", bad)
                    .isInstanceOf(IOException.class)
                    .hasMessage("고정 커밋 정보가 없거나 올바르지 않습니다");
        }
        assertThatThrownBy(() -> prepare(cache, ref, " ", MISSING_SHA))
                .isInstanceOf(IOException.class)
                .hasMessage("참조 브랜치 정보가 없습니다");

        assertThat(reposDir.resolve("acme").resolve("widgets")).doesNotExist();
    }

    @Test
    void full_shas_are_40_or_64_hex_characters() {
        assertThat(GitRepoCache.isFullSha(MISSING_SHA)).isTrue();
        assertThat(GitRepoCache.isFullSha(MISSING_SHA.toUpperCase())).isTrue();
        assertThat(GitRepoCache.isFullSha("ab".repeat(32))).isTrue();
        assertThat(GitRepoCache.isFullSha(null)).isFalse();
        assertThat(GitRepoCache.isFullSha(MISSING_SHA.substring(1))).isFalse();
        assertThat(GitRepoCache.isFullSha(MISSING_SHA + "0")).isFalse();
        assertThat(GitRepoCache.isFullSha("g".repeat(40))).isFalse();
        assertThat(GitRepoCache.isFullSha(MISSING_SHA + "\n")).isFalse();
    }

    @Test
    void ref_git_never_uses_a_helper_askpass_or_prompt() {
        String u = "https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git";
        String[] prefix = GitRepoCache.REF_GIT_PREFIX.toArray(String[]::new);

        assertThat(GitRepoCache.REF_GIT_PREFIX)
                .containsExactly("git", "-c", "credential.helper=", "-c", "core.askPass=");
        assertThat(GitRepoCache.REF_GIT_ENV)
                .containsExactlyInAnyOrderEntriesOf(Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "never"));
        assertThat(GitRepoCache.REF_GIT_ENV_REMOVED).isEqualTo(Set.of("GIT_ASKPASS", "SSH_ASKPASS"));
        assertThat(GitRepoCache.REF_FALLBACK_DEPTHS).containsExactly(50, 500);

        assertThat(GitRepoCache.refGit("status")).startsWith(prefix).endsWith("status");
        assertThat(GitRepoCache.refCloneArgs(u, "main", "p")).startsWith(prefix)
                .containsSubsequence("clone", "--depth=1", "--branch", "main", u, "p");
        assertThat(GitRepoCache.refFetchShaArgs(u, MISSING_SHA)).startsWith(prefix)
                .containsSubsequence("fetch", "--no-tags", "--depth=1", u, MISSING_SHA);
        assertThat(GitRepoCache.refFetchBranchArgs(u, "release/6.5", 50)).startsWith(prefix)
                .containsSubsequence("fetch", "--no-tags", "--depth=50", u,
                        "+refs/heads/release/6.5:refs/netis-refs/release/6.5");
        // origin에는 평문 URL만 — 토큰이 설정돼 있어도
        assertThat(GitRepoCache.refScrubArgs(RepoRef.fromSnapshot("g/p", "https://gitlab.hamon.vip/g/p.git")))
                .startsWith(prefix)
                .endsWith("remote", "set-url", "origin", "https://gitlab.hamon.vip/g/p.git");

        // 단일 통로: refGit으로 조립하지 않은 명령은 받지 않는다(앞 인자를 두 번 붙이지도 않는다)
        assertThatThrownBy(() -> GitRepoCache.runRefGit(tmp.toFile(), List.of("git", "status"), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failure_message_never_contains_the_token() {
        GitRepoCache cache = new GitRepoCache(props(tmp.resolve("repos")));
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        assertThatThrownBy(() -> prepare(cache, ref, "main", MISSING_SHA))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("glpat-").doesNotContain("oauth2:glpat"));
    }

    /**
     * 토큰 없는 비공개 참조(401) — 운영자 PC의 자격증명 도우미(GCM)·askpass·터미널 프롬프트를 쓰지 않으니
     * 창이나 입력 대기 없이 곧바로 실패한다(Review Focus 1). 실패한 clone은 git이 폴더를 지우므로 다음 준비가 다시 clone한다.
     */
    @Test
    void a_401_remote_fails_fast_without_prompting() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"netis-test\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        try {
            Path reposDir = tmp.resolve("repos");
            GitRepoCache cache = new GitRepoCache(props(reposDir, null, null));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/g/p.git";
            var taskRef = new WorkerTaskResponse.TaskRefRepo(3L, "비공개", "g/p", url, "gitlab", "main", MISSING_SHA);
            RepoRef ref = WorktreeService.repoRefOf(taskRef);
            String refKey = GitRemotes.localKey(ref);
            long started = System.nanoTime();

            assertThatThrownBy(() -> prepare(cache, ref, "main", MISSING_SHA))
                    .isInstanceOf(IOException.class)
                    .satisfies(e -> assertThat(e.getMessage())
                            .containsAnyOf("terminal prompts disabled", "Authentication failed"));

            assertThat((System.nanoTime() - started) / 1_000_000).as("입력을 기다리지 않는다").isLessThan(20_000);
            assertThat(reposDir.resolve(refKey).resolve(".git")).doesNotExist();
            assertThat(reposDir.resolve(refKey)).doesNotExist();
        } finally {
            server.stop(0);
        }
    }
}
