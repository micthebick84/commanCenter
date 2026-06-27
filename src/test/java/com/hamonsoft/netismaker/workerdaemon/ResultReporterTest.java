package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResultReporterTest {

    private static final WorkerResultRequest REQ = new WorkerResultRequest(
            "w1", TaskStatus.PR_CREATED, null, null, null, 1L, null,
            "https://x/pull/1", 1, "netismaker/task-1", "sha", "log",
            null, null, null, null, null);

    /** maxRetries=2, backoff=1ms, no-op sleeper. */
    private ResultReporter reporter(ResultReporter.Poster poster) {
        return new ResultReporter(poster, 2, 1L, ms -> { /* no sleep */ });
    }

    @Test
    void success_on_first_attempt_returns_true_and_calls_once() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> calls.incrementAndGet()).reportTerminal(1L, REQ);
        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void transient_5xx_twice_then_success_retries_and_returns_true() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            if (calls.incrementAndGet() <= 2) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }).reportTerminal(1L, REQ);
        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(3); // 2 실패 + 1 성공
    }

    @Test
    void persistent_5xx_exhausts_retries_returns_false_without_throwing() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            calls.incrementAndGet();
            throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
        }).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(calls.get()).isEqualTo(3); // maxRetries(2) + 최초 1 = 3 시도
    }

    @Test
    void permanent_4xx_not_retried_returns_false() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.CONFLICT);
        }).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(calls.get()).isEqualTo(1); // 4xx는 재시도 안 함
    }

    @Test
    void unexpected_runtime_exception_returns_false_without_throwing() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(calls.get()).isEqualTo(1); // 예기치 못한 예외는 재시도 안 함
    }
}
