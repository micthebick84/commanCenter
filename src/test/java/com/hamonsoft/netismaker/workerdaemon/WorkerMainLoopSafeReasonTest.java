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

    /**
     * pollAndProcess의 최상위 catch는 Throwable을 slf4j에 그대로 넘기지 않는다 — 그러면
     * 메시지와 cause 체인이 마스킹 없이 로그로 나간다. 대신 cause 체인을 한 줄로 요약해 마스킹한다.
     */
    @Test
    void cause_chain_summary_is_maskable_and_covers_nested_causes() {
        Throwable root = new java.io.IOException(
                "fatal: could not read Username for 'https://oauth2:glpat-X@gitlab.hamon.vip'");
        Throwable wrapper = new IllegalStateException("commit/push 실패", root);

        String logged = WorkerMainLoop.safeReason(WorkerMainLoop.causeChain(wrapper));

        assertThat(logged).contains("commit/push 실패");
        assertThat(logged).contains("https://***@gitlab.hamon.vip");
        assertThat(logged).doesNotContain("glpat-X");
    }

    /** 자기 자신을 cause로 돌려주는 예외(직접 구현체)에도 요약이 끝나야 한다. */
    @Test
    void cause_chain_of_a_self_referencing_throwable_terminates() {
        class SelfCaused extends RuntimeException {
            SelfCaused() { super("boom"); }
            @Override public synchronized Throwable getCause() { return this; }
        }

        assertThat(WorkerMainLoop.causeChain(new SelfCaused())).contains("boom");
    }
}
