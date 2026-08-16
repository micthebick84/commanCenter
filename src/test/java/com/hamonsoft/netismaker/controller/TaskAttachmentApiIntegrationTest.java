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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
    @Value("${app.attachment.dir}") private String attachmentDir;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
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

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/" + taskId).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].fileName").value("요구사항.txt"))
                .andExpect(jsonPath("$.attachments[0].sizeBytes").value(6));
        // "내용" = UTF-8 6바이트
    }
}
