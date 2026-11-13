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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GitLab REST로 MR 병합·브랜치 삭제 (GitLabMrCreator와 같은 토큰·헤더 관례).
 *   GET    …/merge_requests/:iid            상태·draft·sha·detailed_merge_status
 *   PUT    …/merge_requests/:iid            title에서 "Draft:" 접두어 제거(=draft 해제), 실패 시 원복
 *   PUT    …/merge_requests/:iid/merge      sha 고정, squash 선택(rebase는 merge와 동일 취급)
 *   DELETE …/repository/branches/:branch    404 "Branch Not Found"만 이미 없음
 *   GET    …/merge_requests?state=opened&target_branch=  삭제 전 그 브랜치를 target으로 쓰는 MR → PUT target_branch
 *   GET    …/repository/branches/:branch    충돌로 보이는 MR의 target이 지워졌는지
 *
 * 병합 가능 여부 계산 대기 (2026-11-13). 같은 target에 다른 MR이 막 병합되면 GitLab이 병합 가능 여부를 다시 계산하는
 * 동안 병합 PUT이 `병합 거부(checking) (HTTP 422)`로 실패한다 → 계산이 끝날 때까지 기다린다.
 * 사내 GitLab 버전: 16.5.2-ee (GET /api/v4/version, 2026-11-13).
 * 문서(docs.gitlab.com Merge requests API — 현재 문서 기준, 16.5 문서 자체는 보지 못함)로 확인한 것:
 *   - detailed_merge_status의 "아직 계산 중" 값 = checking, unchecked, preparing → COMPUTING.
 *   - MR 단건 GET은 병합 가능 여부 재계산을 비동기로 시작시키므로 폴링해야 하고, has_conflicts는 merge_status에
 *     종속(cannot_be_merged일 때만 true)이라 계산이 끝나기 전에는 믿지 않는다 → 계산 뒤에만 has_conflicts를 본다.
 *   - merge_status는 15.6부터 deprecated, 값은 unchecked·checking·can_be_merged·cannot_be_merged·cannot_be_merged_recheck.
 * 문서가 정하지 못해 통제자 판정으로 둘 다 구현한 것: Draft MR은 detailed_merge_status가 draft_status로 덮이는데
 *   (작업 42 MR !10 실측: draft_status, merge_status=cannot_be_merged, has_conflicts=true) 그때 merge_status가 계산 상태를
 *   보여 주는지는 문서로 확인하지 못했다. 그래서 ① Draft 해제 전에, detailed_merge_status가 draft_status(또는 빈 값)일 때만
 *   merge_status ∈ LEGACY_COMPUTING도 계산 중으로 보고 기다리고(detailed가 다른 값이면 COMPUTING만 본다 —
 *   merge_status만 늦게 따라오는 non-Draft MR 보호), ② Draft 해제 뒤 응답으로 같은 대기를 한 번 더 한다(해제하면 detailed_merge_status가 드러난다).
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

    /** 병합 가능 여부 계산 대기(병합 PUT 전) — 2000ms × 10회. */
    static final int MERGEABILITY_POLL_ATTEMPTS = 10;
    static final long MERGEABILITY_POLL_MILLIS = 2000;
    /** detailed_merge_status가 이 값이면 GitLab이 아직 병합 가능 여부를 계산 중이다(문서 확인). */
    static final Set<String> COMPUTING = Set.of("checking", "unchecked", "preparing");
    /** 예전 칸 merge_status의 계산 중 값 — detailed_merge_status가 draft_status로 덮였을 때(또는 비었을 때)만 본다(통제자 판정). */
    static final Set<String> LEGACY_COMPUTING = Set.of("unchecked", "checking", "cannot_be_merged_recheck");
    private static final String COMPUTING_REFUSED = "병합 가능 여부 확인 중 — 잠시 뒤 재시도하세요";

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
        requireOpenAndPinned(mr, iid, headSha);
        // 계산이 끝나기 전의 has_conflicts는 믿을 수 없다 — 먼저 기다린 뒤에 본다(Draft여도 아직 제목은 건드리지 않았다).
        mr = awaitMergeability(base, mr);
        // 기다리는 사이 밖에서 병합되거나 push됐을 수 있다 — 같은 판정을 다시 적용한다.
        if ("merged".equals(mr.path("state").asText(""))) {
            log.info("MR !{} 대기 중 병합됨 — 성공 처리", iid);
            return commitOf(mr);
        }
        requireOpenAndPinned(mr, iid, headSha);
        CloseRefusedException conflict = conflictOf(ref, mr);
        if (conflict != null) throw conflict;
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
            JsonNode undrafted = JSON.readTree(u.body());
            if (undrafted.path("draft").asBoolean(false)) {
                throw new CloseRefusedException("draft 해제 실패 — 제목의 Draft 접두어를 확인하세요");
            }
            // 둘째 길: 해제하면 detailed_merge_status가 draft_status에서 풀려 계산 중이 보일 수 있다 — 다시 기다린다.
            // 여기서 거부하면 이미 해제한 제목을 원복해야 한다.
            CloseRefusedException refused;
            JsonNode settled;
            try {
                settled = awaitMergeability(base, undrafted);
                if (!"merged".equals(settled.path("state").asText(""))) requireOpenAndPinned(settled, iid, headSha);
                refused = conflictOf(ref, settled);
            } catch (CloseRefusedException | IOException | InterruptedException e) {
                String undo = restoreTitle(base, title);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (e instanceof CloseRefusedException) throw new CloseRefusedException(e.getMessage() + undo);
                throw e;
            }
            if ("merged".equals(settled.path("state").asText(""))) {
                log.info("MR !{} 해제 뒤 대기 중 병합됨 — 성공 처리", iid);
                return commitOf(settled);
            }
            if (refused != null) throw new CloseRefusedException(refused.getMessage() + restoreTitle(base, title));
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

    /** 계산 중(detailed_merge_status ∈ COMPUTING 또는 merge_status ∈ LEGACY_COMPUTING)이면 2초씩 최대 10회 기다리며 다시 조회한다. */
    private JsonNode awaitMergeability(String base, JsonNode mr)
            throws CloseRefusedException, IOException, InterruptedException {
        for (int i = 0; i < MERGEABILITY_POLL_ATTEMPTS && isComputing(mr); i++) {
            sleeper.sleep(MERGEABILITY_POLL_MILLIS);
            mr = getJson(base);
        }
        if (isComputing(mr)) throw new CloseRefusedException(COMPUTING_REFUSED);
        return mr;
    }

    /** 병합 없이 닫혔거나 확인한 커밋과 sha가 다르면 거부 — 처음 조회와 대기 뒤 재조회에 같은 판정을 쓴다. */
    private static void requireOpenAndPinned(JsonNode mr, int iid, String headSha) throws CloseRefusedException {
        String state = mr.path("state").asText("");
        if (!"opened".equals(state)) {
            throw new CloseRefusedException("MR !" + iid + "이 병합 없이 닫혀 있습니다(" + state + ") — 다시 열거나 직접 처리한 뒤 재시도하세요");
        }
        String sha = mr.path("sha").asText("");
        if (!sha.equalsIgnoreCase(headSha)) {
            throw new CloseRefusedException("head 불일치: 확인한 커밋 " + shortSha(headSha) + " 이후 브랜치가 "
                    + shortSha(sha) + "(으)로 바뀌었습니다" + HEAD_MISMATCH_HINT);
        }
    }

    /**
     * detailed_merge_status가 COMPUTING이면 계산 중. 예전 칸 merge_status는 detailed_merge_status가 Draft로 덮였을 때
     * (draft_status 또는 비어 있음)만 보조 신호로 쓴다 — 값이 이미 정해졌는데 merge_status만 늦게 따라오는 non-Draft MR을
     * 20초 기다리다 거부하지 않도록.
     */
    private static boolean isComputing(JsonNode mr) {
        String detailed = mr.path("detailed_merge_status").asText("");
        if (COMPUTING.contains(detailed)) return true;
        boolean hiddenByDraft = detailed.isBlank() || "draft_status".equals(detailed);
        return hiddenByDraft && LEGACY_COMPUTING.contains(mr.path("merge_status").asText(""));
    }

    /** 계산이 끝난 MR이 충돌이면 거부 예외, 아니면 null. */
    private CloseRefusedException conflictOf(RepoRef ref, JsonNode mr) {
        if (!mr.path("has_conflicts").asBoolean(false)) return null;
        // target 브랜치가 지워진 MR도 GitLab은 충돌로 표시한다 — 그때는 고칠 곳이 다르니 따로 알린다.
        String target = mr.path("target_branch").asText("");
        if (!target.isBlank() && branchMissing(ref, target)) {
            return new CloseRefusedException("target 브랜치 없음: " + target + "이(가) 삭제됐습니다 — MR의 target 브랜치를 바꾼 뒤 재시도하세요");
        }
        return new CloseRefusedException(CONFLICT_REFUSED);
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

    @Override
    public List<String> retargetDependents(RepoRef ref, String branch, String newTarget)
            throws IOException, InterruptedException {
        requireToken();
        String project = ref.apiBase() + "/api/v4/projects/" + enc(ref.path());
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(project
                + "/merge_requests?state=opened&per_page=100&target_branch=" + enc(branch))).GET());
        if (res.statusCode() != 200) {
            throw new IOException("target이 " + branch + "인 MR 조회 실패(" + res.statusCode() + "): " + snippet(res.body()));
        }
        List<String> moved = new ArrayList<>();
        for (JsonNode m : JSON.readTree(res.body())) {
            int iid = m.path("iid").asInt();
            HttpResponse<String> u = put(project + "/merge_requests/" + iid, Map.of("target_branch", newTarget));
            if (u.statusCode() != 200) {
                throw new IOException("MR !" + iid + " target 변경 실패(" + u.statusCode() + "): " + snippet(u.body()));
            }
            log.info("MR !{} target 변경: {} → {}", iid, branch, newTarget);
            moved.add("!" + iid);
        }
        return moved;
    }

    /** 브랜치가 없다고 확인될 때만 true — 조회 실패는 모름(false)으로 본다. */
    private boolean branchMissing(RepoRef ref, String branch) {
        try {
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(ref.apiBase() + "/api/v4/projects/"
                    + enc(ref.path()) + "/repository/branches/" + enc(branch))).GET());
            return r.statusCode() == 404 && r.body() != null && r.body().contains("Branch Not Found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            log.warn("target 브랜치 확인 실패: {}", GitRemotes.mask(String.valueOf(e.getMessage())));
            return false;
        }
    }

    static String classify(String detailed) {
        if (COMPUTING.contains(detailed)) return "병합 가능 여부 확인 중";
        return switch (detailed) {
            case "ci_still_running" -> "CI 대기 중";
            case "ci_must_pass" -> "CI 실패 또는 미통과";
            case "conflict", "broken_status" -> "충돌 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요";
            case "need_rebase" -> "리베이스 필요 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요";
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
