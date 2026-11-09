package com.hamonsoft.netismaker.workerdaemon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * next-task 요청이 참조 repo·수정 회차·개발 DB 쓰기 지원을 알린다 — API는 알리지 않은 워커에게 참조 있는 구현(스펙 2026-10-24 §4.7)과
 * 수정 회차 구현(스펙 2026-11-03 §7)을 넘기지 않고, 개발 DB 쓰기 도구를 붙이지 않는다(task-type-db-write §6.1 조건 6).
 */
class WorkerHttpClientNextTaskTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WorkerProperties propsFor(AtomicReference<String> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/next-task", ex -> {
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(props.apiKey()).thenReturn("k1");
        when(props.id()).thenReturn("w1");
        return props;
    }

    /** mockups — 인터뷰 목업을 파일로 두고 프롬프트에 절을 넣는 워커(설계 2026-11-03 §7). 없으면 API가 목업 있는 구현·디자인을 넘기지 않는다. */
    @Test
    void next_task_tells_the_api_it_supports_ref_repos_fix_rounds_db_write_and_mockups() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerProperties props = propsFor(seen);

        assertThat(new WorkerHttpClient(props).nextTask()).isEmpty();
        assertThat(seen.get())
                .isEqualTo("GET /worker/next-task?workerId=w1&supports=refRepos,fixRounds,dbWrite,mockups");
        assertThat(WorkerHttpClient.SUPPORTS).isEqualTo("refRepos,fixRounds,dbWrite,mockups");
    }

    /** WORKER_KINDS를 준 워커(다른 PC)는 받을 종류를 알린다 — 비면 예전 요청 그대로(위 테스트). */
    @Test
    void next_task_sends_the_chosen_kinds() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerProperties props = propsFor(seen);

        assertThat(new WorkerHttpClient(props, " design, implementation ").nextTask()).isEmpty();
        assertThat(seen.get())
                .isEqualTo("GET /worker/next-task?workerId=w1&supports=refRepos,fixRounds,dbWrite,mockups"
                        + "&kinds=IMPLEMENTATION,DESIGN");
    }

    @Test
    void blank_kinds_mean_every_kind() {
        assertThat(WorkerHttpClient.normalizeKinds(null)).isEmpty();
        assertThat(WorkerHttpClient.normalizeKinds("  ")).isEmpty();
        assertThat(WorkerHttpClient.normalizeKinds("close,,CLOSE")).isEqualTo("CLOSE");
    }

    @Test
    void a_typo_in_kinds_fails_at_startup() {
        assertThatThrownBy(() -> WorkerHttpClient.normalizeKinds("IMPLEMENTATION,DESGIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DESGIN");
    }
}
