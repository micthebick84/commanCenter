package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 사내 GitLab 프로젝트 조회 클라이언트(요청 게시판 REPO 요청 처리용).
 *   GET /api/v4/projects?search=…            프로젝트 검색
 *   GET /api/v4/projects/:fullPath|:id       단건 조회(404 → empty)
 * 토큰 원문은 로그·예외 메시지에 남기지 않는다(응답 본문도 로그에 남기지 않는다).
 */
@Slf4j
@Component
@Profile("api")
public class GitLabProjectClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String NOT_CONFIGURED = "사내 GitLab이 설정되지 않았습니다";
    private static final String LOOKUP_FAILED = "GitLab 조회에 실패했습니다";

    /** GitLab 프로젝트 요약(검색·등록 후보). */
    public record GitLabProject(long id, String name, String pathWithNamespace,
                                String defaultBranch, String description) {}

    private final String baseUrl;
    private final String token;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public GitLabProjectClient(@Value("${app.gitlab.base-url:}") String baseUrl,
                               @Value("${app.gitlab.token:}") String token,
                               ObjectMapper om) {
        String b = baseUrl == null ? "" : baseUrl.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.token = token == null ? "" : token.strip();
        this.om = om;
    }

    public boolean configured() {
        return !baseUrl.isEmpty() && !token.isEmpty();
    }

    public List<GitLabProject> search(String q, int limit) {
        int perPage = Math.max(1, Math.min(limit, 100));
        String path = "/api/v4/projects";
        String query = "?search=" + enc(q == null ? "" : q)
                + "&simple=true&archived=false&order_by=last_activity_at&per_page=" + perPage;
        JsonNode root = get(path, query).orElseThrow(this::failed);
        if (!root.isArray()) {
            log.warn("GitLab 응답 형식 오류 {}", path);
            throw failed();
        }
        List<GitLabProject> out = new ArrayList<>();
        for (JsonNode n : root) out.add(toProject(n));
        return out;
    }

    public Optional<GitLabProject> findByPath(String fullPath) {
        return get("/api/v4/projects/" + enc(fullPath), "").map(GitLabProjectClient::toProject);
    }

    public Optional<GitLabProject> findById(long id) {
        return get("/api/v4/projects/" + id, "").map(GitLabProjectClient::toProject);
    }

    /** s 안의 토큰 원문을 ***로 치환한다. */
    String redact(String s) {
        if (s == null || token.isEmpty()) return s;
        return s.replace(token, "***");
    }

    /** 200이면 본문, 404면 empty, 그 밖은 502. 미설정이면 HTTP 없이 503. */
    private Optional<JsonNode> get(String path, String query) {
        if (!configured()) throw new TaskException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path + query))
                    .header("PRIVATE-TOKEN", token)
                    .timeout(TIMEOUT)
                    .GET().build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("GitLab 조회 중단 {}", redact(path));
            throw failed();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("GitLab 조회 I/O 오류 {} ({})", redact(path), e.getClass().getSimpleName());
            throw failed();
        }
        int s = res.statusCode();
        if (s == 404) return Optional.empty();
        if (s != 200) {
            log.warn("GitLab 조회 실패 status={} {}", s, redact(path));
            throw failed();
        }
        try {
            return Optional.of(om.readTree(res.body()));
        } catch (JsonProcessingException e) {
            log.warn("GitLab 응답 파싱 실패 {}", redact(path));
            throw failed();
        }
    }

    private TaskException failed() {
        return new TaskException(HttpStatus.BAD_GATEWAY, LOOKUP_FAILED);
    }

    private static GitLabProject toProject(JsonNode n) {
        return new GitLabProject(n.path("id").asLong(), text(n, "name"), text(n, "path_with_namespace"),
                text(n, "default_branch"), text(n, "description"));
    }

    /** 없거나 null이면 null. */
    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
