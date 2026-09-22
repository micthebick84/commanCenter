package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.OfficeFixtures;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.McpCatalogRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 질문 세션 API 표면 (스펙 §5). 상한은 낮춰서 429/400 경로를 짧게 검증한다:
 * 활성 세션 2개 → 3번째 429, assistant 답변 2개 → ask 400.
 * 2026-09-13 추가: multipart 등록/추가질문 + 첨부 다운로드 ACL + 대화 중 MCP 변경 (스펙 §8).
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest(properties = {"app.question.max-active-per-user=2", "app.question.max-qa-turns=2"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-question-att-test")
class QuestionApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private QuestionAttachmentRepository attachmentRepo;
    @Autowired private McpCatalogRepository mcpCatalogRepo;
    @Value("${app.worker.api-key}") private String apiKey;
    @Value("${app.attachment.dir}") private String attachmentDir;

    private static final String CREATE_BODY =
            "{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"title\":\"인증 흐름\","
                    + "\"question\":\"로그인은 어디서 처리되나요?\",\"model\":\"claude-sonnet-5\",\"effort\":\"medium\"}";

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        mcpCatalogRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long createQuestion(RequestPostProcessor as) throws Exception {
        String resp = mvc.perform(post("/api/questions").with(as).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    /** 워커가 세션을 claim하고 답변 1건을 보고 → AWAITING_INPUT. */
    private void workerAnswers(long sid, String text) throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.kind").value("QUESTION"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"" + text + "\",\"claudeSessionId\":\"sess-q\",\"kind\":\"question\",\"costUsd\":0.1}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void POST_creates_queued_question_for_any_authenticated_user() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/questions/")))
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.requesterId").value("user1"))
                .andExpect(jsonPath("$.repoAlias").value("Netis7.0"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"));
    }

    @Test
    void POST_over_active_limit_returns_429() throws Exception {
        createQuestion(userJwt("user1"));
        createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isTooManyRequests());
    }

    /** question은 킥오프 프롬프트에 그대로 삽입되므로 @Size(max=20000) 상한이 있어야 한다 (final-review minor). */
    @Test
    void POST_with_question_over_length_limit_returns_400() throws Exception {
        String tooLong = "a".repeat(20001);
        String body = json.writeValueAsString(Map.of(
                "repoCatalogId", 1,
                "githubBranch", "main",
                "title", "인증 흐름",
                "question", tooLong));
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_detail_owner_and_admin_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.description").value("로그인은 어디서 처리되나요?"));
        mvc.perform(get("/api/questions/" + id).with(adminJwt("admin1"))).andExpect(status().isOk());
        mvc.perform(get("/api/questions/" + id).with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_stream_owner_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_list_mine_only_unless_admin_asks_all() throws Exception {
        createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions").with(userJwt("user1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/questions").with(userJwt("user2"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").with(adminJwt("admin1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").param("all", "true").with(adminJwt("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requesterId").value("user1"));
        // 일반 사용자의 all=true는 무시 — 본인 것만
        mvc.perform(get("/api/questions").param("all", "true").with(userJwt("user2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cross_kind_access_is_404_in_both_directions() throws Exception {
        long qid = createQuestion(userJwt("user1"));
        long iid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1",
                List.of(), "claude-opus-5", "high")).getId();
        mvc.perform(get("/api/interviews/" + qid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/interviews/" + qid + "/cancel").with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/questions/" + iid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions/" + iid + "/close").with(adminJwt("admin1"))).andExpect(status().isNotFound());
    }

    @Test
    void worker_answer_then_owner_asks_followup_requeues_without_phase() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "AuthController에서 처리합니다");
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.statusName").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.currentPhase").isEmpty())   // null — brainstorming 표기 없음
                .andExpect(jsonPath("$.turns", hasSize(1)));
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"토큰 검증은요?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.turns", hasSize(2)));
        // 타인은 추가 질문 불가
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user2"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ask_at_qa_turn_cap_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"더?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk());
        workerAnswers(id, "답변 2");   // assistant 답변 2개 = 상한
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"또?\",\"replyToSeq\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("최대 문답 수")));
    }

    @Test
    void ask_with_new_model_persists_it_and_the_next_worker_claim_carries_it() throws Exception {
        // 대화 중 모델·effort 변경(스펙 2026-09-05 §2 개정): ask 바디의 model/effort → 세션 갱신 → 다음 claim.
        long id = createQuestion(userJwt("user1"));          // sonnet/medium으로 생성
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"이번 건 싸게 답해줘\",\"replyToSeq\":1,"
                                + "\"model\":\"claude-haiku-4-5\",\"effort\":\"low\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.effort").value("low"));
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.effort").value("low"))
                .andExpect(jsonPath("$.claudeSessionId").value("sess-q"));   // 같은 SDK 세션을 resume한다
    }

    @Test
    void ask_with_incompatible_model_effort_returns_400_and_rolls_back_the_requeue() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"x\",\"replyToSeq\":1,\"model\":\"claude-haiku-4-5\",\"effort\":\"max\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("effort")));
        // 검증 실패는 트랜잭션 롤백 — 답변 턴도, 재큐도 남지 않고 모델도 그대로.
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.statusName").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.effort").value("medium"))
                .andExpect(jsonPath("$.turns", hasSize(1)));
    }

    @Test
    void worker_plan_on_question_session_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1}"))
                .andExpect(status().isBadRequest());
        // 세션은 그대로 RUNNING — 인터뷰 서비스가 이어서 답변 보고 가능
        assertThat(sessionRepo.findById(id).orElseThrow().getStatus().name()).isEqualTo("RUNNING");
    }

    @Test
    void close_by_owner_moves_to_cancelled_and_touches_no_task() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("CANCELLED"));
        assertThat(taskRepo.count()).isZero();   // taskId null → mirrorTask no-op, task 없음 유지
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1"))).andExpect(status().isConflict());
    }

    /** 프론트 새 질문 입력창은 제목을 보내지 않는다 — 서버가 첫 줄로 생성 (스펙 2026-09-05 §2). */
    @Test
    void create_without_title_derives_title_from_question() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\","
                                + "\"question\":\"로그인은 어디서 처리되나요?\\n상세 설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("로그인은 어디서 처리되나요?"));
    }

    /** 워커가 /question에 컨텍스트 스냅샷을 보고하면 상세·목록 응답에 그대로 노출된다 (스펙 2026-09-05 §4.2). */
    @Test
    void worker_context_snapshot_is_exposed_on_detail_and_list() throws Exception {
        String created = mvc.perform(post("/api/questions").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();

        mvc.perform(post("/worker/interviews/claim").param("workerId", "w1").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/question").param("workerId", "w1")
                        .header("X-Worker-API-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"content\":\"AuthController입니다\",\"claudeSessionId\":\"sess-1\",\"kind\":\"question\","
                                + "\"costUsd\":0.01,\"inputTokens\":4,\"outputTokens\":120,\"cacheCreationTokens\":30000,"
                                + "\"cacheReadTokens\":46000,\"contextTokens\":76004,\"contextWindow\":200000}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextTokens").value(76004))
                .andExpect(jsonPath("$.contextWindow").value(200000));
        mvc.perform(get("/api/questions").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contextTokens").value(76004));
    }

    // ── 첨부 (스펙 2026-09-13 §5.1, §8) ────────────────────────────────────────

    private MockMultipartFile metaPart(String jsonBody) {
        return new MockMultipartFile("meta", "meta", "application/json", jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile filePart(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private long createQuestionWithFile(RequestPostProcessor as, MockMultipartFile file) throws Exception {
        String resp = mvc.perform(multipart("/api/questions").file(metaPart(CREATE_BODY)).file(file).with(as))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void multipart_등록은_등록분_첨부_행과_디스크_파일을_만들고_상세에_노출된다() throws Exception {
        long id = createQuestionWithFile(userJwt("user1"), filePart("요구사항.txt", "내용"));

        List<QuestionAttachment> rows = attachmentRepo.findBySessionIdOrderByIdAsc(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTurnSeq()).isNull();
        assertThat(rows.get(0).getStoredPath()).isEqualTo("question-" + id + "/create/1-요구사항.txt");
        assertThat(rows.get(0).getExtractedTextPath()).isNull();
        assertThat(Files.readString(Path.of(attachmentDir, "question-" + id + "/create/1-요구사항.txt"),
                StandardCharsets.UTF_8)).isEqualTo("내용");

        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].sizeBytes").value(6))
                .andExpect(jsonPath("$.attachments[0].absolutePath").doesNotExist())
                .andExpect(jsonPath("$.mcpCatalogIds").isArray());
    }

    @Test
    void 파일_파트_없는_multipart_등록도_동작하고_JSON_등록은_변함없다() throws Exception {
        mvc.perform(multipart("/api/questions").file(metaPart(CREATE_BODY)).with(userJwt("user1")))
                .andExpect(status().isCreated());
        assertThat(attachmentRepo.count()).isZero();
        mvc.perform(post("/api/questions").with(userJwt("user2")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void 차단_확장자는_400이고_세션도_파일도_생기지_않는다() throws Exception {
        mvc.perform(multipart("/api/questions").file(metaPart(CREATE_BODY)).file(filePart("evil.exe", "x"))
                        .with(userJwt("user1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("허용되지 않는 파일 형식")));
        assertThat(sessionRepo.count()).isZero();
        assertThat(Files.exists(Path.of(attachmentDir))).isFalse();
    }

    @Test
    void 오피스_문서_등록은_Tika_sidecar를_만들고_claim에_절대경로와_attachmentRoot가_실린다() throws Exception {
        MockMultipartFile docx = new MockMultipartFile("files", "설계.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                OfficeFixtures.minimalDocx("설계 문서 본문 — 인증 흐름"));
        long id = createQuestionWithFile(userJwt("user1"), docx);

        QuestionAttachment row = attachmentRepo.findBySessionIdOrderByIdAsc(id).get(0);
        assertThat(row.getExtractedTextPath()).isEqualTo("question-" + id + "/create/1-설계.docx.txt");
        assertThat(Files.readString(Path.of(attachmentDir, row.getExtractedTextPath()), StandardCharsets.UTF_8))
                .contains("설계 문서 본문 — 인증 흐름");

        MvcResult claim = mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.attachments[0].fileName").value("설계.docx"))
                .andReturn();
        var node = json.readTree(claim.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Path abs = Path.of(node.at("/attachments/0/absolutePath").asText());
        Path txt = Path.of(node.at("/attachments/0/extractedTextPath").asText());
        Path root = Path.of(node.get("attachmentRoot").asText());
        assertThat(abs.isAbsolute() && Files.exists(abs)).isTrue();
        assertThat(txt.isAbsolute() && Files.exists(txt)).isTrue();
        assertThat(root.isAbsolute()).isTrue();
        assertThat(root).isEqualTo(Path.of(attachmentDir).toAbsolutePath().normalize().resolve("question-" + id));
        assertThat(abs.startsWith(root)).isTrue();
    }

    @Test
    void multipart_추가질문은_첨부를_그_답변_턴에_링크하고_claim의_turns에_실린다() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "AuthController에서 처리합니다");   // seq 0 = assistant

        mvc.perform(multipart("/api/questions/" + id + "/ask")
                        .file(metaPart("{\"answer\":\"이 로그 봐줘\",\"replyToSeq\":0}"))
                        .file(filePart("로그.txt", "ERR"))
                        .with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.turns", hasSize(2)))
                .andExpect(jsonPath("$.turns[1].role").value("user"))
                .andExpect(jsonPath("$.turns[1].attachments", hasSize(1)))
                .andExpect(jsonPath("$.turns[1].attachments[0].fileName").value("로그.txt"))
                .andExpect(jsonPath("$.attachments", hasSize(0)));

        QuestionAttachment row = attachmentRepo.findBySessionIdOrderByIdAsc(id).get(0);
        assertThat(row.getTurnSeq()).isEqualTo(1);
        assertThat(row.getStoredPath()).isEqualTo("question-" + id + "/1/1-로그.txt");

        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.attachments", hasSize(0)))
                .andExpect(jsonPath("$.turns[0].attachments", hasSize(0)))
                .andExpect(jsonPath("$.turns[1].attachments[0].absolutePath", containsString("/1/1-로그.txt")))
                .andExpect(jsonPath("$.attachmentRoot", containsString("question-" + id)));
    }

    @Test
    void 첨부_다운로드는_본인과_관리자만_타인은_403_다른_세션_조합은_404() throws Exception {
        long id = createQuestionWithFile(userJwt("user1"), filePart("요구사항.txt", "내용"));
        long attId = attachmentRepo.findBySessionIdOrderByIdAsc(id).get(0).getId();
        String url = "/api/questions/" + id + "/attachments/" + attId;

        MvcResult ok = mvc.perform(get(url).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(content().bytes("내용".getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        String cd = ok.getResponse().getHeader("Content-Disposition");
        assertThat(cd).contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(cd).getFilename()).isEqualTo("요구사항.txt");

        mvc.perform(get(url).with(adminJwt("admin1"))).andExpect(status().isOk());
        mvc.perform(get(url).with(userJwt("user2"))).andExpect(status().isForbidden());

        long other = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + other + "/attachments/" + attId).with(userJwt("user1")))
                .andExpect(status().isNotFound());
        // INTERVIEW 세션 id로는 질문 첨부 API 자체가 404 (kind 가드)
        long iid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1",
                List.of(), "claude-opus-5", "high")).getId();
        mvc.perform(get("/api/questions/" + iid + "/attachments/" + attId).with(adminJwt("admin1")))
                .andExpect(status().isNotFound());
    }

    // ── 대화 중 MCP 변경 (스펙 2026-09-13 §5.2, §5.3) ──────────────────────────

    private long seedMcp(String name) {
        return mcpCatalogRepo.save(McpCatalogEntry.create(name, name.toUpperCase(), "https://" + name + ".example/sse",
                "sse", null, "admin1")).getId();
    }

    @Test
    void ask_with_new_mcp_set_updates_snapshot_appends_note_and_next_claim_carries_it() throws Exception {
        long a = seedMcp("ctx7");
        long b = seedMcp("obsidian");
        String created = mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"question\":\"Q?\",\"mcpCatalogIds\":[" + a + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(created).get("id").asLong();
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.mcpCatalogIds[0]").value(a));
        workerAnswers(id, "답변 1");

        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"이제 obsidian도 써줘\",\"replyToSeq\":0,\"mcpCatalogIds\":[" + b + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.mcpCatalogIds", hasSize(1)))
                .andExpect(jsonPath("$.mcpCatalogIds[0]").value(b))
                .andExpect(jsonPath("$.turns", hasSize(3)))
                .andExpect(jsonPath("$.turns[2].role").value("system"))
                .andExpect(jsonPath("$.turns[2].kind").value("note"))
                .andExpect(jsonPath("$.turns[2].content").value("MCP 도구 변경: obsidian"));

        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.mcpsExtra", hasSize(1)))
                .andExpect(jsonPath("$.mcpsExtra[0].name").value("obsidian"));
    }

    @Test
    void ask_with_same_mcp_set_adds_no_note_and_empty_list_clears_with_note() throws Exception {
        long a = seedMcp("ctx7");
        String created = mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"question\":\"Q?\",\"mcpCatalogIds\":[" + a + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(created).get("id").asLong();
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"더?\",\"replyToSeq\":0,\"mcpCatalogIds\":[" + a + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns", hasSize(2)));   // 노트 없음
        workerAnswers(id, "답변 2");
        // max-qa-turns=2 이므로 한 세션에서 더 못 묻는다 — 해제 경로는 새 세션으로 검증
        String created2 = mvc.perform(post("/api/questions").with(userJwt("user2")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"question\":\"Q2?\",\"mcpCatalogIds\":[" + a + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id2 = json.readTree(created2).get("id").asLong();
        workerAnswers(id2, "답변 1");
        mvc.perform(post("/api/questions/" + id2 + "/ask").with(userJwt("user2")).contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"MCP 빼줘\",\"replyToSeq\":0,\"mcpCatalogIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpCatalogIds", hasSize(0)))
                .andExpect(jsonPath("$.turns", hasSize(3)))
                .andExpect(jsonPath("$.turns[2].content").value("MCP 도구 변경: 없음(전부 해제)"));
    }

    @Test
    void ask_with_unknown_mcp_id_returns_400_and_rolls_back_answer_and_files() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "답변 1");
        mvc.perform(multipart("/api/questions/" + id + "/ask")
                        .file(metaPart("{\"answer\":\"x\",\"replyToSeq\":0,\"mcpCatalogIds\":[999999]}"))
                        .file(filePart("로그.txt", "ERR"))
                        .with(userJwt("user1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("MCP 카탈로그")));
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.statusName").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.turns", hasSize(1)))
                .andExpect(jsonPath("$.mcpCatalogIds", hasSize(0)));
        assertThat(attachmentRepo.count()).isZero();
        assertThat(Files.exists(Path.of(attachmentDir, "question-" + id))).isFalse();   // 파일 쓰기는 검증 뒤 — 고아 없음
    }
}
