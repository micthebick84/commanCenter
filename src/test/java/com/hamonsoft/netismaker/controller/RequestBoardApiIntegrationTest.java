package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 요청 게시판 API 표면·권한 경계 (스펙 2026-10-19). 시드 사용자 user1, user2, admin1 (init-test-schema.sql).
 * 컨텍스트가 뜨면서 V30 마이그레이션과 엔티티 {@code ddl-auto: validate}도 함께 검증된다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class RequestBoardApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private RequestPostRepository posts;

    /** 댓글은 FK ON DELETE CASCADE로 함께 지워진다. */
    @BeforeEach void clean() { posts.deleteAll(); }

    private static RequestPostProcessor user(String username) {
        return jwt().jwt(b -> b.claim("username", username).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin1").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static String createBody(String kind, String title) {
        return "{\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"content\":\"본문 " + title + "\""
                + ("REPO".equals(kind) ? ",\"repoHint\":\"사내 GitLab group/" + title + "\"" : "") + "}";
    }

    private long create(RequestPostProcessor who, String kind, String title) throws Exception {
        String json = mvc.perform(post("/api/requests").with(who).contentType(APPLICATION_JSON)
                        .content(createBody(kind, title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void create_returns_201_with_labels() throws Exception {
        mvc.perform(post("/api/requests").with(user("user1")).contentType(APPLICATION_JSON)
                        .content(createBody("FEATURE", "다크 모드")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("FEATURE"))
                .andExpect(jsonPath("$.kindLabel").value("기능 추가"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.statusLabel").value("접수"))
                .andExpect(jsonPath("$.requesterId").value("user1"))
                .andExpect(jsonPath("$.title").value("다크 모드"));
    }

    @Test
    void user_cannot_see_others_request() throws Exception {
        long id = create(user("user1"), "FEATURE", "user1의 요청");

        mvc.perform(get("/api/requests/" + id).with(user("user2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("요청을 찾을 수 없습니다"));
        mvc.perform(put("/api/requests/" + id).with(user("user2")).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"가로채기\",\"content\":\"바꿔치기\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/requests/" + id + "/comments").with(user("user2")).contentType(APPLICATION_JSON)
                        .content("{\"body\":\"남의 요청에 답글\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/requests/" + id).with(user("user2")))
                .andExpect(status().isNotFound());

        // 아무것도 바뀌지 않았다.
        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("user1의 요청"))
                .andExpect(jsonPath("$.comments", hasSize(0)));
    }

    @Test
    void non_admin_forbidden_on_admin_paths() throws Exception {
        long id = create(user("user1"), "FEATURE", "관리자 경로 시험");

        mvc.perform(get("/api/admin/requests/summary").with(user("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/requests/" + id + "/status").with(user("user1"))
                        .contentType(APPLICATION_JSON).content("{\"status\":\"REVIEWING\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void user_all_param_is_ignored() throws Exception {
        create(user("user1"), "FEATURE", "user1의 요청");

        mvc.perform(get("/api/requests").param("all", "true").with(user("user2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/requests").with(user("user1")))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void admin_lists_all_with_filters() throws Exception {
        create(user("user1"), "FEATURE", "기능 요청");
        create(user("user1"), "REPO", "레포 요청");
        create(user("user2"), "MCP", "MCP 요청");

        // 필터 없음: 열린 요청 셋 모두
        mvc.perform(get("/api/requests").param("all", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].title", containsInAnyOrder("기능 요청", "레포 요청", "MCP 요청")))
                .andExpect(jsonPath("$[*].requesterId", containsInAnyOrder("user1", "user1", "user2")));

        // kind=REPO: 레포 요청 하나만
        mvc.perform(get("/api/requests").param("all", "true").param("kind", "REPO").with(admin()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("REPO"))
                .andExpect(jsonPath("$[0].title").value("레포 요청"))
                .andExpect(jsonPath("$[0].unread").value(true));

        // 관리자가 아직 못 봐서 includeUnread=true(기본)면 상태 필터에 안 걸려도 보이고, false면 빠진다.
        mvc.perform(get("/api/requests").param("all", "true").param("kind", "REPO")
                        .param("status", "DONE").with(admin()))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/requests").param("all", "true").param("kind", "REPO")
                        .param("status", "DONE").param("includeUnread", "false").with(admin()))
                .andExpect(jsonPath("$", hasSize(0)));
        // status는 반복 가능한 다중 값
        mvc.perform(get("/api/requests").param("all", "true").param("kind", "REPO")
                        .param("status", "RECEIVED", "REVIEWING").param("includeUnread", "false").with(admin()))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void flow_comment_unread_read() throws Exception {
        long id = create(user("user1"), "FEATURE", "답글 흐름");

        // 새로 만든 요청은 요청자에게 안 읽음이 아니다.
        mvc.perform(get("/api/requests").with(user("user1")))
                .andExpect(jsonPath("$[0].unread").value(false));

        mvc.perform(post("/api/requests/" + id + "/comments").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"body\":\"검토해 보겠습니다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("COMMENT"))
                .andExpect(jsonPath("$.authorId").value("admin1"))
                .andExpect(jsonPath("$.authorAdmin").value(true))
                .andExpect(jsonPath("$.body").value("검토해 보겠습니다"));

        mvc.perform(get("/api/requests").with(user("user1")))
                .andExpect(jsonPath("$[0].unread").value(true));

        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments", hasSize(1)))
                .andExpect(jsonPath("$.comments[0].authorAdmin").value(true));

        mvc.perform(get("/api/requests").with(user("user1")))
                .andExpect(jsonPath("$[0].unread").value(false));
    }

    @Test
    void summary_counts_attention_for_reply_on_done() throws Exception {
        long id = create(user("user1"), "FEATURE", "처리 뒤 답글");
        // 새 요청: 접수이므로 관리자 주의 필요
        mvc.perform(get("/api/admin/requests/summary").with(admin()))
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.attention").value(1));

        // 관리자가 처리완료로 바꾸면 관리자 활동이 읽음 처리라 주의 필요가 0이 된다.
        mvc.perform(post("/api/admin/requests/" + id + "/status").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.statusLabel").value("처리완료"));
        mvc.perform(get("/api/admin/requests/summary").with(admin()))
                .andExpect(jsonPath("$.received").value(0))
                .andExpect(jsonPath("$.attention").value(0));

        // 처리완료 뒤 요청자의 답글은 상태와 무관하게 관리자가 못 본 활동이다.
        mvc.perform(post("/api/requests/" + id + "/comments").with(user("user1"))
                        .contentType(APPLICATION_JSON).content("{\"body\":\"하나 더 부탁드립니다\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/requests/summary").with(admin()))
                .andExpect(jsonPath("$.received").value(0))
                .andExpect(jsonPath("$.attention").value(1));
    }

    @Test
    void owner_can_cancel_received_request() throws Exception {
        long id = create(user("user1"), "FEATURE", "취소할 요청");
        mvc.perform(delete("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void validation_400_on_blank_title() throws Exception {
        mvc.perform(post("/api/requests").with(user("user1")).contentType(APPLICATION_JSON)
                        .content("{\"kind\":\"FEATURE\",\"title\":\"   \",\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").exists());
        mvc.perform(get("/api/requests").with(user("user1")))
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
