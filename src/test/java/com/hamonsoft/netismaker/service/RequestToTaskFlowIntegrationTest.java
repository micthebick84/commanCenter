package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.RequestCommentKind;
import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RequestAttachmentRepository;
import com.hamonsoft.netismaker.repository.RequestCommentRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 개발 요청 → 작업 등록 (스펙 2026-11-19 §5.4) — 실제 DB·디스크·HTTP로 끝까지 본다: 첨부 있는 개발 요청 등록(multipart) →
 * 다른 사용자 목록(scope=dev)·열람·답글 → 관리자가 sourceRequestId로 작업 등록 → 요청 처리완료·연결, 작업 등록자 = 원 요청자·
 * 첨부 복사, 같은 바디로 다시 → 409(작업 수 그대로), 요청 첨부 내려받기는 그대로 된다. 복사 실패 롤백·되돌리기 가드도 본다.
 * 같은 규칙을 Docker 없이 TaskServiceFromRequestTest·RequestBoardServiceTest가 목 DB로 지킨다.
 * ⚠️ 테스트 메서드에 @Transactional을 붙이지 않는다 — 롤백이 관측 불가능해진다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-request-to-task-test")
class RequestToTaskFlowIntegrationTest {

    private static final String REQUESTER = "user2";
    private static final String OTHER = "user1";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private RequestPostRepository posts;
    @Autowired private RequestCommentRepository comments;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;

    /** 답글·요청 첨부 행은 FK ON DELETE CASCADE로 함께 지워진다. */
    @BeforeEach void clean() throws Exception {
        posts.deleteAll();
        attachmentRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static RequestPostProcessor user(String username) {
        return jwt().jwt(b -> b.claim("username", username).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin1").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestBoardDto.CreateRequest devCreate() {
        return new RequestBoardDto.CreateRequest(RequestKind.DEV, "결재선 화면 오류", "결재선 저장 시 500",
                null, RequestIssueType.BUG, null, null, null);
    }

    /** 첨부 하나 있는 개발 요청을 multipart로 등록하고 id를 돌려준다. */
    private long createDevRequestWithAttachment() throws Exception {
        MockMultipartFile meta = new MockMultipartFile("meta", "meta", "application/json",
                json.writeValueAsBytes(devCreate()));
        MockMultipartFile file = new MockMultipartFile("files", "화면.txt", "text/plain",
                "재현 화면".getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/requests").file(meta).file(file).with(user(REQUESTER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentCount").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String taskBody(long requestId) throws Exception {
        return json.writeValueAsString(new TaskCreateRequest(1L, "main", "결재선 화면 오류 수정", "요청에서 등록",
                null, null, null, requestId));
    }

    @Test
    void 개발_요청에서_작업_등록_전체_흐름() throws Exception {
        long rid = createDevRequestWithAttachment();

        // 다른 사용자: 공개 목록·열람·답글
        mvc.perform(get("/api/requests").param("scope", "dev").with(user(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) rid)));
        String detail = mvc.perform(get("/api/requests/" + rid).with(user(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long attId = ((Number) JsonPath.read(detail, "$.attachments[0].id")).longValue();
        mvc.perform(post("/api/requests/" + rid + "/comments").with(user(OTHER)).contentType(APPLICATION_JSON)
                        .content("{\"body\":\"저도 같은 오류가 납니다\"}"))
                .andExpect(status().isCreated());
        // 관리자 상세: 작업으로 등록 가능
        mvc.perform(get("/api/requests/" + rid).with(admin()))
                .andExpect(jsonPath("$.convertible").value(true));

        // 관리자가 요청에서 작업 등록
        String created = mvc.perform(post("/api/tasks").with(admin()).contentType(APPLICATION_JSON)
                        .content(taskBody(rid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requesterId").value(REQUESTER))
                .andExpect(jsonPath("$.sourceRequestId").value((int) rid))
                .andExpect(jsonPath("$.sourceQuestionId").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long taskId = ((Number) JsonPath.read(created, "$.id")).longValue();

        // 요청: 처리완료·연결·시스템 답글
        mvc.perform(get("/api/requests/" + rid).with(user(REQUESTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.taskId").value((int) taskId))
                .andExpect(jsonPath("$.convertible").value(false))
                .andExpect(jsonPath("$.cancellable").value(false));
        assertThat(comments.findByRequestIdOrderByCreatedAtAscIdAsc(rid))
                .filteredOn(c -> c.getKind() == RequestCommentKind.SYSTEM)
                .extracting(c -> c.getBody())
                .containsExactly("관리자(admin1)가 작업 #" + taskId + "으로 등록 — 처리완료");

        // 작업: 등록자 = 원 요청자, 출처 요청, 첨부 1개(파일까지)
        Task task = taskRepo.findById(taskId).orElseThrow();
        assertThat(task.getRequesterId()).isEqualTo(REQUESTER);
        assertThat(task.getRequesterEmail()).isNull();
        assertThat(task.getSourceRequestId()).isEqualTo(rid);
        List<TaskAttachment> atts = attachmentRepo.findByTaskIdOrderByIdAsc(taskId);
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).getStoredPath()).isEqualTo("task-" + taskId + "/1-화면.txt");
        assertThat(atts.get(0).getUploadedBy()).isEqualTo(REQUESTER);
        assertThat(Files.readString(Path.of(attachmentDir, atts.get(0).getStoredPath()))).isEqualTo("재현 화면");
        // 원 요청자는 자기 작업으로 본다
        mvc.perform(get("/api/tasks/" + taskId).with(user(REQUESTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1));

        // 같은 바디로 다시 → 409, 작업 수 그대로
        long tasksBefore = taskRepo.count();
        mvc.perform(post("/api/tasks").with(admin()).contentType(APPLICATION_JSON).content(taskBody(rid)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 작업 #" + taskId + "으로 등록된 요청입니다"));
        assertThat(taskRepo.count()).isEqualTo(tasksBefore);
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(taskId)).hasSize(1);

        // 요청 첨부 내려받기는 그대로 된다(복사이지 이동이 아니다)
        mvc.perform(get("/api/requests/" + rid + "/attachments/" + attId).with(user(OTHER)))
                .andExpect(status().isOk())
                .andExpect(content().bytes("재현 화면".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 비관리자는_403이고_아무것도_바뀌지_않는다() throws Exception {
        long rid = createDevRequestWithAttachment();
        long tasksBefore = taskRepo.count();

        mvc.perform(post("/api/tasks").with(user(REQUESTER)).contentType(APPLICATION_JSON).content(taskBody(rid)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("요청을 작업으로 등록할 권한이 없습니다"));

        assertThat(taskRepo.count()).isEqualTo(tasksBefore);
        RequestPost p = posts.findById(rid).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(RequestStatus.RECEIVED);
        assertThat(p.getTaskId()).isNull();
    }

    @Test
    void 연결된_요청은_작업이_살아_있으면_되돌릴_수_없고_삭제되면_연결_해제() throws Exception {
        long rid = createDevRequestWithAttachment();
        String created = mvc.perform(post("/api/tasks").with(admin()).contentType(APPLICATION_JSON)
                        .content(taskBody(rid)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long taskId = ((Number) JsonPath.read(created, "$.id")).longValue();
        String reopen = "{\"status\":\"REVIEWING\"}";

        mvc.perform(post("/api/admin/requests/" + rid + "/status").with(admin()).contentType(APPLICATION_JSON)
                        .content(reopen))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("작업 #" + taskId + "에 연결된 요청은 되돌릴 수 없습니다"));
        // 연결된 요청은 원 요청자도 취소할 수 없다
        mvc.perform(delete("/api/requests/" + rid).with(user(REQUESTER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("작업에 연결된 요청은 취소할 수 없습니다"));

        mvc.perform(delete("/api/tasks/" + taskId).with(admin())).andExpect(status().is2xxSuccessful());

        mvc.perform(post("/api/admin/requests/" + rid + "/status").with(admin()).contentType(APPLICATION_JSON)
                        .content(reopen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.taskId").doesNotExist());
        assertThat(posts.findById(rid).orElseThrow().getTaskId()).isNull();
        assertThat(comments.findByRequestIdOrderByCreatedAtAscIdAsc(rid))
                .extracting(c -> c.getBody())
                .contains("관리자(admin1)가 상태를 검토중으로 변경 (작업 #" + taskId + " 연결 해제)");
    }

    /** ⚠️ @MockitoSpyBean은 컨텍스트 키를 바꾸므로 위 테스트들과 격리하려고 @Nested에 둔다. */
    @Nested
    class 복사_실패_롤백 {

        @MockitoSpyBean AttachmentStorage storage;
        @Autowired private TaskService taskService;
        @Autowired private RequestBoardService requestBoardService;
        @Autowired private RequestPostRepository posts;
        @Autowired private RequestCommentRepository comments;
        @Autowired private RequestAttachmentRepository requestAttachments;
        @Autowired private TaskRepository taskRepo;
        @Autowired private TaskAttachmentRepository attachmentRepo;

        @Test
        void 복사_실패면_작업과_연결이_모두_취소된다() throws Exception {
            long rid = requestBoardService.create(devCreate(), List.of(
                    new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8)),
                    new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes(StandardCharsets.UTF_8))),
                    REQUESTER).id();
            String bPath = requestAttachments.findByRequestIdOrderByOrdinalAsc(rid).get(1).getStoredPath();
            doCallRealMethod().when(storage).copyTree(any(), any());
            doThrow(new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)"))
                    .when(storage).copyTree(eq(bPath), any());
            long tasksBefore = taskRepo.count();
            long attsBefore = attachmentRepo.count();

            assertThatThrownBy(() -> taskService.create(new TaskCreateRequest(1L, "main", "제목", "설명",
                    null, null, null, rid), "admin1", null, true))
                    .isInstanceOf(TaskException.class)
                    .hasMessage("디스크 오류(테스트)");

            // 작업·첨부 행이 없고, 요청은 열린 채 연결도 없다(linkTask가 같은 tx에서 롤백됐다)
            assertThat(taskRepo.count()).isEqualTo(tasksBefore);
            assertThat(attachmentRepo.count()).isEqualTo(attsBefore);
            RequestPost p = posts.findById(rid).orElseThrow();
            assertThat(p.getStatus()).isEqualTo(RequestStatus.RECEIVED);
            assertThat(p.getTaskId()).isNull();
            assertThat(comments.findByRequestIdOrderByCreatedAtAscIdAsc(rid)).isEmpty();
            // 먼저 옮긴 첫 첨부는 지워진다 — task-{id}/ 아래에 남은 파일이 없다
            try (var dirs = Files.list(Path.of(attachmentDir))) {
                for (Path d : dirs.filter(x -> x.getFileName().toString().startsWith("task-")).toList()) {
                    try (var files = Files.walk(d)) {
                        assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
                    }
                }
            }
            // 요청 쪽 원본은 그대로
            assertThat(Path.of(attachmentDir, bPath)).exists();

            // 실패 뒤 다시 등록하면 된다(락·연결이 남지 않았다)
            doCallRealMethod().when(storage).copyTree(any(), any());
            Task t = taskService.create(new TaskCreateRequest(1L, "main", "제목", "설명", null, null, null, rid),
                    "admin1", null, true);
            assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t.getId())).hasSize(2);
            assertThat(posts.findById(rid).orElseThrow().getTaskId()).isEqualTo(t.getId());
        }
    }
}
