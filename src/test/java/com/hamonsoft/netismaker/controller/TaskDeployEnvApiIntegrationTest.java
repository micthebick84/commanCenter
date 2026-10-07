package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
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

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/tasks/{id}/deploy-env 권한·내용 + 비관리자 응답 비밀 값 비우기 (스펙 2026-10-07 §6, §7). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskDeployEnvApiIntegrationTest {

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
    private Task prCreated(String requester) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "배포", "설명"), requester);
        t.setStatus(TaskStatus.PR_CREATED);
        return taskRepo.save(t);
    }

    @Test
    void 관리자는_직전_배포_값으로_채운_추천을_받는다() throws Exception {
        Task prev = prCreated("user2");
        taskService.deploy(prev.getId(), "admin", List.of(
                new EnvVar("DB_URL", "jdbc:prev", false), new EnvVar("DB_PASSWORD", "pw", true)));
        Task cur = prCreated("user1");
        cur.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB 접속 URL", false, true))));
        taskRepo.save(cur);

        mvc.perform(get("/api/tasks/" + cur.getId() + "/deploy-env").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousTaskId").value(prev.getId().intValue()))
                .andExpect(jsonPath("$.templateCount").value(1))
                .andExpect(jsonPath("$.rows[0].key").value("DB_URL"))
                .andExpect(jsonPath("$.rows[0].value").value("jdbc:prev"))
                .andExpect(jsonPath("$.rows[0].source").value("TEMPLATE"))
                .andExpect(jsonPath("$.rows[0].description").value("DB 접속 URL"))
                .andExpect(jsonPath("$.rows[1].key").value("DB_PASSWORD"))
                .andExpect(jsonPath("$.rows[1].value").value("pw"))
                .andExpect(jsonPath("$.rows[1].source").value("PREVIOUS"));
    }

    @Test
    void 일반_사용자는_추천을_볼_수_없다() throws Exception {
        Task cur = prCreated("user1");

        mvc.perform(get("/api/tasks/" + cur.getId() + "/deploy-env").with(userJwt("user1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일반_사용자_응답에서는_비밀_값이_비고_관리자는_그대로_본다() throws Exception {
        Task cur = prCreated("user1");
        taskService.deploy(cur.getId(), "admin", List.of(
                new EnvVar("DB_URL", "jdbc:x", false), new EnvVar("DB_PASSWORD", "pw", true)));

        mvc.perform(get("/api/tasks/" + cur.getId()).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envVars[0].value").value("jdbc:x"))
                .andExpect(jsonPath("$.envVars[1].value").value(""));
        mvc.perform(get("/api/tasks").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].envVars[1].value").value(""));
        mvc.perform(get("/api/tasks/" + cur.getId()).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envVars[1].value").value("pw"));
    }
}
