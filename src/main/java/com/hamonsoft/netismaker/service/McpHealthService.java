package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.repository.McpCatalogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * MCP 서버 헬스 체크.
 *
 * V1 probe 깊이:
 *   L1: HTTP 응답 + status code
 *   L2: SSE 시 content-type=text/event-stream 검증
 *
 * L3 (MCP initialize handshake)는 V2 — MCP 클라이언트 구현 필요.
 *
 * 분류:
 *   HEALTHY  — 200 OK + sse는 content-type 매치, http는 200이면 OK
 *   DEGRADED — 2xx인데 content-type 불일치, 또는 401/403 (auth 필요)
 *   DOWN     — 5xx, timeout, DNS/network 에러
 *
 * SSE 응답이 영원히 안 닫히는 특성 때문에 InputStream BodyHandler + 헤더만 읽고 즉시 close.
 */
@Service
@Profile("api")
@Slf4j
public class McpHealthService {

    public static final String STATUS_HEALTHY = "HEALTHY";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_DOWN = "DOWN";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final McpCatalogRepository repo;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public McpHealthService(McpCatalogRepository repo) {
        this.repo = repo;
    }

    /** 단일 entry probe + DB 갱신. 호출자가 분류 결과 받음. */
    @Transactional
    public ProbeResult checkAndPersist(Long entryId) {
        McpCatalogEntry e = repo.findById(entryId)
                .orElseThrow(() -> new TaskException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "카탈로그 항목 없음: " + entryId));
        ProbeResult r = probe(e);
        e.setLastCheckAt(OffsetDateTime.now());
        e.setLastCheckStatus(r.status());
        e.setLastCheckError(r.error());
        return r;
    }

    /** DB 갱신 없이 순수 probe. 호출자가 결과 처리. */
    public ProbeResult probe(McpCatalogEntry e) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(e.getUrl()))
                    .timeout(TIMEOUT)
                    .GET();
            if ("sse".equalsIgnoreCase(e.getTransport())) {
                reqBuilder.header("Accept", "text/event-stream");
            }
            HttpResponse<java.io.InputStream> resp = http.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try {
                int code = resp.statusCode();
                String ct = resp.headers().firstValue("content-type").orElse("");
                long elapsed = System.currentTimeMillis() - start;
                if (code >= 200 && code < 300) {
                    if ("sse".equalsIgnoreCase(e.getTransport())) {
                        if (ct.toLowerCase().contains("text/event-stream")) {
                            return new ProbeResult(STATUS_HEALTHY, null, elapsed);
                        }
                        return new ProbeResult(STATUS_DEGRADED,
                                "SSE 기대했으나 content-type=" + (ct.isEmpty() ? "(빈값)" : ct), elapsed);
                    }
                    return new ProbeResult(STATUS_HEALTHY, null, elapsed);
                }
                if (code == 401 || code == 403) {
                    return new ProbeResult(STATUS_DEGRADED,
                            "HTTP " + code + " — 인증 필요 (헬스 체크는 헤더 미지원, 실제 동작 가능성 있음)", elapsed);
                }
                if (code >= 400 && code < 500) {
                    return new ProbeResult(STATUS_DEGRADED, "HTTP " + code, elapsed);
                }
                return new ProbeResult(STATUS_DOWN, "HTTP " + code, elapsed);
            } finally {
                try { resp.body().close(); } catch (Exception ignored) {}
            }
        } catch (java.net.http.HttpTimeoutException ex) {
            return new ProbeResult(STATUS_DOWN, "timeout (" + TIMEOUT.toSeconds() + "s)",
                    System.currentTimeMillis() - start);
        } catch (java.net.ConnectException ex) {
            return new ProbeResult(STATUS_DOWN, "connect refused: " + ex.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (java.net.UnknownHostException ex) {
            return new ProbeResult(STATUS_DOWN, "DNS 해석 실패: " + ex.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return new ProbeResult(STATUS_DOWN,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    public record ProbeResult(String status, String error, long elapsedMs) {}
}
