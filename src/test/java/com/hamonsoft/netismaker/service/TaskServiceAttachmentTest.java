package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-att-test")
class TaskServiceAttachmentTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // repoCatalogId=1 = V14 시드 (InterviewApiIntegrationTest.startSession과 동일 전제)
    private static TaskCreateRequest req() {
        return new TaskCreateRequest(1L, "main", "첨부 테스트", "설명");
    }

    @Test
    void 파일과_함께_등록하면_메타행과_디스크_파일이_모두_생긴다() throws Exception {
        Task t = taskService.create(req(), List.of(
                file("요구사항.txt", "내용A"), file("데이터.csv", "a,b")), "user1");

        List<TaskAttachment> atts = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId());
        assertThat(atts).hasSize(2);
        assertThat(atts.get(0).getStoredPath()).isEqualTo("task-" + t.getId() + "/1-요구사항.txt");
        assertThat(atts.get(1).getStoredPath()).isEqualTo("task-" + t.getId() + "/2-데이터.csv");
        assertThat(atts.get(0).getUploadedBy()).isEqualTo("user1");

        Path root = Path.of(attachmentDir);
        assertThat(Files.readString(root.resolve("task-" + t.getId() + "/1-요구사항.txt")))
                .isEqualTo("내용A");
    }

    @Test
    void 파일_없이_null_또는_빈리스트면_기존_등록과_동일하다() {
        Task t1 = taskService.create(req(), null, "user1");
        Task t2 = taskService.create(req(), List.of(), "user1");
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t1.getId())).isEmpty();
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t2.getId())).isEmpty();
    }

    @Test
    void 검증_실패면_task도_생성되지_않는다() {
        long before = taskRepo.count();
        assertThatThrownBy(() -> taskService.create(req(),
                List.of(file("evil.exe", "x")), "user1"))
                .isInstanceOf(TaskException.class);
        assertThat(taskRepo.count()).isEqualTo(before);
    }

    @Test
    void getAttachment은_taskId가_다르면_notFound다() {
        Task t = taskService.create(req(), List.of(file("a.txt", "x")), "user1");
        Long attId = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId()).get(0).getId();
        assertThatThrownBy(() -> taskService.getAttachment(t.getId() + 999, attId))
                .isInstanceOf(TaskException.class);
        assertThat(taskService.getAttachment(t.getId(), attId).getOriginalFilename())
                .isEqualTo("a.txt");
    }
}
