package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cloudflare DNS REST를 흉내 내는 인메모리 가짜 서버로 upsert/delete/listOwned와 토큰 비노출을 검증. */
class CloudflareDnsRegistrarTest {

    private static final String TOKEN = "cf-secret-TOKEN-123";
    private static final String PREFIX = "/client/v4/zones/zone-1/dns_records";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();          // "METHOD path?query"
    private final List<String> authHeaders = new ArrayList<>();
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();   // id → record
    private int nextId = 1;
    private int failStatus = 0;                                   // 0 = 정상 응답

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String rawQuery = ex.getRequestURI().getRawQuery();
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (rawQuery == null ? "" : "?" + rawQuery));
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            Object resp;
            if (failStatus != 0) {
                status = failStatus;
                resp = Map.of("success", false, "result", List.of(),
                        "errors", List.of(Map.of("code", 10000, "message", "Authentication error")));
            } else {
                resp = handle(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), query(rawQuery), body);
            }
            byte[] bytes = JSON.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private Object handle(String method, String path, Map<String, String> q, String body) throws IOException {
        String id = path.length() > PREFIX.length() ? path.substring(PREFIX.length() + 1) : null;
        switch (method) {
            case "GET" -> {
                List<Map<String, Object>> hits = records.values().stream()
                        .filter(r -> !q.containsKey("name.exact") || q.get("name.exact").equals(r.get("name")))
                        .filter(r -> !q.containsKey("comment.exact") || q.get("comment.exact").equals(r.get("comment")))
                        .toList();
                int per = Integer.parseInt(q.getOrDefault("per_page", "100"));
                int page = Integer.parseInt(q.getOrDefault("page", "1"));
                int totalPages = Math.max(1, (hits.size() + per - 1) / per);
                List<Map<String, Object>> slice = hits.subList(
                        Math.min((page - 1) * per, hits.size()), Math.min(page * per, hits.size()));
                return Map.of("success", true, "errors", List.of(), "result", slice,
                        "result_info", Map.of("page", page, "per_page", per, "total_pages", totalPages));
            }
            case "POST" -> {
                Map<String, Object> r = new LinkedHashMap<>(JSON.readValue(body, Map.class));
                String nid = "rec-" + nextId++;
                r.put("id", nid);
                records.put(nid, r);
                return Map.of("success", true, "errors", List.of(), "result", r);
            }
            case "PUT" -> {
                Map<String, Object> r = new LinkedHashMap<>(JSON.readValue(body, Map.class));
                r.put("id", id);
                records.put(id, r);
                return Map.of("success", true, "errors", List.of(), "result", r);
            }
            case "DELETE" -> {
                records.remove(id);
                return Map.of("success", true, "errors", List.of(), "result", Map.of("id", id));
            }
            default -> throw new IllegalStateException(method);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void seed(String name, String content, String comment) {
        Map<String, Object> r = new LinkedHashMap<>();
        String id = "rec-" + nextId++;
        r.put("id", id);
        r.put("type", "CNAME");
        r.put("name", name);
        r.put("content", content);
        r.put("comment", comment);
        records.put(id, r);
    }

    private String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
    }

    private CloudflareDnsRegistrar registrar() {
        return new CloudflareDnsRegistrar(apiBase(), TOKEN, "zone-1", "tun-1", "win-worker-1");
    }

    @Test
    void upsert_creates_proxied_cname_to_tunnel_with_owner_comment() throws Exception {
        registrar().upsert("task-7-win.micthebick.dev");

        assertThat(records).hasSize(1);
        Map<String, Object> r = records.values().iterator().next();
        assertThat(r).containsEntry("type", "CNAME")
                .containsEntry("name", "task-7-win.micthebick.dev")
                .containsEntry("content", "tun-1.cfargotunnel.com")
                .containsEntry("proxied", true)
                .containsEntry("ttl", 1)
                .containsEntry("comment", "netis-maker:win-worker-1");
        assertThat(seen.get(0)).startsWith("GET " + PREFIX + "?").contains("name.exact=task-7-win.micthebick.dev");
        assertThat(seen.get(1)).isEqualTo("POST " + PREFIX);
        assertThat(authHeaders).containsOnly("Bearer " + TOKEN);
    }

    @Test
    void upsert_updates_existing_record_instead_of_duplicating() throws Exception {
        seed("task-7-win.micthebick.dev", "old.example.com", null);

        registrar().upsert("task-7-win.micthebick.dev");

        assertThat(records).hasSize(1);
        assertThat(records.get("rec-1")).containsEntry("content", "tun-1.cfargotunnel.com")
                .containsEntry("comment", "netis-maker:win-worker-1");
        assertThat(seen.get(seen.size() - 1)).isEqualTo("PUT " + PREFIX + "/rec-1");
    }

    @Test
    void delete_removes_record_and_is_noop_when_absent() throws Exception {
        seed("task-7-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:win-worker-1");
        CloudflareDnsRegistrar reg = registrar();

        reg.delete("task-7-win.micthebick.dev");
        assertThat(records).isEmpty();
        assertThat(seen).contains("DELETE " + PREFIX + "/rec-1");

        seen.clear();
        reg.delete("task-7-win.micthebick.dev");
        assertThat(seen).hasSize(1).allMatch(s -> s.startsWith("GET "));
    }

    @Test
    void listOwned_returns_only_tagged_records_across_pages() throws Exception {
        for (int i = 1; i <= 150; i++) {
            seed("task-" + i + "-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:win-worker-1");
        }
        seed("manual.micthebick.dev", "tun-1.cfargotunnel.com", null);
        seed("task-999-win.micthebick.dev", "tun-1.cfargotunnel.com", "netis-maker:other-worker");

        Set<String> owned = registrar().listOwned();

        assertThat(owned).hasSize(150)
                .contains("task-1-win.micthebick.dev", "task-150-win.micthebick.dev")
                .doesNotContain("manual.micthebick.dev", "task-999-win.micthebick.dev");
        assertThat(seen).anyMatch(s -> s.contains("page=2"));
        assertThat(seen).allMatch(s -> s.contains("comment.exact=netis-maker%3Awin-worker-1"));
    }

    @Test
    void api_failure_throws_with_cloudflare_message_but_never_the_token() {
        failStatus = 403;

        assertThatThrownBy(() -> registrar().upsert("task-7-win.micthebick.dev"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Authentication error")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void fromConfig_lists_every_missing_setting() {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", " ", null, ""));

        assertThatThrownBy(() -> CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDFLARE_API_TOKEN")
                .hasMessageContaining("CLOUDFLARE_ZONE_ID")
                .hasMessageContaining("CLOUDFLARE_TUNNEL_ID");
    }

    @Test
    void fromConfig_requires_public_access_enabled() {
        var pa = new WorkerProperties.Deploy.PublicAccess(false, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", TOKEN, "zone-1", "tun-1"));

        assertThatThrownBy(() -> CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEPLOY_PUBLIC_ACCESS_ENABLED")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void fromConfig_builds_a_working_registrar() throws Exception {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, null, null, "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", TOKEN, "zone-1", "tun-1"));

        CloudflareDnsRegistrar.fromConfig(pa, "win-worker-1", apiBase()).upsert("task-8-win.micthebick.dev");

        assertThat(records.values()).extracting(r -> r.get("name")).containsExactly("task-8-win.micthebick.dev");
    }
}
