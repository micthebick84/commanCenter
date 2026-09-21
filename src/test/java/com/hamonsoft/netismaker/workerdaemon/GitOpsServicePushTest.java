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

/**
 * push는 origin의 자격증명에 기대지 않는다 — 캐시/워크트리 config에 토큰을 남기지 않기 위해
 * {@code git push <인증 URL> HEAD:refs/heads/<branch>}로 보낸다.
 * 인증 URL이 argv에 실리므로 실패 메시지는 반드시 마스킹된 채로 올라와야 한다.
 */
class GitOpsServicePushTest {

    @TempDir Path tmp;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0,
                tmp.resolve("repos").toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null, null, null, null, null,
                null, null, null, null, deploy);
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    /** file:// bare 레포 + main 브랜치 커밋 1개. */
    private Path bareRemote(String name) throws Exception {
        Path seed = Files.createDirectories(tmp.resolve(name + "-seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve(name + ".git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    /** bare에서 clone → 새 브랜치 체크아웃 → 커밋되지 않은 변경 1개. */
    private Path workingClone(Path bare, String dirName, String branch) throws Exception {
        Path clone = tmp.resolve(dirName);
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        git(clone.toFile(), "checkout", "-b", branch);
        Files.writeString(clone.resolve("impl.txt"), "구현 산출물");
        return clone;
    }

    @Test
    void push_uses_the_ref_url_so_a_stale_origin_does_not_break_it() throws Exception {
        Path bare = bareRemote("r1");
        Path clone = workingClone(bare, "work1", "netismaker/task-1");
        // origin에는 자격증명도 없고 최신 URL도 아니다 — push가 origin에 기대면 실패한다
        git(clone.toFile(), "remote", "set-url", "origin", "file:///nonexistent/stale.git");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        String sha = new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-1", "feat: 구현");

        assertThat(sha).hasSize(40);
        String pushed = ProcessRunner.requireSuccess(bare.toFile(),
                List.of("git", "rev-parse", "refs/heads/netismaker/task-1"), 30).trim();
        assertThat(pushed).isEqualTo(sha);
    }

    @Test
    void a_failing_push_throws_an_ioexception_whose_message_hides_the_token() throws Exception {
        Path bare = bareRemote("r2");
        Path clone = workingClone(bare, "work2", "netismaker/task-2");
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-2", "feat: 구현"))
                .isInstanceOf(IOException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage())
                            .doesNotContain("glpat-SECRETTOKEN")
                            .doesNotContain("oauth2:glpat");
                    // 원본 예외는 마스킹되지 않은 메시지를 갖고 있으므로 cause로 달지 않는다
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void nothing_to_commit_still_reports_the_dedicated_reason() throws Exception {
        Path bare = bareRemote("r3");
        Path clone = tmp.resolve("work3");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-3", "feat: 구현"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nothing to commit");
    }
}
