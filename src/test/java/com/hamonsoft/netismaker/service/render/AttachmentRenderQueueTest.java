package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.service.render.RenderJobRef.Kind;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SQL 없이 도는 큐 판단(TASK 먼저·회수 분기). SKIP LOCKED 자체는 AttachmentRenderQueueIntegrationTest(CI). */
class AttachmentRenderQueueTest {

    private final TaskAttachmentRepository taskRepo = mock(TaskAttachmentRepository.class);
    private final QuestionAttachmentRepository questionRepo = mock(QuestionAttachmentRepository.class);
    private final AttachmentRenderQueue queue = new AttachmentRenderQueue(taskRepo, questionRepo);

    private static TaskAttachment task(String rel, String name) {
        return TaskAttachment.create(1L, name, rel, null, 1L, "u", null,
                AttachmentRendition.of(RenderStatus.PENDING, null), null);
    }

    private static QuestionAttachment question(String rel, String name) {
        return QuestionAttachment.create(2L, null, name, rel, null, 1L, null, "u",
                AttachmentRendition.of(RenderStatus.PENDING, null));
    }

    @Test
    void claimNext는_작업_첨부를_먼저_잡고_RUNNING으로_바꾼다() {
        TaskAttachment t = task("task-1/1-a.pptx", "a.pptx");
        when(taskRepo.findFirstPendingIdForUpdateSkipLocked()).thenReturn(Optional.of(10L));
        when(taskRepo.findById(10L)).thenReturn(Optional.of(t));

        Optional<RenderJobRef> ref = queue.claimNext();

        assertThat(ref).contains(new RenderJobRef(Kind.TASK, 10L, "task-1/1-a.pptx", "a.pptx"));
        assertThat(t.getRendition().getStatus()).isEqualTo(RenderStatus.RUNNING);
        assertThat(t.getRendition().getAttempts()).isEqualTo(1);
        assertThat(t.getRendition().getStartedAt()).isNotNull();
        verify(questionRepo, never()).findFirstPendingIdForUpdateSkipLocked();
    }

    @Test
    void claimNext는_작업_첨부가_없으면_질문_첨부를_잡는다() {
        QuestionAttachment q = question("question-2/create/1-b.pptx", "b.pptx");
        when(taskRepo.findFirstPendingIdForUpdateSkipLocked()).thenReturn(Optional.empty());
        when(questionRepo.findFirstPendingIdForUpdateSkipLocked()).thenReturn(Optional.of(20L));
        when(questionRepo.findById(20L)).thenReturn(Optional.of(q));

        Optional<RenderJobRef> ref = queue.claimNext();

        assertThat(ref).contains(new RenderJobRef(Kind.QUESTION, 20L, "question-2/create/1-b.pptx", "b.pptx"));
        assertThat(q.getRendition().getStatus()).isEqualTo(RenderStatus.RUNNING);
    }

    @Test
    void claimNext는_둘_다_없으면_empty() {
        when(taskRepo.findFirstPendingIdForUpdateSkipLocked()).thenReturn(Optional.empty());
        when(questionRepo.findFirstPendingIdForUpdateSkipLocked()).thenReturn(Optional.empty());
        assertThat(queue.claimNext()).isEmpty();
    }

    @Test
    void complete는_DONE과_숫자를_기록한다() {
        TaskAttachment t = task("task-1/1-a.pptx", "a.pptx");
        t.getRendition().markRunning(OffsetDateTime.now());
        when(taskRepo.findById(10L)).thenReturn(Optional.of(t));

        queue.complete(new RenderJobRef(Kind.TASK, 10L, "task-1/1-a.pptx", "a.pptx"),
                "task-1/1-a.pptx.render", new RenderResult(3, 40, 2));

        AttachmentRendition r = t.getRendition();
        assertThat(r.getStatus()).isEqualTo(RenderStatus.DONE);
        assertThat(r.getDir()).isEqualTo("task-1/1-a.pptx.render");
        assertThat(r.getPageCount()).isEqualTo(3);
        assertThat(r.getTotalPages()).isEqualTo(40);
        assertThat(r.getMediaCount()).isEqualTo(2);
    }

    @Test
    void fail은_질문_첨부에_사유를_기록한다() {
        QuestionAttachment q = question("question-2/create/1-b.pptx", "b.pptx");
        q.getRendition().markRunning(OffsetDateTime.now());
        when(questionRepo.findById(20L)).thenReturn(Optional.of(q));

        queue.fail(new RenderJobRef(Kind.QUESTION, 20L, "question-2/create/1-b.pptx", "b.pptx"), "PDF 변환 실패");

        assertThat(q.getRendition().getStatus()).isEqualTo(RenderStatus.FAILED);
        assertThat(q.getRendition().getError()).isEqualTo("PDF 변환 실패");
    }

    @Test
    void 행이_사라졌으면_complete와_fail은_아무것도_안_한다() {
        when(taskRepo.findById(any())).thenReturn(Optional.empty());
        RenderJobRef ref = new RenderJobRef(Kind.TASK, 99L, "task-1/1-a.pptx", "a.pptx");
        queue.complete(ref, "x.render", new RenderResult(1, 1, 0));
        queue.fail(ref, "사유");
    }

    @Test
    void recoverStale은_첫_시도면_PENDING_두_번째부터_FAILED() {
        OffsetDateTime cutoff = OffsetDateTime.now();
        TaskAttachment once = task("task-1/1-a.pptx", "a.pptx");
        once.getRendition().markRunning(cutoff.minusHours(1));
        TaskAttachment twice = task("task-1/2-b.pptx", "b.pptx");
        twice.getRendition().markRunning(cutoff.minusHours(2));
        twice.getRendition().markRunning(cutoff.minusHours(1));
        QuestionAttachment qOnce = question("question-2/create/1-c.pptx", "c.pptx");
        qOnce.getRendition().markRunning(cutoff.minusHours(1));
        when(taskRepo.findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus.RUNNING, cutoff))
                .thenReturn(List.of(once, twice));
        when(questionRepo.findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus.RUNNING, cutoff))
                .thenReturn(List.of(qOnce));

        int n = queue.recoverStale(cutoff);

        assertThat(n).isEqualTo(3);
        assertThat(once.getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
        assertThat(once.getRendition().getStartedAt()).isNull();
        assertThat(twice.getRendition().getStatus()).isEqualTo(RenderStatus.FAILED);
        assertThat(twice.getRendition().getError()).isEqualTo("렌더링 중단(재기동)");
        assertThat(qOnce.getRendition().getStatus()).isEqualTo(RenderStatus.PENDING);
    }
}
