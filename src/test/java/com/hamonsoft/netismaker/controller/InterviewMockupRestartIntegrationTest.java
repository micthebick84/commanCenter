package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [RF3] 목업을 올린 턴이 재기동으로 끊긴 경우 (스펙 2026-11-03 §5) — 기동 정합(재큐)이 그 턴의 안 묶인 목업을 버려
 * 목록에서 빠지고, 같은 턴을 다시 돌려 같은 이름을 올리면 번호는 이어지고(버린 번호를 다시 쓰지 않음) 버전은 1부터다.
 * 하네스는 {@code InterviewWorkerApiIntegrationTest}와 같다(워커 키만).
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewMockupRestartIntegrationTest {

    private static final String KEY_HEADER = "X-Worker-API-Key";
    private static final String HTML = "<!doctype html><html><body>목록</body></html>";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewMockupRepository mockupRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private Long sid;

    @BeforeEach
    void seed() {
        sessionRepo.deleteAll();   // interview_mockup은 CASCADE
        sid = sessionRepo.save(InterviewSession.create("hamonsoft/netis-backend", "main",
                "목업", "화면 작업", "user1", List.of(), "claude-opus-5-5", "high")).getId();
    }

    private void claim() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header(KEY_HEADER, apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid));
    }

    private JsonNode upload(String name) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "title", name + " 안", "html", HTML));
        String res = mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void 재기동_재큐_뒤_끊긴_턴의_목업은_목록에서_빠지고_번호는_이어진다() throws Exception {
        claim();
        JsonNode first = upload("a");
        assertThat(first.get("n").asInt()).isEqualTo(1);
        assertThat(first.get("version").asInt()).isEqualTo(1);

        // 인터뷰 서비스 재기동 — 이 워커가 잡고 있던 RUNNING 세션을 QUEUED로 되돌린다(같은 턴을 다시 돌림)
        mvc.perform(post("/worker/interviews/startup").header(KEY_HEADER, apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value(sid));
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.QUEUED);

        assertThat(mockupRepo.findSummaries(sid)).as("끊긴 턴의 목업은 목록에서 빠진다").isEmpty();
        InterviewMockup discarded = mockupRepo.findById(first.get("id").asLong()).orElseThrow();
        assertThat(discarded.isDiscarded()).as("행은 남고 버림 표시만").isTrue();

        // 같은 턴을 다시 돌려 같은 이름을 올린다 — 번호는 버린 1 다음, 버전은 버린 것을 세지 않아 1
        claim();
        JsonNode again = upload("a");
        assertThat(again.get("n").asInt()).isEqualTo(2);
        assertThat(again.get("version").asInt()).isEqualTo(1);
        assertThat(mockupRepo.findSummaries(sid)).singleElement()
                .satisfies(m -> {
                    assertThat(m.n()).isEqualTo(2);
                    assertThat(m.turnSeq()).as("아직 어느 턴에도 안 묶임").isNull();
                });
    }
}
