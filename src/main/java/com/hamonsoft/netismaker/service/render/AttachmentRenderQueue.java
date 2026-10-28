package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.entity.AttachmentRendition;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RenderStatus;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.service.render.RenderJobRef.Kind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 렌더링 큐 = 두 첨부 테이블의 render_* 칸 (스펙 2026-10-27 §5.2·§6 — 별도 큐 테이블 없음). 메서드마다 짧은 트랜잭션이고
 * 변환 자체는 호출자(AttachmentRenderJob)가 트랜잭션 밖에서 한다. claim은 질문 첨부 먼저(질문 세션은 등록 즉시 시작해 렌더링을 최대 claim-wait만 기다리고 작업 인터뷰는 승인 뒤에야 시작한다), 없으면 작업 첨부.
 */
@Slf4j
@Component
@Profile("api")
public class AttachmentRenderQueue {

    static final String STALE_REASON = "렌더링 중단(재기동)";

    private final TaskAttachmentRepository taskAttachments;
    private final QuestionAttachmentRepository questionAttachments;

    public AttachmentRenderQueue(TaskAttachmentRepository taskAttachments,
                                 QuestionAttachmentRepository questionAttachments) {
        this.taskAttachments = taskAttachments;
        this.questionAttachments = questionAttachments;
    }

    /** PENDING 1건을 SKIP LOCKED로 잡아 RUNNING(시도 +1)으로 바꾼다. 커밋되면 다른 claim은 이 행을 못 본다. */
    @Transactional
    public Optional<RenderJobRef> claimNext() {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<Long> questionId = questionAttachments.findFirstPendingIdForUpdateSkipLocked();
        if (questionId.isPresent()) {
            Optional<QuestionAttachment> a = questionAttachments.findById(questionId.get());
            if (a.isPresent()) {
                a.get().getRendition().markRunning(now);
                return Optional.of(new RenderJobRef(Kind.QUESTION, questionId.get(),
                        a.get().getStoredPath(), a.get().getOriginalFilename()));
            }
        }
        Optional<Long> taskId = taskAttachments.findFirstPendingIdForUpdateSkipLocked();
        if (taskId.isPresent()) {
            Optional<TaskAttachment> a = taskAttachments.findById(taskId.get());
            if (a.isPresent()) {
                a.get().getRendition().markRunning(now);
                return Optional.of(new RenderJobRef(Kind.TASK, taskId.get(),
                        a.get().getStoredPath(), a.get().getOriginalFilename()));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void complete(RenderJobRef ref, String renderDirRel, RenderResult result) {
        rendition(ref).ifPresent(r ->
                r.markDone(renderDirRel, result.pageCount(), result.totalPages(), result.mediaCount()));
    }

    @Transactional
    public void fail(RenderJobRef ref, String reason) {
        rendition(ref).ifPresent(r -> r.markFailed(reason));
    }

    /**
     * cutoff보다 먼저 시작한 RUNNING을 회수한다 — 첫 시도(attempts 1)는 PENDING으로 되돌려 한 번 더,
     * 두 번째부터는 FAILED {@value #STALE_REASON}(같은 파일이 매번 프로세스를 죽이는 경우 무한 반복 방지). 회수한 행 수.
     */
    @Transactional
    public int recoverStale(OffsetDateTime cutoff) {
        int n = 0;
        for (TaskAttachment a : taskAttachments.findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus.RUNNING, cutoff)) {
            recover(a.getRendition());
            n++;
        }
        for (QuestionAttachment a : questionAttachments.findByRenditionStatusAndRenditionStartedAtBefore(RenderStatus.RUNNING, cutoff)) {
            recover(a.getRendition());
            n++;
        }
        if (n > 0) log.info("슬라이드 렌더링: 멈춘 RUNNING {}건 회수", n);
        return n;
    }

    private static void recover(AttachmentRendition r) {
        if (r.getAttempts() <= 1) {
            r.resetToPending();
        } else {
            r.markFailed(STALE_REASON);
        }
    }

    /** 행이 사라졌으면(테스트 정리·향후 삭제) 조용히 넘어간다 — 결과를 기록할 곳이 없다. */
    private Optional<AttachmentRendition> rendition(RenderJobRef ref) {
        Optional<AttachmentRendition> r = switch (ref.kind()) {
            case TASK -> taskAttachments.findById(ref.attachmentId()).map(TaskAttachment::getRendition);
            case QUESTION -> questionAttachments.findById(ref.attachmentId()).map(QuestionAttachment::getRendition);
        };
        if (r.isEmpty()) log.warn("슬라이드 렌더링: 첨부 행 없음 — 결과 버림: {} #{}", ref.kind(), ref.attachmentId());
        return r;
    }
}
