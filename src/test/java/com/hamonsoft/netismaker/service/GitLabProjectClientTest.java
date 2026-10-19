package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.service.GitLabProjectClient.GitLabProject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabProjectClientTest {

    private static final String TOKEN = "tok-123";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> rawPaths = new CopyOnWriteArrayList<>();
    private final List<String> rawQueries = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            rawPaths.add(ex.getRequestURI().getRawPath());
            rawQueries.add(String.valueOf(ex.getRequestURI().getRawQuery()));
            tokens.add(String.valueOf(ex.getRequestHeaders().getFirst("PRIVATE-TOKEN")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private GitLabProjectClient client(String token) {
        return new GitLabProjectClient(baseUrl(), token, new ObjectMapper());
    }

    @Test
    void search_parses_and_sends_token() {
        body = "[{\"id\":5,\"name\":\"netis-collector\",\"path_with_namespace\":\"netis/netis-collector\","
                + "\"default_branch\":\"main\",\"description\":\"수집기\"}]";

        List<GitLabProject> result = client(TOKEN).search("collector", 5);

        assertThat(result).containsExactly(
                new GitLabProject(5, "netis-collector", "netis/netis-collector", "main", "수집기"));
        assertThat(tokens).containsExactly(TOKEN);
        assertThat(rawPaths).containsExactly("/api/v4/projects");
        assertThat(rawQueries.get(0)).contains("search=collector").contains("per_page=5")
                .contains("simple=true").contains("archived=false").contains("order_by=last_activity_at");
    }

    @Test
    void search_maps_null_description_and_missing_default_branch_to_null() {
        body = "[{\"id\":6,\"name\":\"empty\",\"path_with_namespace\":\"g/empty\",\"description\":null}]";

        assertThat(client(TOKEN).search("e", 5)).containsExactly(
                new GitLabProject(6, "empty", "g/empty", null, null));
    }

    @Test
    void find_by_path_encodes_slashes() {
        body = "{\"id\":7,\"name\":\"p\",\"path_with_namespace\":\"g/sub/p\",\"default_branch\":\"dev\",\"description\":\"d\"}";

        Optional<GitLabProject> found = client(TOKEN).findByPath("g/sub/p");

        assertThat(rawPaths).containsExactly("/api/v4/projects/g%2Fsub%2Fp");
        assertThat(found).contains(new GitLabProject(7, "p", "g/sub/p", "dev", "d"));
    }

    @Test
    void find_by_id_hits_numeric_path() {
        body = "{\"id\":9,\"name\":\"p\",\"path_with_namespace\":\"g/p\",\"default_branch\":\"main\",\"description\":\"d\"}";

        assertThat(client(TOKEN).findById(9)).contains(new GitLabProject(9, "p", "g/p", "main", "d"));
        assertThat(rawPaths).containsExactly("/api/v4/projects/9");
    }

    @Test
    void find_by_path_404_is_empty() {
        status = 404;
        body = "{\"message\":\"404 Project Not Found\"}";

        assertThat(client(TOKEN).findByPath("g/none")).isEmpty();
    }

    @Test
    void find_by_id_404_is_empty() {
        status = 404;

        assertThat(client(TOKEN).findById(404)).isEmpty();
    }

    @Test
    void server_error_maps_to_502_without_token() {
        status = 500;
        body = "bad " + TOKEN;

        assertThatThrownBy(() -> client(TOKEN).search("x", 5))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).isEqualTo("GitLab 조회에 실패했습니다").doesNotContain(TOKEN);
                });
    }

    @Test
    void not_configured_throws_503() {
        assertThatThrownBy(() -> client("").search("x", 5))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("사내 GitLab이 설정되지 않았습니다");
                });
        assertThat(calls.get()).isZero();
    }

    @Test
    void configured_requires_both_base_url_and_token() {
        assertThat(client(TOKEN).configured()).isTrue();
        assertThat(client("").configured()).isFalse();
        assertThat(new GitLabProjectClient("", TOKEN, new ObjectMapper()).configured()).isFalse();
    }

    @Test
    void trailing_slash_of_base_url_is_trimmed() {
        body = "[]";

        new GitLabProjectClient(baseUrl() + "/", TOKEN, new ObjectMapper()).search("x", 5);

        assertThat(rawPaths).containsExactly("/api/v4/projects");
    }

    @Test
    void redact_masks_token() {
        assertThat(client(TOKEN).redact("bad tok-123 and tok-123")).isEqualTo("bad *** and ***");
        assertThat(client("").redact("nothing")).isEqualTo("nothing");
    }
}
