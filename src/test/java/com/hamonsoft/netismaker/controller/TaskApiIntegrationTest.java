package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testcontainers 기반 통합 테스트.
 *
 * 환경 의존: Docker Desktop에서 Docker AI Agent가 docker socket을 가로채는 경우
 * /info API가 빈 응답 → Testcontainers가 daemon을 못 찾음. 해결책:
 *   1) Docker Desktop > Settings > Beta features > Docker AI Agent 비활성화
 *   2) 또는 CI/리눅스 환경에서만 실행
 *
 * 환경변수 RUN_TESTCONTAINERS=true 일 때만 실행. 기본은 skip.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach
    void cleanTasks() {
        taskRepo.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("user_id", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("user_id", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private String body(String repo, String title, String desc) throws Exception {
        return json.writeValueAsString(new TaskCreateRequest(repo, "main", title, desc, java.util.List.of(), null, null));
    }

    @Test
    void POST_tasks_happy_path_returns_201_and_pending_status() throws Exception {
        mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "버그 수정", "로그인이 안 됨")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusLabel").value("작업대기"))
                .andExpect(jsonPath("$.requesterId").value("user1"));
        assertThat(taskRepo.count()).isEqualTo(1);
    }

    @Test
    void POST_tasks_invalid_github_repo_returns_400() throws Exception {
        mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("not-a-valid-repo", "제목", "상세")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.githubRepo").exists());
    }

    @Test
    void POST_tasks_concurrent_limit_returns_429_on_sixth() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/tasks").with(userJwt("user1"))
                            .contentType(APPLICATION_JSON)
                            .content(body("hamonsoft/netis-backend", "작업 " + i, "내용")))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "6번째", "초과")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void GET_tasks_id_other_user_returns_403() throws Exception {
        // user 1이 작성, user 2가 조회
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mvc.perform(get(location).with(userJwt("user2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void GET_tasks_id_admin_can_see_anyone() throws Exception {
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requesterId").value("user1"));
    }

    @Test
    void POST_approve_non_admin_returns_403() throws Exception {
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(post(location + "/approve").with(userJwt("user1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void POST_cancel_in_progress_returns_409() throws Exception {
        // 작업 등록 + DB에서 상태 강제 변경 (분석중)
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "제목", "내용")))
                .andReturn().getResponse().getHeader("Location");
        Long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        var t = taskRepo.findById(id).orElseThrow();
        t.setStatus(com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS);
        taskRepo.save(t);

        mvc.perform(post("/api/tasks/" + id + "/cancel").with(userJwt("user1")))
                .andExpect(status().isConflict());
    }

    // ── Task 16: SSE 스트림 엔드포인트 접근 제어 ──────────────────────────────

    @Test
    void GET_logs_stream_non_owner_returns_403() throws Exception {
        // user1이 작성한 작업을 user2(비관리자)가 스트림 구독 시도 → 403
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "스트림 테스트", "내용")))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mvc.perform(get(location + "/logs/stream").with(userJwt("user2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void GET_logs_stream_owner_returns_200_text_event_stream() throws Exception {
        // 작업 소유자는 자신의 스트림을 구독할 수 있어야 함
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "오너 스트림", "내용")))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mvc.perform(get(location + "/logs/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        containsString("text/event-stream")));
    }

    @Test
    void GET_logs_stream_admin_returns_200() throws Exception {
        // 관리자는 모든 작업의 스트림을 구독할 수 있어야 함
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body("hamonsoft/netis-backend", "관리자 스트림", "내용")))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mvc.perform(get(location + "/logs/stream").with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        containsString("text/event-stream")));
    }
}
