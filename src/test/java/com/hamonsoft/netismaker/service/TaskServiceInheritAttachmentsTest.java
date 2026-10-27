package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 질문 → 작업 첨부 승계 (스펙 2026-10-27 §7.5) — 실제 DB·디스크로 순번·파일 복사·렌더링 상태 승계·롤백을 본다.
 * 같은 규칙을 Docker 없이 TaskServiceInheritAttachmentsUnitTest가 목 DB로 지킨다.
 * ⚠️ 테스트 메서드에 @Transactional을 붙이지 않는다 — 롤백이 관측 불가능해진다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = {
        "app.attachment.dir=${java.io.tmpdir}/netismaker-inherit-test",
        "app.attachment.inherit-max-total-mb=1"})
class TaskServiceInheritAttachmentsTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private QuestionAttachmentRepository questionAttachmentRepo;
    @Value("${app.attachment.dir}") private String attachmentDir;

    @BeforeEach void clean() throws Exception {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private long question() {
        InterviewSession q = InterviewSession.createQuestion("micthebick84/netis7.0", "main", "결재선 비교",
                "옛앱 결재선과 비교해 줘", "user1", List.of(), null, null);
        q.setRepoAlias("Netis7.0");
        q.setStatus(InterviewStatus.AWAITING_INPUT);
        return sessionRepo.save(q).getId();
    }

    private QuestionAttachment questionAtt(long qid, Integer turnSeq, String name, long sizeBytes,
                                           boolean writeOriginal, boolean withSidecar,
                                           AttachmentRendition rendition) throws Exception {
        String rel = "question-" + qid + "/" + (turnSeq == null ? "create" : turnSeq) + "/1-" + name;
        Path root = Path.of(attachmentDir);
        if (writeOriginal) {
            Files.createDirectories(root.resolve(rel).getParent());
            Files.writeString(root.resolve(rel), "원본:" + name);
        }
        String sidecar = null;
        if (withSidecar) {
            sidecar = rel + ".txt";
            Files.writeString(root.resolve(sidecar), "텍스트:" + name);
        }
        return questionAttachmentRepo.save(QuestionAttachment.create(qid, turnSeq, name, rel,
                "application/octet-stream", sizeBytes, sidecar, "asker", rendition));
    }

    private static AttachmentRendition none() {
        return AttachmentRendition.of(RenderStatus.NONE, null);
    }

    private static TaskCreateRequest req(Long qid) {
        return new TaskCreateRequest(1L, "main", "결재선 이식", "설명", null, qid);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", "x".getBytes(StandardCharsets.UTF_8));
    }

    private List<String> reasons(long taskId) {
        return historyRepo.findByTaskIdOrderByAtDescIdDesc(taskId, Limit.of(10)).stream()
                .map(TaskStatusHistory::getReason).toList();
    }

    @Test
    void 등록_턴_첨부_순번_N플러스2부터() throws Exception {
        long qid = question();
        questionAtt(qid, null, "덱.pptx", 10, true, true, none());
        questionAtt(qid, 2, "표.csv", 10, true, false, none());

        Task t = taskService.create(req(qid), List.of(file("a.txt")), "user1", null, false);

        List<TaskAttachment> atts = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId());
        String p = "task-" + t.getId() + "/";
        assertThat(atts).extracting(TaskAttachment::getStoredPath).containsExactly(
                p + "1-a.txt", p + "2-question-" + qid + "-대화.md", p + "3-덱.pptx", p + "4-표.csv");
        Path root = Path.of(attachmentDir);
        assertThat(Files.readString(root.resolve(p + "3-덱.pptx"))).isEqualTo("원본:덱.pptx");
        assertThat(Files.readString(root.resolve(p + "3-덱.pptx.txt"))).isEqualTo("텍스트:덱.pptx");
        assertThat(Files.readString(root.resolve(p + "4-표.csv"))).isEqualTo("원본:표.csv");
        assertThat(atts.get(2).getUploadedBy()).isEqualTo("asker");
        assertThat(atts.get(2).getSourceQuestionAttachmentId()).isNotNull();
        assertThat(reasons(t.getId())).contains("질문 #" + qid + " 첨부 승계: 2개");
    }

    @Test
    void DONE_렌더링_승계() throws Exception {
        long qid = question();
        String src = "question-" + qid + "/create/1-덱.pptx.render";
        Path root = Path.of(attachmentDir);
        Files.createDirectories(root.resolve(src));
        Files.write(root.resolve(src).resolve("slide-001.png"), new byte[]{1});
        AttachmentRendition done = AttachmentRendition.of(RenderStatus.PENDING, null);
        done.markDone(src, 1, 1, 0);
        questionAtt(qid, null, "덱.pptx", 10, true, false, done);

        Task t = taskService.create(req(qid), "user1", null, false);

        TaskAttachment deck = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId()).get(1);
        String newDir = "task-" + t.getId() + "/2-덱.pptx.render";
        assertThat(deck.getRendition().getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(deck.getRendition().getDir()).isEqualTo(newDir);
        assertThat(deck.getRendition().getPageCount()).isEqualTo(1);
        assertThat(root.resolve(newDir).resolve("slide-001.png")).exists();
    }

    @Test
    void 질문쪽_PENDING이면_작업쪽은_정책_초기값() throws Exception {
        // 테스트 initializer에서 렌더링이 꺼져 있어 정책 초기값은 SKIPPED — PENDING 자체는 단위 테스트가 본다
        long qid = question();
        questionAtt(qid, null, "덱.pptx", 10, true, false, AttachmentRendition.of(RenderStatus.PENDING, null));

        Task t = taskService.create(req(qid), "user1", null, false);

        TaskAttachment deck = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId()).get(1);
        assertThat(deck.getRendition().getStatus()).isIn(RenderStatus.PENDING, RenderStatus.SKIPPED);
    }

    @Test
    void 정리된_질문_첨부는_건너뜀() throws Exception {
        long qid = question();
        questionAtt(qid, null, "덱.pptx", 10, false, false, none());

        Task t = taskService.create(req(qid), "user1", null, false);

        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t.getId())).hasSize(1);   // 대화 전문만
        assertThat(reasons(t.getId())).contains("질문 #" + qid + " 첨부 승계: 0개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 용량_상한_초과분_제외() throws Exception {
        long qid = question();
        questionAtt(qid, null, "a.pptx", 800L * 1024, true, false, none());
        questionAtt(qid, null, "b.pptx", 800L * 1024, true, false, none());

        Task t = taskService.create(req(qid), "user1", null, false);

        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t.getId())).hasSize(2);   // 대화 전문 + 1개
        assertThat(reasons(t.getId())).contains("질문 #" + qid + " 첨부 승계: 1개 / 제외 1개(정리됨·용량 상한)");
    }

    @Test
    void 출처_질문_없으면_그대로() {
        Task t = taskService.create(req(null), List.of(file("a.txt")), "user1", null, false);
        assertThat(attachmentRepo.findByTaskIdOrderByIdAsc(t.getId())).hasSize(1);
        assertThat(reasons(t.getId())).containsExactly("작업 등록");
    }

    /** ⚠️ @MockitoSpyBean은 컨텍스트 키를 바꾸므로 위 테스트들과 격리하려고 @Nested에 둔다. */
    @Nested
    class 복사_실패_롤백 {

        @MockitoSpyBean AttachmentStorage storage;
        @Autowired private TaskService taskService;
        @Autowired private TaskRepository taskRepo;
        @Autowired private TaskAttachmentRepository attachmentRepo;

        @Test
        void 복사_실패시_등록_롤백() throws Exception {
            long qid = question();
            questionAtt(qid, null, "a.pptx", 10, true, true, none());
            questionAtt(qid, 2, "b.pptx", 10, true, false, none());
            doCallRealMethod().when(storage).copyTree(any(), any());
            doThrow(new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)"))
                    .when(storage).copyTree(eq("question-" + qid + "/2/1-b.pptx"), any());
            long tasksBefore = taskRepo.count();
            long attsBefore = attachmentRepo.count();

            assertThatThrownBy(() -> taskService.create(req(qid), "user1", null, false))
                    .isInstanceOf(TaskException.class);

            assertThat(taskRepo.count()).isEqualTo(tasksBefore);
            assertThat(attachmentRepo.count()).isEqualTo(attsBefore);
            // 먼저 옮긴 첫 첨부(원본·sidecar)는 지워진다 — task-{id}/ 아래에 남은 파일이 없다
            try (var dirs = Files.list(Path.of(attachmentDir))) {
                for (Path d : dirs.filter(x -> x.getFileName().toString().startsWith("task-")).toList()) {
                    try (var files = Files.walk(d)) {
                        assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
                    }
                }
            }
        }
    }
}
