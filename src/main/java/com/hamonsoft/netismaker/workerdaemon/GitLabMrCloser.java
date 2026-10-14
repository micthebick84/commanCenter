package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;

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
import java.util.regex.Pattern;

/**
 * GitLab REST로 MR 병합·브랜치 삭제 (GitLabMrCreator와 같은 토큰·헤더 관례).
 *   GET    …/merge_requests/:iid            상태·draft·sha·detailed_merge_status
 *   PUT    …/merge_requests/:iid            title에서 "Draft:" 접두어 제거(=draft 해제), 실패 시 원복
 *   PUT    …/merge_requests/:iid/merge      sha 고정, squash 선택(rebase는 merge와 동일 취급)
 *   DELETE …/repository/branches/:branch    404 "Branch Not Found"만 이미 없음
 */
@Slf4j
public class GitLabMrCloser implements MergeRequestCloser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DRAFT_PREFIX = Pattern.compile("^\\s*(\\[draft]|\\(draft\\)|draft:|wip:)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final String UNDO_FAILED = " (draft 원복 실패 — 수동으로 draft 전환 필요)";

    /** 비동기 병합 완료 대기용 sleep — 테스트 seam. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final int MERGE_POLL_ATTEMPTS = 5;
    private static final long MERGE_POLL_MILLIS = 2000;

    private final String token;
    private final Sleeper sleeper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitLabMrCloser(String token) {
        this(token, Thread::sleep);
    }

    GitLabMrCloser(String token, Sleeper sleeper) {
        this.token = token;
        this.sleeper = sleeper;
    }

    @Override
    public boolean supports(RepoRef ref) {
        return ref.isGitlab();
    }

    @Override
    public String merge(RepoRef ref, int iid, String headSha, String method)
            throws CloseRefusedException, IOException, InterruptedException {
        if (!("merge".equals(method) || "squash".equals(method) || "rebase".equals(method))) {
            throw new IllegalArgumentException("method must be one of: merge, squash, rebase");
        }
        requireToken();
        String base = ref.apiBase() + "/api/v4/projects/" + enc(ref.path()) + "/merge_requests/" + iid;
        JsonNode mr = getJson(base);
        String state = mr.path("state").asText("");
        if ("merged".equals(state)) {
            log.info("MR !{} 이미 병합됨 — 성공 처리", iid);
            return commitOf(mr);
        }
        if (!"opened".equals(state)) {
            throw new CloseRefusedException("MR !" + iid + "이 병합 없이 닫혀 있습니다(" + state + ") — 다시 열거나 직접 처리한 뒤 재시도하세요");
        }
        String sha = mr.path("sha").asText("");
        if (!sha.equalsIgnoreCase(headSha)) {
            throw new CloseRefusedException("head 불일치: 확인한 커밋 " + shortSha(headSha) + " 이후 브랜치가 "
                    + shortSha(sha) + "(으)로 바뀌었습니다 — 확인되지 않은 커밋이라 병합하지 않습니다");
        }
        if (mr.path("has_conflicts").asBoolean(false)) {
            throw new CloseRefusedException("충돌: target 브랜치와 충돌합니다 — 충돌을 해결한 뒤 재시도하세요");
        }
        String title = mr.path("title").asText("");
        boolean wasDraft = mr.path("draft").asBoolean(false) || mr.path("work_in_progress").asBoolean(false);
        if (wasDraft) {
            HttpResponse<String> u;
            try {
                u = put(base, Map.of("title", DRAFT_PREFIX.matcher(title).replaceFirst("")));
            } catch (IOException | InterruptedException e) {
                // 요청이 반영됐을 수 있다 — 원복은 멱등이라 항상 시도한 뒤 전파한다.
                restoreTitle(base, title);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw e;
            }
            if (u.statusCode() != 200) throw new IOException("draft 해제 실패(" + u.statusCode() + "): " + snippet(u.body()));
            if (JSON.readTree(u.body()).path("draft").asBoolean(false)) {
                throw new CloseRefusedException("draft 해제 실패 — 제목의 Draft 접두어를 확인하세요");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sha", headSha);
        payload.put("should_remove_source_branch", false);
        if ("squash".equals(method)) payload.put("squash", true);

        // 여기부터 draft를 해제한 상태 — 성공 외의 모든 경로에서 제목을 되돌린 뒤 전파한다.
        HttpResponse<String> res;
        try {
            res = put(base + "/merge", payload);
        } catch (IOException | InterruptedException e) {
            if (wasDraft) restoreTitle(base, title);
            throw e;
        }
        if (res.statusCode() == 200) {
            JsonNode body = JSON.readTree(res.body());
            if ("merged".equals(body.path("state").asText(""))) return commitOf(body);
            return awaitMerged(base, iid); // 병합이 수락됐지만 아직 진행 중 — 제목은 되돌리지 않는다
        }

        // 거부 사유는 draft가 풀린 상태에서 조회해야 draft_status로 덮이지 않는다(원복보다 먼저).
        String reason;
        InterruptedException interrupted = null;
        if (res.statusCode() == 409) {
            reason = "head 불일치: 병합 요청 직전 브랜치가 바뀌었습니다 (HTTP 409)";
        } else {
            String detailed = "";
            try {
                detailed = getJson(base).path("detailed_merge_status").asText("");
            } catch (InterruptedException e) {
                interrupted = e;
            } catch (Exception e) {
                log.warn("병합 거부 사유 조회 실패: {}", GitRemotes.mask(String.valueOf(e.getMessage())));
            }
            reason = classify(detailed) + " (HTTP " + res.statusCode() + ")";
        }
        String undoMsg = wasDraft ? restoreTitle(base, title) : "";
        if (interrupted != null) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        throw new CloseRefusedException(reason + undoMsg);
    }

    /** 병합 PUT이 200이어도 GitLab은 비동기로 마저 병합할 수 있다 — merged가 될 때까지 짧게 폴링. */
    private String awaitMerged(String base, int iid) throws CloseRefusedException, IOException, InterruptedException {
        for (int i = 0; i < MERGE_POLL_ATTEMPTS; i++) {
            sleeper.sleep(MERGE_POLL_MILLIS);
            JsonNode mr = getJson(base);
            if ("merged".equals(mr.path("state").asText(""))) return commitOf(mr);
        }
        log.warn("MR !{} 병합 수락 후 {}회 확인에도 merged가 아님", iid, MERGE_POLL_ATTEMPTS);
        throw new CloseRefusedException("병합 진행 중 — 잠시 뒤 재시도하세요");
    }

    @Override
    public void deleteBranch(RepoRef ref, String branch) throws IOException, InterruptedException {
        requireToken();
        String url = ref.apiBase() + "/api/v4/projects/" + enc(ref.path()) + "/repository/branches/" + enc(branch);
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).DELETE());
        int s = res.statusCode();
        if (s == 204 || s == 200) return;
        String body = res.body() == null ? "" : res.body();
        // 404는 프로젝트·경로 오류에서도 나므로 브랜치가 없다는 메시지일 때만 이미 삭제된 것으로 본다.
        if (s == 404 && body.contains("Branch Not Found")) {
            log.info("브랜치 이미 없음: {}", branch);
            return;
        }
        if (s == 401 || s == 403) throw new IOException("GitLab 인증 실패(" + s + ") — 토큰 scope(api) 확인");
        throw new IOException("브랜치 삭제 실패(" + s + "): " + snippet(body));
    }

    static String classify(String detailed) {
        return switch (detailed) {
            case "ci_still_running" -> "CI 대기 중";
            case "ci_must_pass" -> "CI 실패 또는 미통과";
            case "conflict", "broken_status" -> "충돌";
            case "need_rebase" -> "리베이스 필요";
            case "not_approved" -> "승인 필요";
            case "discussions_not_resolved" -> "미해결 토론";
            default -> "병합 거부(" + (detailed.isBlank() ? "사유 미상" : detailed) + ")";
        };
    }

    /** 제목을 원래대로(draft 접두어 포함) 되돌린다. 실패하면 거부 메시지에 덧붙일 수동 조치 안내를, 성공이면 ""를 돌려준다. */
    private String restoreTitle(String base, String original) {
        try {
            HttpResponse<String> r = put(base, Map.of("title", original));
            if (r.statusCode() != 200) {
                log.warn("draft 제목 원복 실패({}): {}", r.statusCode(), snippet(r.body()));
                return UNDO_FAILED;
            }
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("draft 제목 원복 중단");
            return UNDO_FAILED;
        } catch (IOException e) {
            log.warn("draft 제목 원복 실패: {}", GitRemotes.mask(String.valueOf(e.getMessage())));
            return UNDO_FAILED;
        }
    }

    private static String commitOf(JsonNode mr) {
        for (String f : new String[]{"merge_commit_sha", "squash_commit_sha"}) {
            String v = mr.path(f).asText(null);
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).GET());
        if (res.statusCode() != 200) throw new IOException("GitLab MR 조회 실패(" + res.statusCode() + "): " + snippet(res.body()));
        return JSON.readTree(res.body());
    }

    private HttpResponse<String> put(String url, Map<String, ?> body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.header("PRIVATE-TOKEN", token).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void requireToken() throws IOException {
        if (token == null || token.isBlank()) throw new IOException("GITLAB_TOKEN 미설정 — GitLab MR을 다룰 수 없습니다");
    }

    private static String snippet(String s) {
        String m = GitRemotes.mask(s == null ? "" : s.strip());
        return m.length() <= 300 ? m : m.substring(0, 300) + "…";
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
