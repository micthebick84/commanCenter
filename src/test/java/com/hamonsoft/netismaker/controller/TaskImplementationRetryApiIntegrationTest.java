package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/tasks/{id}/implementation/retry — 관리자 한정, 시도 번호·방식 반영 (스펙 2026-10-15 §5.5). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskImplementationRetryApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

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

    private long failedTask() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "재시도", "설명"), "user1");
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setHeadBranch("netismaker/task-" + t.getId() + "-x");
        taskRepo.save(t);
        return t.getId();
    }

    @Test
    void only_admins_can_retry() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(userJwt("user1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_retry_requeues_with_the_new_mode_and_attempt() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"SDD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.implementationMode").value("SDD"))
                .andExpect(jsonPath("$.implementationAttempt").value(2));
    }

    @Test
    void an_unknown_mode_is_400() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"FAST\"}"))
                .andExpect(status().isBadRequest());
    }
}
