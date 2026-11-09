package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워커 목업 업로드 엔드포인트 (스펙 2026-11-03 §5) — 검사 문구가 {error, message} 바디로 나가는지, 에이전트가 한 메시지에서 show_mockup을
 * 여러 번 동시에 불러도 n·버전이 겹치지 않는지(세션 행 잠금), API 키·workerId 가드.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewMockupWorkerApiIntegrationTest {

    private static final String KEY_HEADER = "X-Worker-API-Key";
    private static final String HTML = "<!doctype html><html><body>목록</body></html>";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewMockupRepository mockupRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private Long sid;

    @BeforeEach
    void seed() throws Exception {
        sessionRepo.deleteAll();   // interview_mockup은 CASCADE
        sid = sessionRepo.save(InterviewSession.create("hamonsoft/netis-backend", "main",
                "목업", "화면 작업", "user1", List.of(), "claude-opus-5-5", "high")).getId();
        // 워커 iw-1이 claim해 RUNNING이 된 상태 — 목업은 이 상태에서만 올라간다
        mvc.perform(post("/worker/interviews/claim").header(KEY_HEADER, apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk());
    }

    private String body(String name, String html) throws Exception {
        return json.writeValueAsString(Map.of("name", name, "title", name + " 안", "description", "설명", "html", html));
    }

    private MvcResult upload(String name, String html) throws Exception {
        return mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON).content(body(name, html)))
                .andReturn();
    }

    /** 이름마다 한 스레드씩, 장벽으로 맞춰 동시에 올린다. */
    private List<JsonNode> uploadInParallel(List<String> names) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(names.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(names.size());
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (String name : names) {
                calls.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return upload(name, HTML);
                });
            }
            List<JsonNode> bodies = new ArrayList<>();
            for (Future<MvcResult> f : pool.invokeAll(calls)) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
                bodies.add(json.readTree(r.getResponse().getContentAsString()));
            }
            return bodies;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 업로드는_id_n_version을_돌려주고_세션에_쌓인다() throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON).content(body("list-a", HTML)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.n").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.html").doesNotExist());

        List<InterviewMockupSummary> list = mockupRepo.findSummaries(sid);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("list-a");
        assertThat(list.get(0).title()).isEqualTo("list-a 안");
        assertThat(list.get(0).turnSeq()).as("어느 턴에도 안 묶인 채").isNull();
        InterviewMockup row = mockupRepo.findAll().get(0);
        assertThat(row.getHtml()).isEqualTo(HTML);
        assertThat(row.getSizeBytes()).isEqualTo(HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void 한_메시지의_병렬_업로드도_n과_버전이_겹치지_않는다() throws Exception {
        // [RF1] 같은 name을 두 스레드가 동시에 — 둘 다 200, n {1,2}, version {1,2}
        List<JsonNode> bodies = uploadInParallel(List.of("list-a", "list-a"));

        Set<Integer> ns = new HashSet<>();
        Set<Integer> versions = new HashSet<>();
        for (JsonNode b : bodies) {
            ns.add(b.get("n").asInt());
            versions.add(b.get("version").asInt());
        }
        assertThat(ns).containsExactlyInAnyOrder(1, 2);
        assertThat(versions).containsExactlyInAnyOrder(1, 2);
        assertThat(mockupRepo.findSummaries(sid)).hasSize(2);
    }

    @Test
    void 이름이_섞인_네_건의_병렬_업로드도_모두_올라간다() throws Exception {
        List<JsonNode> bodies = uploadInParallel(List.of("list-a", "list-b", "list-a", "list-b"));

        assertThat(bodies).extracting(b -> b.get("n").asInt()).containsExactlyInAnyOrder(1, 2, 3, 4);
        List<InterviewMockupSummary> list = mockupRepo.findSummaries(sid);
        assertThat(list).hasSize(4);
        for (String name : List.of("list-a", "list-b")) {   // 이름마다 버전은 1, 2
            assertThat(list).filteredOn(m -> m.name().equals(name)).extracting(InterviewMockupSummary::version)
                    .containsExactlyInAnyOrder(1, 2);
        }
    }

    @Test
    void 오류_바디는_error와_message() throws Exception {
        String over = "가".repeat(68267);   // 204 801바이트
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON).content(body("list-a", over)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("목업이 200KB를 넘습니다: 201KB"));

        // 다른 워커가 잡은 인터뷰 — 409 {error, message}
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-2").contentType(APPLICATION_JSON).content(body("list-a", HTML)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("다른 워커가 잡은 인터뷰입니다 (소유: iw-1)"));

        // 검증 실패 문구가 "검증 오류"로 가려지지 않는다 — 이름·제목 사유가 그대로
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON).content(body("Bad Name", HTML)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("목업 이름은 영문 소문자·숫자·하이픈 64자 이하여야 합니다: Bad Name"));
        assertThat(mockupRepo.findSummaries(sid)).isEmpty();
    }

    @Test
    void API키가_없으면_거절_workerId가_없으면_400() throws Exception {
        // 워커 체인은 진입점이 없어 403이다 (InterviewWorkerApiIntegrationTest.claim_requires_api_key_returns_401_or_403 선례)
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON).content(body("list-a", HTML)))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/worker/interviews/" + sid + "/mockups").header(KEY_HEADER, apiKey)
                        .contentType(APPLICATION_JSON).content(body("list-a", HTML)))
                .andExpect(status().isBadRequest());
        assertThat(mockupRepo.findSummaries(sid)).isEmpty();
    }
}
