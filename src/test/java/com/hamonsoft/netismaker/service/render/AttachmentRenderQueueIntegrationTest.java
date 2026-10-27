package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.render.RenderJobRef.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 렌더링 큐 — SKIP LOCKED claim·RUNNING 전이·stale 회수 (스펙 2026-10-27 §6). */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class AttachmentRenderQueueIntegrationTest {

    @Autowired private AttachmentRenderQueue queue;
    @Autowired private TaskAttachmentRepository taskAttachments;
    @Autowired private QuestionAttachmentRepository questionAttachments;
    @Autowired private TaskRepository tasks;
    @Autowired private InterviewSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        taskAttachments.deleteAll();
        questionAttachments.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
    }

    private TaskAttachment pendingTask(long taskId, int ordinal) {
        String name = ordinal + ".pptx";
        return taskAttachments.save(TaskAttachment.create(taskId, name, "task-" + taskId + "/" + ordinal + "-" + name,
                null, 1L, "user1", null, AttachmentRendition.of(RenderStatus.PENDING, null), null));
    }

    private long savedTaskId() {
        return tasks.save(Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null)).getId();
    }

    @Test
    void claimNext_RUNNING_전이() {
        long taskId = savedTaskId();
        TaskAttachment a = pendingTask(taskId, 1);
        InterviewSession s = sessions.save(InterviewSession.createQuestion("acme/widgets", "main", "제목", "질문?",
                "user1", List.of(), "claude-opus-5", "high"));
        QuestionAttachment q = questionAttachments.save(QuestionAttachment.create(s.getId(), null, "q.pptx",
                "question-" + s.getId() + "/create/1-q.pptx", null, 1L, null, "user1",
                AttachmentRendition.of(RenderStatus.PENDING, null)));

        Optional<RenderJobRef> first = queue.claimNext();
        Optional<RenderJobRef> second = queue.claimNext();
        Optional<RenderJobRef> none = queue.claimNext();

        assertThat(first).contains(new RenderJobRef(Kind.TASK, a.getId(), a.getStoredPath(), "1.pptx"));
        assertThat(second).contains(new RenderJobRef(Kind.QUESTION, q.getId(), q.getStoredPath(), "q.pptx"));
        assertThat(none).isEmpty();
        AttachmentRendition r = taskAttachments.findById(a.getId()).orElseThrow().getRendition();
        assertThat(r.getStatus()).isEqualTo(RenderStatus.RUNNING);
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(r.getStartedAt()).isNotNull();
        assertThat(questionAttachments.findById(q.getId()).orElseThrow().getRendition().getStatus())
                .isEqualTo(RenderStatus.RUNNING);

        queue.complete(first.get(), a.getStoredPath() + ".render", new RenderResult(2, 5, 1));
        queue.fail(second.get(), "PDF 변환 실패");
        AttachmentRendition done = taskAttachments.findById(a.getId()).orElseThrow().getRendition();
        assertThat(done.getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(done.getTotalPages()).isEqualTo(5);
        AttachmentRendition failed = questionAttachments.findById(q.getId()).orElseThrow().getRendition();
        assertThat(failed.getStatus()).isEqualTo(RenderStatus.FAILED);
        assertThat(failed.getError()).isEqualTo("PDF 변환 실패");
    }

    @Test
    void stale_RUNNING_회수_1회() {
        long taskId = savedTaskId();
        TaskAttachment once = pendingTask(taskId, 1);
        TaskAttachment twice = pendingTask(taskId, 2);
        TaskAttachment fresh = pendingTask(taskId, 3);
        OffsetDateTime old = OffsetDateTime.now().minusHours(1);
        jdbc.update("UPDATE com.task_attachment SET render_status='RUNNING', render_started_at=?, render_attempts=1,"
                + " render_error='이전' WHERE id=?", old, once.getId());
        jdbc.update("UPDATE com.task_attachment SET render_status='RUNNING', render_started_at=?, render_attempts=2"
                + " WHERE id=?", old, twice.getId());
        jdbc.update("UPDATE com.task_attachment SET render_status='RUNNING', render_started_at=?, render_attempts=1"
                + " WHERE id=?", OffsetDateTime.now(), fresh.getId());

        int n = queue.recoverStale(OffsetDateTime.now().minusMinutes(4));

        assertThat(n).isEqualTo(2);
        AttachmentRendition r1 = taskAttachments.findById(once.getId()).orElseThrow().getRendition();
        assertThat(r1.getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(r1.getStartedAt()).isNull();
        assertThat(r1.getError()).isNull();
        AttachmentRendition r2 = taskAttachments.findById(twice.getId()).orElseThrow().getRendition();
        assertThat(r2.getStatus()).isEqualTo(RenderStatus.FAILED);
        assertThat(r2.getError()).isEqualTo("렌더링 중단(재기동)");
        assertThat(taskAttachments.findById(fresh.getId()).orElseThrow().getRendition().getStatus())
                .isEqualTo(RenderStatus.RUNNING);
    }

    @Test
    void 동시_claim_한_건씩() throws Exception {
        long taskId = savedTaskId();
        TaskAttachment a = pendingTask(taskId, 1);
        TaskAttachment b = pendingTask(taskId, 2);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 스레드 A: 바깥 트랜잭션 안에서 claim하고 커밋하지 않은 채 행 잠금을 쥐고 있는다
        CompletableFuture<Optional<RenderJobRef>> holder = CompletableFuture.supplyAsync(() -> tx.execute(st -> {
            Optional<RenderJobRef> ref = queue.claimNext();
            claimed.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ref;
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        // 스레드 B: 잠긴 행을 건너뛰고(SKIP LOCKED) 기다리지 않고 다음 행을 잡는다
        Optional<RenderJobRef> other = CompletableFuture.supplyAsync(queue::claimNext).get(10, TimeUnit.SECONDS);
        release.countDown();
        Optional<RenderJobRef> held = holder.get(10, TimeUnit.SECONDS);

        assertThat(held).isPresent();
        assertThat(other).isPresent();
        assertThat(List.of(held.get().attachmentId(), other.get().attachmentId()))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }
}
