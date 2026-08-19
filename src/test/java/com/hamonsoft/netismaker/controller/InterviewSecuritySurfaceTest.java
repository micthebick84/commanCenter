package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewSecuritySurfaceTest {

    @Autowired private MockMvc mvc;

    @Test
    void api_interviews_without_jwt_is_401() throws Exception {
        mvc.perform(post("/api/interviews/1/confirm").contentType(APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void worker_interviews_claim_without_api_key_is_rejected() throws Exception {
        mvc.perform(post("/worker/interviews/claim").param("workerId", "iw-1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void api_interviews_stream_without_token_is_401() throws Exception {
        mvc.perform(get("/api/interviews/1/stream")).andExpect(status().isUnauthorized());
    }
}
