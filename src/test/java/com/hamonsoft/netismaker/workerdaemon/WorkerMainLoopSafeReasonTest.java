package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WorkerMainLoop.safeReason}(경계 마스킹 헬퍼) 단위 테스트.
 *
 * ProcessRunner.ProcessException은 실패한 git 명령의 원문(인증 URL 포함)을 메시지에 그대로
 * 담는다. 토큰이 폐기/스코프 부족이면 `git push`가 인증 URL을 에코하는데, 이 문자열이
 * failure_reason으로 그대로 나가면 작업 상세 화면에서 토큰이 노출된다. safeReason은 모든
 * safePost*Failure 경로에서 이 값을 감싸는 단일 지점이다.
 */
class WorkerMainLoopSafeReasonTest {

    @Test
    void masks_a_credential_embedded_in_a_git_failure_message() {
        String raw = "fatal: Authentication failed for 'https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git/'";

        String masked = WorkerMainLoop.safeReason(raw);

        assertThat(masked).contains("https://***@gitlab.hamon.vip");
        assertThat(masked).doesNotContain("glpat-X");
    }

    @Test
    void null_stays_null() {
        assertThat(WorkerMainLoop.safeReason(null)).isNull();
    }

    @Test
    void a_string_without_credentials_is_unchanged() {
        String reason = "claude exit=1\nsome ordinary output without any secrets";

        assertThat(WorkerMainLoop.safeReason(reason)).isEqualTo(reason);
    }
}
