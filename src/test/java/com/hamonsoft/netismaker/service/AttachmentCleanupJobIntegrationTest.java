package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 첨부 정리 잡의 새 쿼리(소유 행 상태 일괄 조회·최대 id)와 실제 DB + 임시 첨부 루트 end-to-end.
 * cron은 TestcontainersConfig가 꺼 두므로 runOnce를 직접 호출한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.attachment.dir=${java.io.tmpdir}/netismaker-att-cleanup-test")
class AttachmentCleanupJobIntegrationTest {

    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskAttachmentRepository taskAttachmentRepo;
    @Autowired private QuestionAttachmentRepository questionAttachmentRepo;
    @Autowired private AttachmentCleanupJob job;
    @Value("${app.attachment.dir}") private String attachmentDir;

    @BeforeEach void clean() throws Exception {
        taskAttachmentRepo.deleteAll();
        questionAttachmentRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        FileSystemUtils.deleteRecursively(Path.of(attachmentDir));
    }

    private Task savedTask(OffsetDateTime deletedAt) {
        Task t = Task.create("acme/widgets", "main", "제목", "설명", "user1", 3, new ArrayList<>(), null, null);
        t.setDeletedAt(deletedAt);
        return taskRepo.save(t);
    }

    private InterviewSession savedQuestion(InterviewStatus status, OffsetDateTime lastActivityAt) {
        InterviewSession s = InterviewSession.createQuestion("acme/widgets", "main", "제목", "질문?",
                "user1", List.of(), "test-model", "high");
        s.setStatus(status);
        s.setLastActivityAt(lastActivityAt);
        return sessionRepo.save(s);
    }

    @Test
    void 소유_행_상태_조회는_soft_delete된_작업을_포함하고_없는_id는_빠진다() {
        OffsetDateTime deletedAt = OffsetDateTime.now().minusDays(40);
        Task alive = savedTask(null);
        Task deleted = savedTask(deletedAt);
        long missing = deleted.getId() + 1000;

        Map<Long, TaskRepository.AttachmentOwnerState> rows = taskRepo
                .findAttachmentOwnerStates(List.of(alive.getId(), deleted.getId(), missing)).stream()
                .collect(Collectors.toMap(TaskRepository.AttachmentOwnerState::getId, Function.identity()));

        assertThat(rows).containsOnlyKeys(alive.getId(), deleted.getId());
        assertThat(rows.get(alive.getId()).getDeletedAt()).isNull();
        assertThat(rows.get(deleted.getId()).getDeletedAt().toInstant())
                .isCloseTo(deletedAt.toInstant(), within(1, ChronoUnit.MILLIS));
        assertThat(taskRepo.findMaxId()).isEqualTo(deleted.getId());
    }

    @Test
    void 세션_상태_조회는_종류_상태_마지막_활동을_변환해_돌려준다() {
        OffsetDateTime closedAt = OffsetDateTime.now().minusDays(100);
        InterviewSession closed = savedQuestion(InterviewStatus.CANCELLED, closedAt);
        InterviewSession interview = sessionRepo.save(InterviewSession.create("acme/widgets", "main", "T", "D",
                "user1", List.of(), "test-model", "high"));

        Map<Long, InterviewSessionRepository.AttachmentOwnerState> rows = sessionRepo
                .findAttachmentOwnerStates(List.of(closed.getId(), interview.getId(), interview.getId() + 1000)).stream()
                .collect(Collectors.toMap(InterviewSessionRepository.AttachmentOwnerState::getId, Function.identity()));

        assertThat(rows).containsOnlyKeys(closed.getId(), interview.getId());
        assertThat(rows.get(closed.getId()).getKind()).isEqualTo(InterviewKind.QUESTION);
        assertThat(rows.get(closed.getId()).getStatus()).isEqualTo(InterviewStatus.CANCELLED);   // 한글 DB값 → enum
        assertThat(rows.get(closed.getId()).getLastActivityAt().toInstant())
                .isCloseTo(closedAt.toInstant(), within(1, ChronoUnit.MILLIS));
        assertThat(rows.get(interview.getId()).getKind()).isEqualTo(InterviewKind.INTERVIEW);
        assertThat(sessionRepo.findMaxId()).isEqualTo(interview.getId());
    }

    @Test
    void 빈_테이블의_최대_id는_null() {
        assertThat(taskRepo.findMaxId()).isNull();
        assertThat(sessionRepo.findMaxId()).isNull();
    }

    @Test
    void 실제_DB_기준으로_정리하고_첨부_행은_남긴다() throws Exception {
        Path root = Path.of(attachmentDir);
        Instant old = Instant.now().minus(Duration.ofDays(400));

        Task alive = savedTask(null);
        Task gone = savedTask(null);
        taskRepo.delete(gone);   // 발급됐지만(< max) 행이 없는 id = 등록 롤백 잔재 흉내
        long orphanTaskId = gone.getId();
        Task deleted = savedTask(OffsetDateTime.now().minusDays(31));
        taskAttachmentRepo.save(TaskAttachment.create(deleted.getId(), "a.txt",
                "task-" + deleted.getId() + "/1-a.txt", "text/plain", 1L, "user1"));
        InterviewSession closed = savedQuestion(InterviewStatus.EXPIRED, OffsetDateTime.now().minusDays(91));
        questionAttachmentRepo.save(QuestionAttachment.create(closed.getId(), null, "q.txt",
                "question-" + closed.getId() + "/create/1-q.txt", "text/plain", 1L, null, "user1"));
        InterviewSession awaiting = savedQuestion(InterviewStatus.AWAITING_INPUT, OffsetDateTime.now().minusDays(365));

        for (String rel : List.of("task-" + alive.getId() + "/1-a.txt", "task-" + deleted.getId() + "/1-a.txt",
                "question-" + closed.getId() + "/create/1-q.txt", "question-" + awaiting.getId() + "/create/1-q.txt",
                "task-" + orphanTaskId + "/1-a.txt")) {
            Path p = root.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "x");
        }
        try (var dirs = Files.list(root)) {
            for (Path d : dirs.toList()) Files.setLastModifiedTime(d, FileTime.from(old));
        }

        AttachmentCleanupJob.Summary s = job.runOnce(Instant.now());

        assertThat(root.resolve("task-" + alive.getId())).isDirectory();
        assertThat(root.resolve("task-" + deleted.getId())).doesNotExist();
        assertThat(root.resolve("question-" + closed.getId())).doesNotExist();
        assertThat(root.resolve("question-" + awaiting.getId())).isDirectory();
        assertThat(root.resolve("task-" + orphanTaskId)).doesNotExist();
        assertThat(s.deletedOrphan()).isEqualTo(1);
        assertThat(s.deletedTask()).isEqualTo(1);
        assertThat(s.deletedQuestion()).isEqualTo(1);
        assertThat(s.failed()).isZero();
        // DB 첨부 행은 감사용으로 남는다
        assertThat(taskAttachmentRepo.findByTaskIdOrderByIdAsc(deleted.getId())).hasSize(1);
        assertThat(questionAttachmentRepo.findBySessionIdOrderByIdAsc(closed.getId())).hasSize(1);
    }
}
