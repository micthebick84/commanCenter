package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabMrCreatorTest {

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();   // "METHOD rawPath?query | token | body"
    private int postStatus = 201;
    private String postBody = "{\"iid\":12,\"web_url\":\"http://gl/g/sub/p/-/merge_requests/12\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                    + " | " + ex.getRequestHeaders().getFirst("PRIVATE-TOKEN") + " | " + body);
            int status;
            String resp;
            if (ex.getRequestMethod().equals("POST")) {
                status = postStatus;
                resp = postBody;
            } else {
                status = 200;
                resp = "[{\"iid\":7,\"web_url\":\"http://gl/g/sub/p/-/merge_requests/7\"}]";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
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

    private RepoRef ref() {
        return RepoRef.fromSnapshot("g/sub/p", "http://127.0.0.1:" + server.getAddress().getPort() + "/g/sub/p.git");
    }

    @Test
    void creates_draft_mr_with_url_encoded_project_path_and_token_header() throws Exception {
        GitOpsService.PrInfo pr = new GitLabMrCreator("glpat-T").create(null, ref(),
                "develop", "netismaker/task-1-x", "제목", "본문");

        assertThat(pr.number()).isEqualTo(12);
        assertThat(pr.url()).isEqualTo("http://gl/g/sub/p/-/merge_requests/12");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("POST /api/v4/projects/g%2Fsub%2Fp/merge_requests | glpat-T | ");
        assertThat(seen.get(0)).contains("\"source_branch\":\"netismaker/task-1-x\"")
                .contains("\"target_branch\":\"develop\"")
                .contains("\"title\":\"Draft: 제목\"")
                .contains("\"description\":\"본문\"");
    }

    @Test
    void conflict_returns_the_existing_open_mr_for_the_source_branch() throws Exception {
        postStatus = 409;
        postBody = "{\"message\":[\"Another open merge request already exists for this source branch: !7\"]}";

        GitOpsService.PrInfo pr = new GitLabMrCreator("glpat-T").create(null, ref(),
                "develop", "netismaker/task-1-x", "t", "b");

        assertThat(pr.number()).isEqualTo(7);
        assertThat(seen.get(1)).startsWith(
                "GET /api/v4/projects/g%2Fsub%2Fp/merge_requests?state=opened&source_branch=netismaker%2Ftask-1-x");
    }

    @Test
    void auth_failure_is_reported_without_leaking_the_token() {
        postStatus = 401;
        postBody = "{\"message\":\"401 Unauthorized\"}";
        assertThatThrownBy(() -> new GitLabMrCreator("glpat-T").create(null, ref(), "develop", "h", "t", "b"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("GitLab 인증 실패")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("glpat-T"));
    }

    @Test
    void missing_token_fails_before_any_request() {
        assertThatThrownBy(() -> new GitLabMrCreator(" ").create(null, ref(), "develop", "h", "t", "b"))
                .isInstanceOf(IOException.class).hasMessageContaining("GITLAB_TOKEN");
        assertThat(seen).isEmpty();
    }

    @Test
    void supports_only_gitlab_refs() {
        assertThat(new GitLabMrCreator("x").supports(ref())).isTrue();
        assertThat(new GitLabMrCreator("x").supports(RepoRef.fromSnapshot("a/b", null))).isFalse();
    }
}
