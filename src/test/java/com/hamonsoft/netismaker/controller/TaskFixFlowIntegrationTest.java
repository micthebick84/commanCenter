package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 수정 요청 구간(등록자 요청 → 관리자 승인/반려, 관리자 직접 요청) 통합 테스트 (스펙 2026-11-03 §6).
 * Testcontainers 기반 — RUN_TESTCONTAINERS=true 일 때만 실행(이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskFixFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanTasks() {
        fixRepo.deleteAll();
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

    private Long deployedTask(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "수정 흐름", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(status);
        t.setPrNumber(3);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setHeadBranch("netismaker/task-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        return taskRepo.save(t).getId();
    }

    private static final String BODY = "{\"body\":\"장기분석 화면 로딩이 끝나지 않음\"}";

    @Test
    void 등록자_요청_관리자_승인_뒤_상세에_회차가_보인다() throws Exception {
        Long id = deployedTask(TaskStatus.DEPLOYED);

        // 남의 작업은 403, 본문이 비면 400
        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user2"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FIX_REVIEW"));
        // 두 번째 요청은 409이고 기록은 하나만 남는다
        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        assertThat(fixRepo.findByTaskIdOrderByIdDesc(id)).hasSize(1);

        // 승인은 관리자 전용
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/approve").with(userJwt("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/approve").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"implementationMode\":\"SDD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/approve").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"implementationMode\":\"REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/api/tasks/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.fix.activeRound").value(1))
                .andExpect(jsonPath("$.fix.activeMode").value("REVIEW"))
                .andExpect(jsonPath("$.fix.approvedRounds").value(1))
                .andExpect(jsonPath("$.fix.maxRounds").value(3));
        // 목록은 회차 현황을 싣지 않는다
        mvc.perform(get("/api/tasks").with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fix").doesNotExist());

        // 기록: 등록자·관리자만 본다
        mvc.perform(get("/api/tasks/" + id + "/fix-requests").with(userJwt("user2")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/" + id + "/fix-requests").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].round").value(1))
                .andExpect(jsonPath("$[0].implementationMode").value("REVIEW"))
                .andExpect(jsonPath("$[0].requestedBy").value("user1"))
                .andExpect(jsonPath("$[0].decidedBy").value("admin"));

        // 열린 회차가 있는 동안 다시 요청하면 409
        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void 반려와_철회는_요청_전_상태로_돌아가고_회차에_세지_않는다() throws Exception {
        Long id = deployedTask(TaskStatus.DEPLOY_FAILED);

        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/reject").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/reject").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/reject").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"재현이 안 됨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPLOY_FAILED"));

        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(userJwt("user1"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/withdraw").with(userJwt("user2")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/fix-requests/withdraw").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPLOY_FAILED"));

        mvc.perform(get("/api/tasks/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.fix.activeRound").doesNotExist())
                .andExpect(jsonPath("$.fix.approvedRounds").value(0));
        mvc.perform(get("/api/tasks/" + id + "/fix-requests").with(userJwt("user1")))
                .andExpect(jsonPath("$[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$[1].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].decisionReason").value("재현이 안 됨"));
    }

    @Test
    void 관리자_요청은_바로_구현대기이고_PR생성_상태는_409() throws Exception {
        Long id = deployedTask(TaskStatus.DEPLOYED);
        mvc.perform(post("/api/tasks/" + id + "/fix-requests").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"고쳐 주세요\",\"implementationMode\":\"DIRECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin")))
                .andExpect(jsonPath("$.fix.activeRound").value(1))
                .andExpect(jsonPath("$.fix.activeMode").value("DIRECT"));

        Long prCreated = deployedTask(TaskStatus.PR_CREATED);
        mvc.perform(post("/api/tasks/" + prCreated + "/fix-requests").with(adminJwt("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }
}
