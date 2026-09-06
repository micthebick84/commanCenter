package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/tasks/{id}/history — 최신순·ACL·라벨 매핑 (스펙 2026-09-06 §6). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskHistoryApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
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

    // catalog id=1 = V14 seed (alias 'Netis7.0')
    private Task createTask(String requester) {
        return taskService.create(new TaskCreateRequest(1L, "main", "이력", "설명"), requester);
    }

    @Test
    void 등록과_승인이_최신순으로_라벨과_함께_조회된다() throws Exception {
        Task t = createTask("user1");
        taskService.approve(t.getId(), "admin", null);   // 승인대기 → 인터뷰중

        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].fromStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[0].toStatus").value("INTERVIEWING"))
                .andExpect(jsonPath("$[0].toLabel").value("인터뷰중"))
                .andExpect(jsonPath("$[0].actorId").value("admin"))
                .andExpect(jsonPath("$[1].fromStatus").isEmpty())
                .andExpect(jsonPath("$[1].toStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[1].reason").value("작업 등록"));
    }

    @Test
    void 비소유자는_403_관리자는_200() throws Exception {
        Task t = createTask("user1");
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user2"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(adminJwt("admin"))).andExpect(status().isOk());
    }

    @Test
    void 삭제된_작업은_404() throws Exception {
        Task t = createTask("user1");
        taskService.softDelete(t.getId(), "user1", false);
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user1"))).andExpect(status().isNotFound());
    }
}
