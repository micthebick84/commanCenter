package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitLab REST API로 Draft MR 생성 (glab CLI 의존 없음).
 *   POST {apiBase}/api/v4/projects/{url-encoded path}/merge_requests
 * 토큰 scope: api. 같은 source 브랜치의 열린 MR이 이미 있으면(409) 그 MR을 돌려준다 —
 * 재시도 때 "MR은 있는데 작업은 실패"가 되지 않게.
 */
@Slf4j
public class GitLabMrCreator implements MergeRequestCreator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitLabMrCreator(String token) {
        this.token = token;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return ref.isGitlab();
    }

    @Override
    public GitOpsService.PrInfo create(File worktreeDir, RepoRef ref, String baseBranch, String headBranch,
                                       String title, String body) throws IOException, InterruptedException {
        if (token == null || token.isBlank()) {
            throw new IOException("GITLAB_TOKEN 미설정 — GitLab MR을 만들 수 없습니다");
        }
        String mrs = ref.apiBase() + "/api/v4/projects/" + enc(ref.path()) + "/merge_requests";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_branch", headBranch);
        payload.put("target_branch", baseBranch);
        payload.put("title", "Draft: " + title);
        payload.put("description", body);
        payload.put("remove_source_branch", false);

        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(mrs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8)));

        if (res.statusCode() == 201) {
            return toInfo(JSON.readTree(res.body()));
        }
        if (res.statusCode() == 409) {
            HttpResponse<String> list = send(HttpRequest.newBuilder(
                    URI.create(mrs + "?state=opened&source_branch=" + enc(headBranch))).GET());
            JsonNode arr = list.statusCode() == 200 ? JSON.readTree(list.body()) : null;
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                log.info("기존 열린 MR 재사용: {}", arr.get(0).path("web_url").asText());
                return toInfo(arr.get(0));
            }
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IOException("GitLab 인증 실패(" + res.statusCode() + ") — 토큰 scope(api) 확인");
        }
        String snippet = res.body() == null ? "" : res.body().substring(0, Math.min(500, res.body().length()));
        throw new IOException("GitLab MR 생성 실패(" + res.statusCode() + "): " + GitRemotes.mask(snippet));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.header("PRIVATE-TOKEN", token).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static GitOpsService.PrInfo toInfo(JsonNode mr) throws IOException {
        String url = mr.path("web_url").asText(null);
        int iid = mr.path("iid").asInt(0);
        if (url == null || iid <= 0) {
            throw new IOException("GitLab MR 응답에 web_url/iid가 없습니다");
        }
        log.info("MR 생성: !{} {}", iid, url);
        return new GitOpsService.PrInfo(url, iid);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
