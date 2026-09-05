package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 구독 사용량 API 표면 (스펙 2026-09-05 §5.2/§5.3): 워커 보고 → 사용자 조회, upsert, 검증, 인증 경계. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class UsageApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ClaudeRateLimitRepository repo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() {
        repo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void worker_report_is_upserted_and_visible_to_any_authenticated_user() throws Exception {
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":0.42,"
                                + "\"resetsAt\":\"2026-09-05T07:00:00Z\",\"isUsingOverage\":false}"))
                .andExpect(status().isNoContent());
        // 같은 타입 재보고 → 행 1개 유지(upsert), 최신 값으로 교체
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed_warning\",\"utilization\":0.81}"))
                .andExpect(status().isNoContent());
        assertThat(repo.count()).isEqualTo(1);

        mvc.perform(get("/api/usage/claude").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits", hasSize(1)))
                .andExpect(jsonPath("$.limits[0].limitType").value("five_hour"))
                .andExpect(jsonPath("$.limits[0].status").value("allowed_warning"))
                .andExpect(jsonPath("$.limits[0].utilization").value(0.81))
                .andExpect(jsonPath("$.limits[0].reportedBy").value("iw-1"))
                .andExpect(jsonPath("$.limits[0].updatedAt").exists());
    }

    @Test
    void utilization_outside_0_1_is_rejected() throws Exception {
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":1.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auth_boundaries() throws Exception {
        // 워커 키 없음 → WorkerApiKeyFilter가 거절
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":0.1}"))
                .andExpect(status().is4xxClientError());
        // JWT 없음 → 401
        mvc.perform(get("/api/usage/claude")).andExpect(status().isUnauthorized());
    }
}
