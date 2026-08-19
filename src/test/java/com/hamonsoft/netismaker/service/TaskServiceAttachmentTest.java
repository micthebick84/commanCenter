package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * 첫 파일은 쓰였는데 두 번째에서 디스크가 실패하는 경우 — DB(task + 메타행)가 전부
     * 롤백되고 이미 쓴 파일은 정리되는지. 실제 디스크는 실패를 재현하기 어려워
     * AttachmentStorage를 목으로 대체한다.
     *
     * ⚠️ @Nested로 격리하는 이유: 클래스 레벨 @MockitoBean은 컨텍스트 전체에 적용돼
     * 위쪽 테스트들의 "실제 디스크에 쓰였는가 / 실제 검증이 도는가"를 전부 무력화한다.
     * ⚠️ 테스트 메서드에 @Transactional을 붙이면 롤백이 관측 불가능해지므로 붙이지 않는다.
     */
    @Nested
    class 디스크_쓰기_실패_롤백 {

        @MockitoBean AttachmentStorage attachmentStorage;

        // ⚠️ 목이 걸린 컨텍스트는 바깥 클래스의 컨텍스트와 별개다(@MockitoBean이 컨텍스트
        // 캐시 키를 바꾼다). 바깥 인스턴스의 @Autowired는 바깥 컨텍스트에서 주입되므로,
        // 이 목을 실제로 물고 있는 빈을 쓰려면 여기서 다시 주입받아야 한다.
        @Autowired private TaskService taskService;
        @Autowired private TaskRepository taskRepo;
        @Autowired private TaskAttachmentRepository attachmentRepo;

        @Test
        void 두번째_파일_쓰기가_실패하면_task와_메타행이_모두_롤백되고_쓴_파일은_정리된다() {
            doNothing().when(attachmentStorage).validate(any());
            when(attachmentStorage.relativePath(anyLong(), anyInt(), any()))
                    .thenAnswer(inv -> "task-" + inv.getArgument(0) + "/"
                            + inv.getArgument(1) + "-" + inv.getArgument(2));
            // 1번째 write는 성공, 2번째에서 실패 — "일부만 쓰인" 상태를 만든다.
            doNothing()
                    .doThrow(new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "boom"))
                    .when(attachmentStorage).write(any(), any());

            long tasksBefore = taskRepo.count();
            long attachmentsBefore = attachmentRepo.count();

            assertThatThrownBy(() -> taskService.create(req(),
                    List.of(file("a.txt", "내용A"), file("b.txt", "내용B")), "user1"))
                    .isInstanceOf(TaskException.class);

            assertThat(taskRepo.count()).isEqualTo(tasksBefore);
            assertThat(attachmentRepo.count()).isEqualTo(attachmentsBefore);
            // 롤백 경로에서 이미 쓴 파일(2건 등록분)은 best-effort 삭제된다.
            verify(attachmentStorage, times(2)).deleteQuietly(any());
        }
    }
}
