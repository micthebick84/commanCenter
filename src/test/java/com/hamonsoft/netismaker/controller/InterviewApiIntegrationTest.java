package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskService taskService;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** 세션은 이제 승인에서만 태어난다 — 등록 후 승인해서 세션 id를 얻는다. */
    // catalog id=1 corresponds to the V14 seed entry (alias 'Netis7.0').
    private long startSession(String requester, String title) throws Exception {
        var t = taskService.create(
                new TaskCreateRequest(1L, "main", title, "설명"), requester);
        taskService.approve(t.getId(), "admin", null);
        return sessionRepo.findAll().stream()
                .filter(s -> t.getId().equals(s.getTaskId()))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void GET_interview_other_user_returns_403() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(get("/api/interviews/" + sid).with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_interview_admin_can_see_anyone() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(get("/api/interviews/" + sid).with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("인터뷰대기"));
    }

    @Test
    void GET_stream_owner_returns_text_event_stream() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(get("/api/interviews/" + sid + "/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
    }

    @Test
    void GET_stream_non_owner_returns_403() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(get("/api/interviews/" + sid + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void POST_register_not_plan_ready_returns_409() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))).andExpect(status().isConflict());
    }

    @Test
    void 등록시_designRequested를_넘기면_task에_반영된다() throws Exception {
        long sid = startSession("user1", "제목");

        // PLAN_READY 도달: worker claim → plan (InterviewWorkerApiIntegrationTest와 동일 패턴).
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1000}"))
                .andExpect(status().isNoContent());

        String resp = mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"designRequested\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long taskId = json.readTree(resp).get("taskId").asLong();

        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.isDesignRequested()).isTrue();
    }

    @Test
    void requester_cannot_answer_admin_only_interview() throws Exception {
        long sid = startSession("user1", "권한 확인");
        mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"네\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requester_can_still_read_the_interview() throws Exception {
        long sid = startSession("user1", "권한 확인");
        mvc.perform(get("/api/interviews/" + sid).with(userJwt("user1")))
                .andExpect(status().isOk());
    }
}
