package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 작업 API에 노출되는 단계별 usage/총비용 표면 (Task 7). 상세는 stageUsage 배열 +
 * totalCostUsd/totalTokens, 목록은 totalCostUsd만(그 외 stageUsage는 항상 []).
 * TaskApiIntegrationTest의 인증/시큐리티/컨테이너 관행을 그대로 따른다.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskUsageApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskStageUsageRepository usageRepo;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanTasks() {
        // 공유 컨테이너 — 다른 클래스가 남긴 자식 row가 task 삭제를 FK로 막지 않도록 자식 먼저 삭제
        usageRepo.deleteAll();
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private String body(String title, String desc) throws Exception {
        return json.writeValueAsString(new TaskCreateRequest(1L, "main", title, desc));
    }

    private long createTask(String title) throws Exception {
        String location = mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content(body(title, "내용")))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    /** accumulate는 @Modifying 네이티브 쿼리라 활성 트랜잭션이 필요 — 실제 커밋해 이후 MockMvc
     * 호출(별도 트랜잭션)에서 보이도록 TransactionTemplate으로 직접 커밋한다. */
    private void seedUsage(long taskId) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            usageRepo.accumulate(taskId, TaskStageUsage.STAGE_ANALYSIS,
                    new BigDecimal("0.10"), 100L, 50L, 0L, 0L);
            usageRepo.accumulate(taskId, TaskStageUsage.STAGE_IMPLEMENTATION,
                    new BigDecimal("0.25"), 200L, 100L, 10L, 5L);
        });
    }

    @Test
    void GET_detail_returns_stageUsage_and_totals() throws Exception {
        long id = createTask("usage 상세");
        seedUsage(id);

        String resp = mvc.perform(get("/api/tasks/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageUsage.length()").value(2))
                .andExpect(jsonPath("$.stageUsage[0].stage").value("ANALYSIS"))
                .andExpect(jsonPath("$.stageUsage[1].stage").value("IMPLEMENTATION"))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = json.readTree(resp);
        assertThat(new BigDecimal(node.get("totalCostUsd").asText())).isEqualByComparingTo("0.35");
        assertThat(node.get("totalTokens").asLong()).isEqualTo(450L); // (100+50)+(200+100), 캐시 제외
    }

    @Test
    void GET_detail_without_usage_returns_null_totals_and_empty_stageUsage() throws Exception {
        long id = createTask("usage 없음 상세");

        mvc.perform(get("/api/tasks/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageUsage").isEmpty())
                .andExpect(jsonPath("$.totalCostUsd").doesNotExist())
                .andExpect(jsonPath("$.totalTokens").doesNotExist());
    }

    @Test
    void GET_list_exposes_totalCostUsd_only_null_when_no_usage() throws Exception {
        long withUsage = createTask("목록 usage 있음");
        long withoutUsage = createTask("목록 usage 없음");
        seedUsage(withUsage);

        String resp = mvc.perform(get("/api/tasks").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = json.readTree(resp).get("content");
        JsonNode withUsageNode = findById(content, withUsage);
        JsonNode withoutUsageNode = findById(content, withoutUsage);

        assertThat(withUsageNode.get("stageUsage")).isEmpty();
        assertThat(new BigDecimal(withUsageNode.get("totalCostUsd").asText())).isEqualByComparingTo("0.35");

        assertThat(withoutUsageNode.get("stageUsage")).isEmpty();
        assertThat(withoutUsageNode.hasNonNull("totalCostUsd")).isFalse();
    }

    private JsonNode findById(JsonNode array, long id) {
        for (JsonNode n : array) {
            if (n.get("id").asLong() == id) return n;
        }
        throw new AssertionError("id " + id + " not found in list response: " + array);
    }
}
