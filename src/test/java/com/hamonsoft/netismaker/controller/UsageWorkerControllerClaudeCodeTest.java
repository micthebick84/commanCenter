package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import com.hamonsoft.netismaker.service.UserProvisioner;
import com.hamonsoft.netismaker.usage.ClaudeCodeUsageIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UsageWorkerController.class)
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class UsageWorkerControllerClaudeCodeTest {

    private static final String URL = "/worker/usage/claude-code-events";

    @Autowired private MockMvc mvc;
    @Value("${app.worker.api-key}") private String apiKey;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private UserProvisioner userProvisioner;
    @MockBean private ClaudeUsageService usage;
    @MockBean private ClaudeCodeUsageIngestService ingest;

    @Test
    void 키가_맞으면_집계_수() throws Exception {
        when(ingest.ingest(any())).thenReturn(new ClaudeCodeUsageBatchResponse(3, 2, 1, 0));
        String body = "{\"machine\":\"pc\",\"unreadableLines\":0,\"events\":[{\"eventKey\":\"k\","
                + "\"occurredAt\":\"2026-11-01T00:00:00+09:00\",\"category\":\"SYMPHONY\",\"model\":\"m\","
                + "\"inputTokens\":1,\"outputTokens\":2,\"cacheWrite5mTokens\":0,\"cacheWrite1hTokens\":0,\"cacheReadTokens\":0}]}";

        mvc.perform(post(URL).header("X-Worker-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(3))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0));
    }

    @Test
    void 빈_묶음도_200() throws Exception {
        when(ingest.ingest(any())).thenReturn(new ClaudeCodeUsageBatchResponse(0, 0, 0, 0));

        mvc.perform(post(URL).header("X-Worker-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machine\":\"pc\",\"unreadableLines\":2,\"events\":[]}"))
                .andExpect(status().isOk());

        verify(ingest).ingest(any(ClaudeCodeUsageBatchRequest.class));
    }

    @Test
    void 키가_없으면_거절() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machine\":\"pc\",\"events\":[]}"))
                .andExpect(status().is4xxClientError());

        verify(ingest, never()).ingest(any());
    }

    @Test
    void 천한_건이나_machine_없으면_400() throws Exception {
        String one = "{\"eventKey\":\"k\",\"occurredAt\":\"2026-11-01T00:00:00+09:00\",\"category\":\"DIRECT\",\"model\":\"m\"}";
        String many = String.join(",", java.util.Collections.nCopies(1001, one));

        mvc.perform(post(URL).header("X-Worker-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machine\":\"pc\",\"events\":[" + many + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).header("X-Worker-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isBadRequest());

        verify(ingest, never()).ingest(any());
    }
}
