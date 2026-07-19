package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 디자인 구간(설계 → 디자인 승인/반려 → 구현 큐 진입) 전체 플로우 통합 테스트.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip).
 * MockMvc/JWT 헬퍼는 TaskApiIntegrationTest, 워커 API-key 헬퍼는
 * InterviewWorkerApiIntegrationTest의 관용구를 그대로 따른다.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskDesignFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void cleanTasks() {
        // 자식(task_design/task_analysis/interview_session) 먼저 삭제 — task 삭제 FK 방지
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

    // catalog id=1 corresponds to the V14 seed entry (alias 'Netis7.0').
    private String createBody(boolean designRequested) throws Exception {
        return json.writeValueAsString(new TaskCreateRequest(1L, "main", "디자인 플로우 테스트",
                "디자인 구간 검증용 작업", List.of(), null, null, designRequested));
    }

    private Long createTask(boolean designRequested) throws Exception {
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(designRequested)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void 디자인_전체_플로우() throws Exception {
        // 1) designRequested=true로 작업 생성 → 201, body.designRequested==true
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.designRequested").value(true))
                .andReturn().getResponse().getHeader("Location");
        Long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // 2) 워커 분석 claim + COMPLETED 보고
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("ANALYSIS"));

        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"status\":\"COMPLETED\","
                                + "\"markdownResult\":\"# 분석 결과\",\"subtasksJson\":\"[]\"}"))
                .andExpect(status().isNoContent());

        // 3) admin 승인 → 200, statusLabel=="디자인대기" (designRequested=true → DESIGN_PENDING 라우팅)
        mvc.perform(post("/api/tasks/" + id + "/approve").with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("디자인대기"));

        // 4) 워커 claim → kind=="DESIGN"
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("DESIGN"));

        // 5) 워커 designReview 보고 → 상세 조회 statusLabel=="디자인승인대기", design.mockupFilesJson 포함
        String mockups1 = "[{\"path\":\"index.html\",\"title\":\"홈\",\"html\":\"<h1>v1</h1>\"}]";
        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"status\":\"DESIGN_REVIEW\","
                                + "\"designMarkdown\":\"# 디자인 v1\",\"mockupFilesJson\":"
                                + json.writeValueAsString(mockups1) + "}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("디자인승인대기"))
                .andExpect(jsonPath("$.design.mockupFilesJson").exists())
                .andExpect(jsonPath("$.design.mockupFilesJson").value(containsString("index.html")));

        // 6) admin 반려 → 200, statusLabel=="디자인대기", design.rejectCount==1
        mvc.perform(post("/api/tasks/" + id + "/design/reject").with(adminJwt("admin1"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"feedback\":\"버튼이 너무 작음\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("디자인대기"))
                .andExpect(jsonPath("$.design.rejectCount").value(1));

        // 7) 워커 재claim → designMarkdown(이전) + feedbackHistoryJson에 피드백 포함
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("DESIGN"))
                .andExpect(jsonPath("$.designMarkdown").value("# 디자인 v1"))
                .andExpect(jsonPath("$.feedbackHistoryJson").value(containsString("버튼이 너무 작음")));

        // 8) 워커 designReview 재보고 → 디자인승인대기
        String mockups2 = "[{\"path\":\"index.html\",\"title\":\"홈\",\"html\":\"<h1>v2</h1>\"}]";
        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"status\":\"DESIGN_REVIEW\","
                                + "\"designMarkdown\":\"# 디자인 v2\",\"mockupFilesJson\":"
                                + json.writeValueAsString(mockups2) + "}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/tasks/" + id).with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("디자인승인대기"));

        // 9) admin 디자인 승인 → statusLabel=="구현대기"
        mvc.perform(post("/api/tasks/" + id + "/design/approve").with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("구현대기"));

        // 10) 워커 claim → kind=="IMPLEMENTATION", designMarkdown 동봉
        mvc.perform(get("/worker/next-task").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("IMPLEMENTATION"))
                .andExpect(jsonPath("$.designMarkdown").value("# 디자인 v2"));
    }

    @Test
    void 디자인_반려는_admin_전용이다() throws Exception {
        Long id = createTask(false);
        mvc.perform(post("/api/tasks/" + id + "/design/reject").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"feedback\":\"피드백\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 피드백_없는_반려는_400() throws Exception {
        Long id = createTask(false);
        mvc.perform(post("/api/tasks/" + id + "/design/reject").with(adminJwt("admin1"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"feedback\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
