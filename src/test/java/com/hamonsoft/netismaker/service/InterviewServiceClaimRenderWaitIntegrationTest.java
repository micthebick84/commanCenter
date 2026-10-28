package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.config.RenderProperties;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claim 렌더링 대기 (스펙 2026-10-27 §7.2) — 렌더링 중(PENDING·RUNNING) 첨부가 대기 상한 안이면 claim을 보류하고,
 * 상한이 지나면 claim하되 그 첨부에 "준비 중(대기 시간 초과)" 안내를 싣는다. 실 Postgres의 FOR UPDATE SKIP LOCKED +
 * NOT EXISTS 조합을 확인한다. RUN_TESTCONTAINERS=true에서만 실행.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewServiceClaimRenderWaitIntegrationTest {

    @Autowired private InterviewService service;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private QuestionAttachmentRepository questionAttachments;
    @Autowired private TaskAttachmentRepository taskAttachments;
    @Autowired private TaskRepository tasks;
    @Autowired private RenderProperties renderProperties;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        questionAttachments.deleteAll();
        taskAttachments.deleteAll();
        sessionRepo.deleteAll();
        tasks.deleteAll();
    }

    /** setter는 package-private라 트랜잭션 프록시가 아닌 실제 빈에 건다. */
    private InterviewService target() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
    }

    @AfterEach
    void restoreWait() {
        target().setRenderProperties(renderProperties);
    }

    private InterviewSession savedQuestion() {
        return sessionRepo.save(InterviewSession.createQuestion("acme/widgets", "main", "제목", "질문?",
                "user1", List.of(), "claude-opus-5-5", "high"));
    }

    private QuestionAttachment pendingAttachment(long sid) {
        String rel = "question-" + sid + "/create/1-발표.pptx";
        return questionAttachments.save(QuestionAttachment.create(sid, null, "발표.pptx", rel, null, 10L,
                null, "user1", AttachmentRendition.of(RenderStatus.PENDING, null)));
    }

    private void ageQuestionAttachment(long id, int seconds) {
        jdbc.update("UPDATE com.question_attachment SET created_at = now() - (? * interval '1 second') WHERE id = ?",
                seconds, id);
    }

    @Test
    void 렌더링_중_첨부가_있으면_claim_보류() {
        InterviewSession s = savedQuestion();
        QuestionAttachment a = pendingAttachment(s.getId());

        assertThat(service.claim("w1")).isEmpty();

        ageQuestionAttachment(a.getId(), 181);
        Optional<InterviewClaimResponse> claimed = service.claim("w1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().sessionId()).isEqualTo(s.getId());
        assertThat(claimed.get().attachments()).hasSize(1);
        assertThat(claimed.get().attachments().get(0).renderNote()).isEqualTo("슬라이드 그림 준비 중(대기 시간 초과)");
        assertThat(claimed.get().attachments().get(0).slideImagePaths()).isEmpty();
    }

    @Test
    void 대기_시간이_0이면_바로_claim() {
        target().setRenderProperties(new RenderProperties(true, "", List.of("pptx"), 120, 30, 1600, 20, 0));
        InterviewSession s = savedQuestion();
        pendingAttachment(s.getId());

        Optional<InterviewClaimResponse> claimed = service.claim("w1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().attachments().get(0).renderNote()).isEqualTo("슬라이드 그림 준비 중(대기 시간 초과)");
    }

    @Test
    void 렌더링_대기_세션은_건너뛰고_다른_세션을_claim() {
        InterviewSession waiting = savedQuestion();
        pendingAttachment(waiting.getId());
        InterviewSession plain = savedQuestion();

        Optional<InterviewClaimResponse> claimed = service.claim("w1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().sessionId()).isEqualTo(plain.getId());
        assertThat(service.claim("w2")).isEmpty();
    }

    @Test
    void 작업_인터뷰도_소유_task의_렌더링_중_첨부를_기다린다() {
        Task t = tasks.save(Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null));
        InterviewSession s = InterviewSession.create("acme/widgets", "main", "제목", "설명", "user1",
                List.of(), null, null);
        s.setTaskId(t.getId());
        sessionRepo.save(s);
        TaskAttachment a = taskAttachments.save(TaskAttachment.create(t.getId(), "발표.pptx",
                "task-" + t.getId() + "/1-발표.pptx", null, 10L, "user1", null,
                AttachmentRendition.of(RenderStatus.RUNNING, null), null));

        assertThat(service.claim("w1")).isEmpty();

        jdbc.update("UPDATE com.task_attachment SET created_at = now() - interval '181 seconds' WHERE id = ?", a.getId());
        Optional<InterviewClaimResponse> claimed = service.claim("w1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().attachments().get(0).renderNote()).isEqualTo("슬라이드 그림 준비 중(대기 시간 초과)");
    }
}
