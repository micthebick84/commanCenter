package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 종료 구간(등록자 요청 → 관리자 승인/반려) 통합 테스트 (스펙 2026-10-13 §5).
 * Testcontainers 기반 — RUN_TESTCONTAINERS=true 일 때만 실행.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskCloseFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanTasks() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void 등록자_요청_관리자_승인_반려_흐름() throws Exception {
        Task t = Task.create("owner/repo", "main", "종료 흐름", "d", "alice", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.DEPLOYED);
        t.setPrNumber(3);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setHeadBranch("netismaker/task-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        Long id = taskRepo.save(t).getId();

        mvc.perform(post("/api/tasks/" + id + "/close-request").with(userJwt("bob")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/close-request").with(userJwt("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_REVIEW"))
                .andExpect(jsonPath("$.close.progress").isEmpty());
        mvc.perform(post("/api/tasks/" + id + "/close/approve").with(userJwt("alice")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/close/reject").with(adminJwt("root"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"다시 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPLOYED"));
        mvc.perform(post("/api/tasks/" + id + "/close-request").with(userJwt("alice")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/close/approve").with(adminJwt("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"));
        // 종료 진행 중에는 재배포·중지·삭제가 막힌다
        mvc.perform(post("/api/tasks/" + id + "/redeploy").with(adminJwt("root")))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/tasks/" + id).with(adminJwt("root")))
                .andExpect(status().isConflict());
    }
}
