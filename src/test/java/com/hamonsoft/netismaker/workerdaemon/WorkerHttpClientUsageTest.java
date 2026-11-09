package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 사용량 묶음 보내기 — 경로·키 헤더·본문, 404는 그대로 던진다(수집기가 위치를 두고 다음 회차에 다시 보낸다). */
class WorkerHttpClientUsageTest {

    private static final String PATH = "/worker/usage/claude-code-events";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private record Seen(String method, String path, String apiKey, String body) {}

    private WorkerHttpClient clientAnswering(int status, String json, AtomicReference<Seen> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("X-Worker-API-Key"), body));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            if (out.length > 0) ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(props.apiKey()).thenReturn("k1");
        when(props.id()).thenReturn("w1");
        return new WorkerHttpClient(props);
    }

    private static ClaudeCodeUsageBatchRequest request() {
        ClaudeCodeUsageEvent e = new ClaudeCodeUsageEvent("msg_1:req_1", OffsetDateTime.parse("2026-11-01T00:30:00Z"),
                "SYMPHONY", "claude-test-1", null, "standard", "cli", "s-1", false, 10, 20, 30, 0, 40);
        return new ClaudeCodeUsageBatchRequest("pc-a", 2, List.of(e));
    }

    @Test
    void 경로_헤더_본문() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        WorkerHttpClient client = clientAnswering(200,
                "{\"received\":1,\"inserted\":1,\"updated\":0,\"skipped\":0,\"extra\":true}", seen);

        ClaudeCodeUsageBatchResponse resp = client.postClaudeCodeUsage(request());

        assertThat(resp).isEqualTo(new ClaudeCodeUsageBatchResponse(1, 1, 0, 0));
        assertThat(seen.get().method()).isEqualTo("POST");
        assertThat(seen.get().path()).isEqualTo(PATH);
        assertThat(seen.get().apiKey()).isEqualTo("k1");
        JsonNode body = new ObjectMapper().readTree(seen.get().body());
        assertThat(body.path("machine").asText()).isEqualTo("pc-a");
        assertThat(body.path("unreadableLines").asInt()).isEqualTo(2);
        assertThat(body.path("events").get(0).path("eventKey").asText()).isEqualTo("msg_1:req_1");
        assertThat(body.path("events").get(0).path("outputTokens").asLong()).isEqualTo(20);
        assertThat(body.path("events").get(0).has("cwd")).isFalse();
        // API 쪽 Jackson(JavaTimeModule)으로 다시 읽으면 같은 순간이다(직렬화 형식과 무관하게)
        ClaudeCodeUsageEvent back = new ObjectMapper().findAndRegisterModules()
                .treeToValue(body.path("events").get(0), ClaudeCodeUsageEvent.class);
        assertThat(back.occurredAt().toInstant()).isEqualTo(request().events().get(0).occurredAt().toInstant());
    }

    @Test
    void 상태_404는_RestClientException() throws Exception {   // 식별자는 숫자로 시작할 수 없어 '상태_'를 붙임
        WorkerHttpClient client = clientAnswering(404, "", new AtomicReference<>());

        assertThatThrownBy(() -> client.postClaudeCodeUsage(request()))
                .isInstanceOf(RestClientException.class)
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void 응답_본문이_없으면_실패() throws Exception {
        WorkerHttpClient client = clientAnswering(200, "", new AtomicReference<>());

        assertThatThrownBy(() -> client.postClaudeCodeUsage(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("응답 본문 없음");
    }
}
