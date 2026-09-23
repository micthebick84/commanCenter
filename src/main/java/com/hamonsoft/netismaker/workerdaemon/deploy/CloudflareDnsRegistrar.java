package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cloudflare DNS REST로 배포별 CNAME을 관리한다 (cloudflared CLI 의존 없음).
 *   {apiBase}/zones/{zoneId}/dns_records — CNAME {host} → {tunnelId}.cfargotunnel.com, proxied.
 * 개별 레코드는 같은 zone의 와일드카드(*.micthebick.dev → 맥 터널)보다 우선하므로 맥 스택과 공존한다.
 * comment에 netis-maker:{workerId}를 달아 우리가 만든 레코드만 listOwned로 식별한다.
 * 토큰은 Authorization 헤더에만 싣고 로그·예외 메시지에는 절대 넣지 않는다.
 */
@Slf4j
public class CloudflareDnsRegistrar implements PublicDnsRegistrar {

    static final String DEFAULT_API_BASE = "https://api.cloudflare.com/client/v4";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiBase;
    private final String token;
    private final String zoneId;
    private final String target;
    private final String ownerTag;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CloudflareDnsRegistrar(String apiBase, String token, String zoneId, String tunnelId, String workerId) {
        this.apiBase = apiBase;
        this.token = token;
        this.zoneId = zoneId;
        this.target = tunnelId + ".cfargotunnel.com";
        this.ownerTag = "netis-maker:" + workerId;
    }

    /** 설정 검증 후 생성. 누락·모순은 IllegalStateException → 워커 기동 실패(배포 때가 아니라 부팅 때 드러냄). */
    public static CloudflareDnsRegistrar fromConfig(WorkerProperties.Deploy.PublicAccess pa, String workerId) {
        return fromConfig(pa, workerId, DEFAULT_API_BASE);
    }

    static CloudflareDnsRegistrar fromConfig(WorkerProperties.Deploy.PublicAccess pa, String workerId,
                                             String apiBase) {
        if (!pa.enabled()) {
            throw new IllegalStateException(
                    "DEPLOY_PUBLIC_DNS_PROVIDER=cloudflare는 DEPLOY_PUBLIC_ACCESS_ENABLED=true일 때만 쓸 수 있다");
        }
        WorkerProperties.Deploy.PublicAccess.Dns dns = pa.dns();
        List<String> missing = new ArrayList<>();
        if (blank(dns.apiToken())) missing.add("CLOUDFLARE_API_TOKEN");
        if (blank(dns.zoneId())) missing.add("CLOUDFLARE_ZONE_ID");
        if (blank(dns.tunnelId())) missing.add("CLOUDFLARE_TUNNEL_ID");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Cloudflare DNS 설정 누락: " + String.join(", ", missing));
        }
        return new CloudflareDnsRegistrar(apiBase, dns.apiToken().trim(), dns.zoneId().trim(),
                dns.tunnelId().trim(), workerId);
    }

    @Override
    public void upsert(String hostname) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(recordBody(hostname));
        Optional<String> id = findId(hostname);
        if (id.isPresent()) {
            call("PUT", records() + "/" + id.get(), body);
        } else {
            call("POST", records(), body);
        }
        log.info("공개 주소 DNS upsert: {} → {}", hostname, target);
    }

    @Override
    public void delete(String hostname) throws IOException, InterruptedException {
        Optional<String> id = findId(hostname);
        if (id.isEmpty()) return;
        call("DELETE", records() + "/" + id.get(), null);
        log.info("공개 주소 DNS 삭제: {}", hostname);
    }

    @Override
    public Set<String> listOwned() throws IOException, InterruptedException {
        Set<String> out = new LinkedHashSet<>();
        int page = 1;
        int totalPages;
        do {
            JsonNode res = call("GET", records() + "?type=CNAME&per_page=100&page=" + page
                    + "&comment.exact=" + enc(ownerTag), null);
            for (JsonNode r : res.path("result")) out.add(r.path("name").asText());
            totalPages = res.path("result_info").path("total_pages").asInt(1);
            page++;
        } while (page <= totalPages);
        return out;
    }

    private Optional<String> findId(String hostname) throws IOException, InterruptedException {
        JsonNode arr = call("GET", records() + "?type=CNAME&name.exact=" + enc(hostname), null).path("result");
        return arr.isArray() && !arr.isEmpty() ? Optional.of(arr.get(0).path("id").asText()) : Optional.empty();
    }

    private Map<String, Object> recordBody(String hostname) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "CNAME");
        m.put("name", hostname);
        m.put("content", target);
        m.put("proxied", true);
        m.put("ttl", 1);
        m.put("comment", ownerTag);
        return m;
    }

    private String records() {
        return apiBase + "/zones/" + zoneId + "/dns_records";
    }

    private JsonNode call(String method, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json;
        try {
            json = JSON.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (IOException e) {
            json = JSON.createObjectNode();
        }
        if (res.statusCode() / 100 != 2 || !json.path("success").asBoolean(false)) {
            throw new IOException("Cloudflare API " + method + " 실패 (HTTP " + res.statusCode() + "): " + errors(json));
        }
        return json;
    }

    /** 응답 errors[]의 code/message만 — 요청(헤더·URL)은 싣지 않는다. */
    private static String errors(JsonNode json) {
        List<String> parts = new ArrayList<>();
        for (JsonNode e : json.path("errors")) {
            parts.add(e.path("code").asText() + " " + e.path("message").asText());
        }
        return parts.isEmpty() ? "(오류 메시지 없음)" : String.join("; ", parts);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
