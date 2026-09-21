package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProcessRunner는 명령 원문(argv)과 캡처한 출력을 로그·예외로 내보낸다.
 * push가 인증 URL을 argv로 받게 되면서 이 경로에도 토큰이 실린다
 * (application.yml이 com.hamonsoft.netismaker를 DEBUG로 둠 → worker.log 평문 노출).
 * 네 지점(run/runStreaming의 debug 로그 + 양쪽 timeout 메시지)과 ProcessException 메시지가
 * 모두 같은 마스킹 헬퍼를 거쳐야 한다.
 */
class ProcessRunnerMaskTest {

    private static final String AUTH_URL = "https://oauth2:glpat-X@gitlab.invalid.example/g/p.git";

    @Test
    void describe_masks_credentials_in_the_command() {
        String described = ProcessRunner.describe(
                List.of("git", "-c", "credential.helper=", "push", AUTH_URL, "HEAD:refs/heads/b"));

        assertThat(described)
                .isEqualTo("git -c credential.helper= push "
                        + "https://***@gitlab.invalid.example/g/p.git HEAD:refs/heads/b")
                .doesNotContain("glpat-X");
    }

    @Test
    void describe_leaves_a_command_without_credentials_alone() {
        assertThat(ProcessRunner.describe(List.of("git", "status", "--porcelain")))
                .isEqualTo("git status --porcelain");
    }

    /** 실제로 실패하는 git 명령 — argv에 인증 URL이 실리는 경로(명령 쪽)를 검증. */
    @Test
    void process_exception_message_masks_the_failed_command() {
        List<String> cmd = List.of("git", "-c", "credential.helper=", "ls-remote", AUTH_URL);

        assertThatThrownBy(() -> ProcessRunner.requireSuccess(new File("."), cmd, 60))
                .isInstanceOf(ProcessRunner.ProcessException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage())
                            .doesNotContain("glpat-X")
                            .doesNotContain("oauth2:glpat");
                    // 마스킹돼도 어떤 명령이 실패했는지는 남아야 한다 (진단성)
                    assertThat(e.getMessage())
                            .contains("ls-remote")
                            .contains("https://***@gitlab.invalid.example");
                });
    }

    /**
     * 캡처한 출력 쪽 경로. git 자신은 에러 메시지에서 자격증명을 지우지만, 다른 명령
     * (docker/gradle/claude 등)은 그러지 않으므로 출력도 생성 시점에 마스킹돼야 한다.
     */
    @Test
    void process_exception_message_masks_the_captured_output() {
        var e = new ProcessRunner.ProcessException(
                List.of("git", "push", "origin", "HEAD"), 128,
                "fatal: Authentication failed for '" + AUTH_URL + "'\n");

        assertThat(e.getMessage())
                .doesNotContain("glpat-X")
                .contains("https://***@gitlab.invalid.example")
                .contains("Authentication failed");
        // 원본 stdout 접근자는 손대지 않는다 (보고 경계에서 따로 마스킹됨)
        assertThat(e.getStdout()).contains("glpat-X");
    }
}
