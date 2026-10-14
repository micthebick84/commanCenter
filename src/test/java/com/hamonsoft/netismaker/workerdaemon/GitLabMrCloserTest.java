package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabMrCloserTest {

    private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";
    private static final String MR = "/api/v4/projects/g%2Fp/merge_requests/9";
    private static final String BRANCH = "/api/v4/projects/g%2Fp/repository/branches/netismaker%2Ftask-9";

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();                // "METHOD path | body"
    private final Map<String, Deque<String[]>> routes = new HashMap<>(); // "METHOD path" → [status, body]…

    /** status -1 이면 응답 없이 연결을 끊는다(IOException 유발). */
    private void route(String key, int status, String body) {
        routes.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new String[]{String.valueOf(status), body});
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath();
            seen.add(key + " | " + body);
            Deque<String[]> q = routes.get(key);
            String[] r = (q == null || q.isEmpty()) ? new String[]{"404", "{}"} : (q.size() > 1 ? q.poll() : q.peek());
            if ("-1".equals(r[0])) {
                ex.close();
                return;
            }
            byte[] bytes = r[1].getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(Integer.parseInt(r[0]), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RepoRef ref() {
        return RepoRef.fromSnapshot("g/p", "http://127.0.0.1:" + server.getAddress().getPort() + "/g/p.git");
    }

    private static String mr(String state, boolean draft, String sha, String detailed, String mergeSha) {
        return "{\"state\":\"" + state + "\",\"draft\":" + draft + ",\"title\":\"" + (draft ? "Draft: 제목" : "제목")
                + "\",\"sha\":\"" + sha + "\",\"has_conflicts\":false,\"detailed_merge_status\":\"" + detailed
                + "\",\"merge_commit_sha\":" + (mergeSha == null ? "null" : "\"" + mergeSha + "\"") + "}";
    }

    private boolean titleRestoreSeen() {
        return seen.stream().anyMatch(s -> s.startsWith("PUT " + MR + " | ") && s.contains("\"title\":\"Draft: 제목\""));
    }

    @Test
    void draft를_해제하고_sha에_고정해_병합한다() throws Exception {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", 200, mr("merged", false, SHA, "not_open", "m3rg3"));

        assertThat(new GitLabMrCloser("glpat-T").merge(ref(), 9, SHA, "squash")).isEqualTo("m3rg3");
        assertThat(seen.get(1)).startsWith("PUT " + MR + " | ").contains("\"title\":\"제목\"");
        assertThat(seen.get(2)).contains("\"sha\":\"" + SHA + "\"").contains("\"squash\":true")
                .contains("\"should_remove_source_branch\":false");
    }

    @Test
    void 이미_병합된_MR은_그_커밋() throws Exception {
        route("GET " + MR, 200, mr("merged", false, SHA, "not_open", "done1"));
        assertThat(new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge")).isEqualTo("done1");
        assertThat(seen).hasSize(1);
    }

    @Test
    void sha가_다르면_거부() {
        route("GET " + MR, 200, mr("opened", false, "ffff", "mergeable", null));
        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("head 불일치");
        assertRefusedBeforeAnyChange();
    }

    @Test
    void 병합_없이_닫힌_MR은_아무것도_바꾸지_않고_거부() {
        route("GET " + MR, 200, mr("closed", false, SHA, "not_open", null));
        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("닫혀");
        assertRefusedBeforeAnyChange();
    }

    @Test
    void 충돌_MR은_아무것도_바꾸지_않고_거부() {
        route("GET " + MR, 200, mr("opened", true, SHA, "conflict", null)
                .replace("\"has_conflicts\":false", "\"has_conflicts\":true"));
        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("충돌");
        assertRefusedBeforeAnyChange();
    }

    private void assertRefusedBeforeAnyChange() {
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("GET " + MR);
        assertThat(seen).noneMatch(s -> s.startsWith("PUT "));
    }

    @Test
    void 알_수_없는_병합_방식은_HTTP_호출_전에_거부() {
        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "fast-forward"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void rebase는_merge와_같이_squash_없이_병합() throws Exception {
        route("GET " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", 200, mr("merged", false, SHA, "not_open", "rb1"));

        assertThat(new GitLabMrCloser("t").merge(ref(), 9, SHA, "rebase")).isEqualTo("rb1");
        assertThat(seen.get(1)).doesNotContain("squash");
    }

    @Test
    void CI_대기로_거부되면_draft_제목을_되돌린다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("GET " + MR, 200, mr("opened", false, SHA, "ci_still_running", null));
        route("PUT " + MR, 200, mr("opened", false, SHA, "ci_still_running", null));
        route("PUT " + MR + "/merge", 405, "{\"message\":\"405 Method Not Allowed\"}");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("CI 대기 중")
                .hasMessageNotContaining("원복 실패");
        assertThat(titleRestoreSeen()).isTrue();
    }

    @Test
    void 병합_거부_뒤_제목_원복도_실패하면_메시지에_수동_조치를_덧붙인다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("GET " + MR, 200, mr("opened", false, SHA, "ci_still_running", null));
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));   // draft 해제
        route("PUT " + MR, 500, "{\"message\":\"boom\"}");                       // 제목 원복 실패
        route("PUT " + MR + "/merge", 405, "{\"message\":\"405 Method Not Allowed\"}");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class)
                .hasMessageContaining("CI 대기 중")
                .hasMessageContaining("draft 원복 실패 — 수동으로 draft 전환 필요");
        assertThat(titleRestoreSeen()).isTrue();
    }

    @Test
    void 병합_호출이_IOException이면_제목을_되돌리고_예외를_전파한다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", -1, "");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(IOException.class);
        assertThat(titleRestoreSeen()).isTrue();
    }

    @Test
    void draft_해제_PUT이_IOException이면_제목_원복을_시도하고_전파한다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("PUT " + MR, -1, "");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(IOException.class);
        assertThat(titleRestoreSeen()).isTrue();
        assertThat(seen).noneMatch(s -> s.startsWith("PUT " + MR + "/merge"));
    }

    @Test
    void draft_해제_응답에서도_draft면_원복_없이_거부() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("PUT " + MR, 200, mr("opened", true, SHA, "draft_status", null));

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("draft 해제 실패");
        assertThat(seen).hasSize(2);
    }

    @Test
    void 병합_PUT_409는_head_불일치로_거부하고_제목을_되돌린다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", 409, "{\"message\":\"SHA does not match HEAD of source branch\"}");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("head 불일치");
        assertThat(titleRestoreSeen()).isTrue();
    }

    @Test
    void 병합_PUT이_200이어도_아직_진행_중이면_폴링해_병합_커밋을_돌려준다() throws Exception {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("GET " + MR, 200, mr("opened", false, SHA, "mergeable", null));   // 폴링 1회째: 아직
        route("GET " + MR, 200, mr("merged", false, SHA, "not_open", "late1")); // 폴링 2회째: 완료
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", 200, mr("opened", false, SHA, "mergeable", null));
        List<Long> sleeps = new ArrayList<>();

        assertThat(new GitLabMrCloser("t", sleeps::add).merge(ref(), 9, SHA, "merge")).isEqualTo("late1");
        assertThat(sleeps).containsExactly(2000L, 2000L);
        assertThat(titleRestoreSeen()).isFalse();
    }

    @Test
    void 병합_PUT이_200인데_끝내_merged가_아니면_제목은_두고_재시도를_안내한다() {
        route("GET " + MR, 200, mr("opened", true, SHA, "draft_status", null));
        route("GET " + MR, 200, mr("opened", false, SHA, "mergeable", null));   // 이후 계속 opened
        route("PUT " + MR, 200, mr("opened", false, SHA, "mergeable", null));
        route("PUT " + MR + "/merge", 200, mr("opened", false, SHA, "mergeable", null));
        List<Long> sleeps = new ArrayList<>();

        assertThatThrownBy(() -> new GitLabMrCloser("t", sleeps::add).merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("병합 진행 중");
        assertThat(sleeps).hasSize(5);
        assertThat(titleRestoreSeen()).isFalse();
    }

    @Test
    void draft가_아니면_거부돼도_제목을_건드리지_않는다() {
        route("GET " + MR, 200, mr("opened", false, SHA, "ci_must_pass", null));
        route("PUT " + MR + "/merge", 405, "{}");

        assertThatThrownBy(() -> new GitLabMrCloser("t").merge(ref(), 9, SHA, "merge"))
                .isInstanceOf(MergeRequestCloser.CloseRefusedException.class).hasMessageContaining("CI 실패");
        assertThat(seen).noneMatch(s -> s.startsWith("PUT " + MR + " | "));
    }

    @Test
    void 브랜치_삭제_204는_성공() throws Exception {
        route("DELETE " + BRANCH, 204, "");
        new GitLabMrCloser("t").deleteBranch(ref(), "netismaker/task-9");
        assertThat(seen.get(0)).startsWith("DELETE " + BRANCH);
    }

    @Test
    void 브랜치_삭제_404_Branch_Not_Found는_이미_없음으로_성공() throws Exception {
        route("DELETE " + BRANCH, 404, "{\"message\":\"404 Branch Not Found\"}");
        new GitLabMrCloser("t").deleteBranch(ref(), "netismaker/task-9");
        assertThat(seen).hasSize(1);
    }

    @Test
    void 브랜치_삭제_404라도_Project_Not_Found면_실패() {
        route("DELETE " + BRANCH, 404, "{\"message\":\"404 Project Not Found\"}");
        assertThatThrownBy(() -> new GitLabMrCloser("t").deleteBranch(ref(), "netismaker/task-9"))
                .isInstanceOf(IOException.class).hasMessageContaining("404");
    }

    @Test
    void 브랜치_삭제_403은_인증_실패() {
        route("DELETE " + BRANCH, 403, "{\"message\":\"403 Forbidden\"}");
        assertThatThrownBy(() -> new GitLabMrCloser("t").deleteBranch(ref(), "netismaker/task-9"))
                .isInstanceOf(IOException.class).hasMessageContaining("인증 실패");
    }
}
