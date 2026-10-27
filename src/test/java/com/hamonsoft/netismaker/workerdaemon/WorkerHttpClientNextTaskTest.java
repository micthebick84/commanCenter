package com.hamonsoft.netismaker.workerdaemon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** next-task 요청이 참조 repo 지원을 알린다 — API는 알리지 않은 워커에게 참조 있는 구현을 넘기지 않는다 (스펙 2026-10-24 §4.7). */
class WorkerHttpClientNextTaskTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void next_task_tells_the_api_it_supports_ref_repos() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
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

        assertThat(new WorkerHttpClient(props).nextTask()).isEmpty();
        assertThat(seen.get()).isEqualTo("GET /worker/next-task?workerId=w1&supports=refRepos");
        assertThat(WorkerHttpClient.SUPPORTS).isEqualTo("refRepos");
    }
}
