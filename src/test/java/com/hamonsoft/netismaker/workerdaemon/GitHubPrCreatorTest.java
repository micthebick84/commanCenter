package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 본문은 명령행이 아니라 --body-file로 — 긴 본문이 Windows 명령행 한도(32,767자)에 걸려 PR 생성이 실패하지 않게
 * (최종 리뷰). gh는 가짜로 갈아 끼워 만든 명령·본문 파일·환경변수를 본다.
 */
class GitHubPrCreatorTest {

    private static final RepoRef REF = RepoRef.fromSnapshot("acme/widgets", null);
    private static final String PR_URL = "https://github.com/acme/widgets/pull/12";

    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> envs = new ArrayList<>();
    private final List<Path> bodyFiles = new ArrayList<>();
    private final List<String> bodiesGhSaw = new ArrayList<>();

    /** gh가 실행되는 동안 본문 파일을 읽어 둔다 — 실제 gh가 읽는 시점의 내용. */
    private GitHubPrCreator creator(String ghOutput) {
        return new GitHubPrCreator("ghp_PAT", (dir, cmd, env) -> {
            commands.add(cmd);
            envs.add(env);
            Path file = Path.of(cmd.get(cmd.indexOf("--body-file") + 1));
            bodyFiles.add(file);
            bodiesGhSaw.add(Files.readString(file, StandardCharsets.UTF_8));
            return new ProcessRunner.Result(0, ghOutput);
        });
    }

    @Test
    void the_body_goes_through_a_file_so_the_command_line_stays_short() throws Exception {
        String body = "## netisMaker 자동 생성 PR\n- **구현 방식**: SDD\n" + "가".repeat(100_000);

        GitOpsService.PrInfo pr = creator(PR_URL + "\n")
                .create(null, REF, "main", "netismaker/task-1-x", "제목", body);

        assertThat(pr.number()).isEqualTo(12);
        assertThat(pr.url()).isEqualTo(PR_URL);
        List<String> cmd = commands.get(0);
        assertThat(cmd).doesNotContain("--body").doesNotContain(body);
        assertThat(String.join(" ", cmd).length()).as("본문 길이와 무관하게 명령행이 짧다").isLessThan(500);
        assertThat(cmd).containsSequence("gh", "pr", "create")
                .containsSequence("--repo", "acme/widgets")
                .contains("--draft")
                .containsSequence("--base", "main")
                .containsSequence("--head", "netismaker/task-1-x")
                .containsSequence("--title", "제목");
        assertThat(bodiesGhSaw.get(0)).as("gh가 읽은 본문 파일(UTF-8)").isEqualTo(body);
        assertThat(envs.get(0)).containsEntry("GH_TOKEN", "ghp_PAT");
    }

    @Test
    void the_body_file_is_deleted_after_a_successful_create() throws Exception {
        creator(PR_URL).create(null, REF, "main", "h", "t", "본문");

        assertThat(bodyFiles).hasSize(1);
        assertThat(bodyFiles.get(0)).doesNotExist();
    }

    @Test
    void the_body_file_is_deleted_when_gh_prints_no_pr_url() {
        assertThatThrownBy(() -> creator("gh: 인증 필요").create(null, REF, "main", "h", "t", "본문"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("PR URL 파싱 실패");

        assertThat(bodyFiles).hasSize(1);
        assertThat(bodyFiles.get(0)).doesNotExist();
    }

    @Test
    void the_body_file_is_deleted_when_gh_itself_fails() {
        Path[] seen = new Path[1];
        GitHubPrCreator failing = new GitHubPrCreator(null, (dir, cmd, env) -> {
            seen[0] = Path.of(cmd.get(cmd.indexOf("--body-file") + 1));
            throw new IOException("gh 실행 불가");
        });

        assertThatThrownBy(() -> failing.create(null, REF, "main", "h", "t", "본문"))
                .isInstanceOf(IOException.class).hasMessageContaining("gh 실행 불가");

        assertThat(seen[0]).doesNotExist();
    }

    @Test
    void without_a_pat_no_token_env_is_set() throws Exception {
        List<Map<String, String>> seenEnv = new ArrayList<>();
        new GitHubPrCreator(" ", (dir, cmd, env) -> {
            seenEnv.add(env);
            return new ProcessRunner.Result(0, PR_URL);
        }).create(null, REF, "main", "h", "t", "본문");

        assertThat(seenEnv.get(0)).doesNotContainKey("GH_TOKEN");
    }

    @Test
    void supports_every_ref_that_is_not_gitlab() {
        assertThat(new GitHubPrCreator("x").supports(REF)).isTrue();
        assertThat(new GitHubPrCreator("x").supports(RepoRef.fromSnapshot("g/p", "https://gitlab.example/g/p.git")))
                .isFalse();
    }
}
