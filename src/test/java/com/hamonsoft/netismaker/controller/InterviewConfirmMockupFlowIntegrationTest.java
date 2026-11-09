package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 확정 바디의 mockupIds가 작업에 저장되고 상세에 실린다 (스펙 2026-11-03 §7). 계획이 가리킨 번호를 해제하면 제외 번호가 남는다.
 * 하네스는 ImplementationModeFlowIntegrationTest와 같다(워커 claim → 목업 업로드 → plan → 확정).
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewConfirmMockupFlowIntegrationTest {

    private static final String KEY_HEADER = "X-Worker-API-Key";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();   // interview_mockup은 CASCADE
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

    private Long createTask() throws Exception {
        String body = json.writeValueAsString(new TaskCreateRequest(1L, "main", "목업 확정 흐름", "목업 확정 검증용 작업"));
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    /** 작업 등록 → 승인 → 워커 claim → 목업 1건 업로드 → plan(본문 "목업 1 기준") → 플랜승인대기. 반환 = {세션 id, 목업 id}. */
    private long[] prepareReview(Long taskId) throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + "/approve").with(adminJwt("admin"))).andExpect(status().isOk());
        long sid = sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId).orElseThrow().getId();
        mvc.perform(post("/worker/interviews/claim").header(KEY_HEADER, apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk());
        String res = mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "list-a", "title", "A안",
                                "description", "목록", "html", "<!doctype html><html><body>A</body></html>"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(res);
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"목업 1 기준\",\"planJson\":\"[]\","
                                + "\"costUsd\":0.1,\"durationMs\":1000}"))
                .andExpect(status().isNoContent());
        return new long[]{sid, node.get("id").asLong()};
    }

    @Test
    void 확정_바디의_mockupIds가_작업에_저장되고_상세에_실린다() throws Exception {
        Long taskId = createTask();
        long[] ids = prepareReview(taskId);

        mvc.perform(post("/api/interviews/" + ids[0] + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"designRequested\":false,\"mockupIds\":[" + ids[1] + "]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tasks/" + taskId).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementationMockupIds[0]").value(ids[1]))
                .andExpect(jsonPath("$.implementationMockupIds.length()").value(1));
        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getImplementationMockupExcluded()).isEmpty();
    }

    @Test
    void 키_없는_확정은_빈_목록과_제외_번호를_남긴다() throws Exception {
        Long taskId = createTask();
        long[] ids = prepareReview(taskId);

        mvc.perform(post("/api/interviews/" + ids[0] + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"designRequested\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tasks/" + taskId).with(adminJwt("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementationMockupIds.length()").value(0));
        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getImplementationMockupExcluded()).containsExactly(1);
    }

    @Test
    void 남의_목업_id는_400이고_상태는_그대로() throws Exception {
        Long taskId = createTask();
        long[] ids = prepareReview(taskId);

        mvc.perform(post("/api/interviews/" + ids[0] + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"designRequested\":false,\"mockupIds\":[" + (ids[1] + 1000) + "]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/tasks/" + taskId).with(adminJwt("admin")))
                .andExpect(jsonPath("$.status").value("INTERVIEW_REVIEW"));
    }
}
