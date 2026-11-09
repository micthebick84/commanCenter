package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 워커가 구현 전에 받는 이 작업의 DB 변경 기록 (스펙 2026-11-03 §5.2·§6.6) — 조회 실패는 빈 목록으로 읽지 않는다(fail-closed). */
class WorkerHttpClientDbChangesTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** status·본문을 돌려주는 /worker/tasks/42/db-changes 서버를 띄운 클라이언트. 요청 줄·키 헤더는 seen에 남는다. */
    private WorkerHttpClient clientFor(AtomicReference<String> seen, int status, String json) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/tasks/42/db-changes", ex -> {
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI() + " key=" + ex.getRequestHeaders().getFirst("X-Worker-API-Key"));
            if (json == null) {
                ex.sendResponseHeaders(status, -1);
            } else {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(props.apiKey()).thenReturn("k1");
        when(props.id()).thenReturn("w1");
        return new WorkerHttpClient(props);
    }

    @Test
    void 기록을_읽어_온다() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String json = "[{\"id\":12,\"connectionId\":5,\"connectionName\":\"개발 (PostgreSQL)\",\"attempt\":1,"
                + "\"fixRequestId\":null,\"sqlText\":\"CREATE TABLE t_new (id int)\",\"classification\":\"EXECUTED\","
                + "\"reason\":null,\"affectedRows\":0,\"error\":null,\"createdAt\":\"2026-11-04T01:00:00Z\","
                + "\"executedAt\":\"2026-11-04T01:00:01Z\",\"executedBy\":null,\"앞으로 생길 칸\":1},"
                + "{\"id\":13,\"connectionId\":null,\"connectionName\":\"개발 (PostgreSQL)\",\"attempt\":1,"
                + "\"fixRequestId\":9,\"sqlText\":\"ALTER TABLE t DROP COLUMN c\",\"classification\":\"DEFERRED\","
                + "\"createdAt\":\"2026-11-04T01:00:02Z\"}]";
        WorkerHttpClient client = clientFor(seen, 200, json);

        List<DbChangeView> out = client.dbChanges(42L);

        assertThat(seen.get()).isEqualTo("GET /worker/tasks/42/db-changes key=k1");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isEqualTo(12L);
        assertThat(out.get(0).connectionName()).isEqualTo("개발 (PostgreSQL)");
        assertThat(out.get(0).sqlText()).isEqualTo("CREATE TABLE t_new (id int)");
        assertThat(out.get(0).classification()).isEqualTo("EXECUTED");
        assertThat(out.get(0).createdAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T01:00:00Z"));
        assertThat(out.get(1).connectionId()).isNull();
        assertThat(out.get(1).fixRequestId()).isEqualTo(9L);
        assertThat(out.get(1).classification()).isEqualTo("DEFERRED");
    }

    @Test
    void 기록이_없으면_빈_목록() throws Exception {
        assertThat(clientFor(new AtomicReference<>(), 200, "[]").dbChanges(42L)).isEmpty();
    }

    /** 본문 없는 2xx는 '기록 없음'이 아니라 조회 실패다 — 빈 목록으로 읽으면 이미 적용한 SQL을 다시 실행한다. */
    @Test
    void 본문이_없으면_조회_실패() throws Exception {
        WorkerHttpClient client = clientFor(new AtomicReference<>(), 204, null);

        assertThatThrownBy(() -> client.dbChanges(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 변경 기록");
    }

    @Test
    void 서버_오류는_그대로_던진다() throws Exception {
        WorkerHttpClient client = clientFor(new AtomicReference<>(), 500, "{}");

        assertThatThrownBy(() -> client.dbChanges(42L)).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void requireDbChangesBody는_null만_거부한다() {
        assertThat(WorkerHttpClient.requireDbChangesBody(List.of())).isEmpty();
        assertThatThrownBy(() -> WorkerHttpClient.requireDbChangesBody(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
