package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-att-api-test")
class TaskAttachmentApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Autowired private com.hamonsoft.netismaker.service.TaskService taskService;
    @Autowired private com.hamonsoft.netismaker.repository.InterviewSessionRepository sessionRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        // interview_session이 task_id FK를 참조하므로 taskRepo.deleteAll()보다 먼저 지워야
        // 한다 — 안 그러면 claim 테스트(task-7)가 만든 세션이 남아 다음 테스트에서 FK 위반이 난다.
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private MockMultipartFile metaPart() throws Exception {
        return new MockMultipartFile("meta", "meta", "application/json",
                json.writeValueAsBytes(new TaskCreateRequest(1L, "main", "첨부 등록", "설명")));
    }

    private static MockMultipartFile filePart(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void multipart_등록은_201과_함께_메타행과_디스크_파일을_만든다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("첨부 등록"));

        Long taskId = taskRepo.findAll().get(0).getId();
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(taskId)).hasSize(1);
        assertThat(Files.exists(Path.of(attachmentDir, "task-" + taskId + "/1-요구사항.txt"))).isTrue();
    }

    @Test
    void 파일_파트_없는_multipart_등록도_동작한다() throws Exception {
        mvc.perform(multipart("/api/tasks").file(metaPart()).with(userJwt("user1")))
                .andExpect(status().isCreated());
        assertThat(attachmentRepo.count()).isZero();
    }

    @Test
    void 기존_JSON_등록은_변함없이_동작한다() throws Exception {
        mvc.perform(post("/api/tasks").with(userJwt("user1"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                new TaskCreateRequest(1L, "main", "JSON 등록", "설명"))))
                .andExpect(status().isCreated());
    }

    @Test
    void 차단_확장자는_400_한국어_메시지고_task가_생기지_않는다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("evil.exe", "x"))
                        .with(userJwt("user1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("허용되지 않는 파일 형식")));
        assertThat(taskRepo.count()).isZero();
        assertThat(Files.exists(Path.of(attachmentDir))).isFalse();
    }

    /**
     * meta 파트도 @RequestBody와 동일하게 @Valid가 걸리는지 확인 (리뷰 요청, task-4).
     * title은 TaskCreateRequest의 @NotBlank — 공백이면 검증에서 걸려야 하고, 걸리지 않으면
     * MethodArgumentNotValidException이 안 던져져 task 행이 그대로 생겨버리는 조용한 구멍이다.
     */
    @Test
    void meta의_title이_공백이면_400이고_task가_생기지_않는다() throws Exception {
        MockMultipartFile blankTitleMeta = new MockMultipartFile("meta", "meta", "application/json",
                json.writeValueAsBytes(new TaskCreateRequest(1L, "main", "   ", "설명")));

        mvc.perform(multipart("/api/tasks")
                        .file(blankTitleMeta)
                        .with(userJwt("user1")))
                .andExpect(status().isBadRequest());
        assertThat(taskRepo.count()).isZero();
    }

    @Test
    void 상세_조회에는_attachments_메타가_실린다() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated());
        Long taskId = taskRepo.findAll().get(0).getId();

        mvc.perform(get("/api/tasks/" + taskId).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].sizeBytes").value(6));
        // "내용" = UTF-8 6바이트
    }

    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * multipart 등록 후 (taskId, attachmentId) 반환. taskId는 생성 응답 JSON의 id를
     * 직접 읽는다(taskRepo.findAll().get(0)은 여러 task가 공존할 때 순서를 보장하지
     * 않는 함정 — 자기 리뷰 발견, task-6 리뷰에서 제거 확정).
     */
    private long[] registerWithFile() throws Exception {
        String responseJson = mvc.perform(multipart("/api/tasks")
                        .file(metaPart())
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long taskId = json.readTree(responseJson).get("id").asLong();
        Long attId = attachmentRepo.findByTaskIdOrderByIdAsc(taskId).get(0).getId();
        return new long[]{taskId, attId};
    }

    /**
     * Content-Disposition이 RFC 5987 filename*=UTF-8''로 실제 인코딩되는지 핀 고정
     * (리뷰 요청, task-6). "attachment"만 확인하는 약한 단언은 filename(name, UTF_8) →
     * filename(name)(charset 없는 오버로드)로의 회귀를 잡지 못하고 조용히 통과한다 —
     * 그러면 한글 파일명이 깨진다. 실제 헤더 값을 Spring의 ContentDisposition.parse로
     * 되읽어 원본 파일명과 라운드트립이 성립하는지까지 확인한다.
     */
    @Test
    void 요청자_본인은_다운로드할_수_있다() throws Exception {
        long[] ids = registerWithFile();
        MvcResult result = mvc.perform(get("/api/tasks/" + ids[0] + "/attachments/" + ids[1])
                        .with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(content().bytes("내용".getBytes(StandardCharsets.UTF_8)))
                .andReturn();

        String contentDisposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(contentDisposition).contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(contentDisposition).getFilename())
                .isEqualTo("요구사항.txt");
    }

    @Test
    void 타인은_403_관리자는_200이다() throws Exception {
        long[] ids = registerWithFile();
        String url = "/api/tasks/" + ids[0] + "/attachments/" + ids[1];
        mvc.perform(get(url).with(userJwt("other"))).andExpect(status().isForbidden());
        mvc.perform(get(url).with(adminJwt("admin"))).andExpect(status().isOk());
    }

    @Test
    void 디스크_파일이_유실되면_404다() throws Exception {
        long[] ids = registerWithFile();
        Files.delete(Path.of(attachmentDir, "task-" + ids[0] + "/1-요구사항.txt"));
        mvc.perform(get("/api/tasks/" + ids[0] + "/attachments/" + ids[1])
                        .with(userJwt("user1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_task의_attId를_섞으면_404다() throws Exception {
        long[] ids = registerWithFile();
        mvc.perform(get("/api/tasks/" + (ids[0] + 999) + "/attachments/" + ids[1])
                        .with(adminJwt("admin")))
                .andExpect(status().isNotFound());
    }

    /**
     * 존재하지 않는 task id가 아니라, 실존하는 다른 task의 첨부 id를 섞는 경우 —
     * TaskService.getAttachment의 taskId 불일치 검사(getForView 이전 단계가 아니라
     * getAttachment 자신의 분기)가 실제로 404를 내는지 확인 (자기 리뷰, task-6).
     * registerWithFile()은 taskRepo.findAll().get(0)에 의존해 단일 task 전제이므로,
     * task 2개가 공존하는 이 테스트에서는 각자의 생성 응답에서 id를 직접 뽑아 쓴다.
     */
    @Test
    void 실존하는_다른_task의_attId를_섞으면_404다() throws Exception {
        long task1Id = createTaskWithFile("첨부 등록 1");
        long task2Id = createTaskWithFile("첨부 등록 2");
        long task2AttId = attachmentRepo.findByTaskIdOrderByIdAsc(task2Id).get(0).getId();

        mvc.perform(get("/api/tasks/" + task1Id + "/attachments/" + task2AttId)
                        .with(adminJwt("admin")))
                .andExpect(status().isNotFound());
    }

    private long createTaskWithFile(String title) throws Exception {
        MockMultipartFile meta = new MockMultipartFile("meta", "meta", "application/json",
                json.writeValueAsBytes(new TaskCreateRequest(1L, "main", title, "설명")));
        String responseJson = mvc.perform(multipart("/api/tasks")
                        .file(meta)
                        .file(filePart("요구사항.txt", "내용"))
                        .with(userJwt("user1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(responseJson).get("id").asLong();
    }

    /**
     * 워커 claim 페이로드가 실제로 절대경로를 나르는지 확인 (task-7). registerWithFile()로
     * 등록 → approve로 인터뷰 세션을 QUEUED로 만든 뒤 /worker/interviews/claim이 그 세션을
     * 잡으면서 attachments[0].absolutePath가 디스크상의 실제 파일을 가리켜야 한다.
     */
    @Test
    void 승인_후_워커_claim에_첨부_절대경로가_실린다() throws Exception {
        long[] ids = registerWithFile();
        taskService.approve(ids[0], "admin", null);   // AWAITING_APPROVAL → 인터뷰 세션 생성

        MvcResult result = mvc.perform(post("/worker/interviews/claim")
                        .header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].absolutePath").value(
                        org.hamcrest.Matchers.endsWith("task-" + ids[0] + "/1-요구사항.txt")))
                .andReturn();

        // 문자열 단언만으론 워커가 실제로 열 수 있는 경로인지 증명하지 못한다 — 진짜
        // 절대경로(파일시스템 루트에서 시작)이고 디스크상에 실존하는지까지 확인 (task-7 self-review).
        String absolutePath = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .at("/attachments/0/absolutePath").asText();
        Path path = Path.of(absolutePath);
        assertThat(path.isAbsolute()).isTrue();
        assertThat(Files.exists(path)).isTrue();
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo("내용");
    }
}
