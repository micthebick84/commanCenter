package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach void clean() { sessionRepo.deleteAll(); }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    // catalog id=1 corresponds to the V14 seed entry (alias 'Netis7.0').
    private String body(Long repoCatalogId, String title, String desc) throws Exception {
        return json.writeValueAsString(new CreateInterviewRequest(repoCatalogId, "main", title, desc, List.of(), null, null));
    }

    @Test
    void POST_interviews_creates_QUEUED_session() throws Exception {
        mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "RBAC", "권한 추가")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void POST_interviews_null_repo_catalog_id_returns_400() throws Exception {
        mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(null, "제목", "내용")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_interview_other_user_returns_403() throws Exception {
        String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(loc).with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_interview_admin_can_see_anyone() throws Exception {
        String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(loc).with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("인터뷰대기"));
    }

    @Test
    void GET_stream_owner_returns_text_event_stream() throws Exception {
        String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(loc + "/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
    }

    @Test
    void GET_stream_non_owner_returns_403() throws Exception {
        String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(loc + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void POST_register_not_plan_ready_returns_409() throws Exception {
        String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(post(loc + "/register").with(userJwt("user1"))).andExpect(status().isConflict());
    }

    @Test
    void GET_active_returns_only_my_active_sessions_with_statusName() throws Exception {
        // user1: 2건 생성 후 1건 취소(terminal) → active 1건만
        mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "살릴 인터뷰", "내용")))
                .andReturn().getResponse().getHeader("Location");
        String loc2 = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body(1L, "취소할 인터뷰", "내용")))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(post(loc2 + "/cancel").with(userJwt("user1"))).andExpect(status().isOk());
        // user2의 세션은 user1 목록에 안 나와야 (생성 성공을 확인해 무음 실패 방지)
        mvc.perform(post("/api/interviews").with(userJwt("user2"))
                        .contentType(APPLICATION_JSON).content(body(1L, "남의 인터뷰", "내용")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/interviews/active").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("살릴 인터뷰"))
                .andExpect(jsonPath("$[0].statusName").value("QUEUED"))
                .andExpect(jsonPath("$[0].status").value("인터뷰대기"))
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void GET_active_unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/interviews/active")).andExpect(status().isUnauthorized());
    }
}
