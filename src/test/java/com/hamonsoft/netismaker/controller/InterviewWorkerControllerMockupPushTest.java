package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewPlan;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 보고 뒤 목업 push 순서 (스펙 2026-11-03 §5) — 턴 이벤트 → 그 세션의 목업 전부 → status.
 * 화면은 status 이벤트 때 묶이지 않은 목업을 지우므로, 묶인 목업이 status보다 먼저 가야 방금 묶인 목업이 지워지지 않는다.
 * 보고는 이미 커밋됐으므로 목업 조회가 실패해도 status는 보내고 204로 끝난다.
 */
class InterviewWorkerControllerMockupPushTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, stream, mock(DbConnectionService.class));

    private static final WorkerQuestionRequest QUESTION = new WorkerQuestionRequest("목업 1과 2 중 어느 쪽인가요?",
            "sess-1", "question", BigDecimal.ZERO, null, null, null, null, null, null);
    private static final WorkerPlanRequest PLAN = new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ZERO,
            null, null, null, null, 1000L);

    private static InterviewMockupSummary mockup(long id, int n, int turnSeq, String turnKind) {
        return new InterviewMockupSummary(id, n, "list-" + n, 1, "목록 " + n + "안", null, turnSeq, turnKind,
                OffsetDateTime.now());
    }

    private static InterviewPlan plan() {
        return InterviewPlan.create(1L, "# 설계", "# 플랜", "[]", 1000L, BigDecimal.ZERO);
    }

    @Test
    void 보고_push는_턴_뒤_status_앞에_목업() {
        InterviewMockupSummary a = mockup(10L, 1, 7, "question");
        InterviewMockupSummary b = mockup(11L, 2, 7, "question");
        when(interviewService.recordQuestion(1L, "w1", QUESTION))
                .thenReturn(InterviewTurn.of(1L, 7, "assistant", "question", QUESTION.content(), null));
        when(interviewService.mockupSummaries(1L)).thenReturn(List.of(a, b));

        controller.question(1L, "w1", QUESTION);

        InOrder order = inOrder(stream);
        order.verify(stream).pushQuestion(1L, 7, QUESTION.content());
        order.verify(stream).pushMockup(1L, a);
        order.verify(stream).pushMockup(1L, b);
        order.verify(stream).pushStatus(1L, InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void design_kind_질문_보고도_design_뒤_status_앞에_목업() {
        InterviewMockupSummary a = mockup(10L, 1, 7, "design");
        when(interviewService.recordQuestion(1L, "w1", QUESTION))
                .thenReturn(InterviewTurn.of(1L, 7, "assistant", "design", "# 설계 초안", null));
        when(interviewService.mockupSummaries(1L)).thenReturn(List.of(a));

        controller.question(1L, "w1", QUESTION);

        InOrder order = inOrder(stream);
        order.verify(stream).pushDesign(1L, 7, "# 설계 초안");
        order.verify(stream).pushMockup(1L, a);
        order.verify(stream).pushStatus(1L, InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void 계획_보고_push는_plan_ready_뒤_status_앞에_목업() {
        InterviewMockupSummary a = mockup(10L, 1, 9, "design");
        InterviewPlan p = plan();
        when(interviewService.recordPlan(1L, "w1", PLAN)).thenReturn(p);
        when(interviewService.mockupSummaries(1L)).thenReturn(List.of(a));

        controller.plan(1L, "w1", PLAN);

        InOrder order = inOrder(stream);
        order.verify(stream).pushPlanReady(1L, p.getDesignMarkdown(), p.getPlanMarkdown(), p.getPlanJson());
        order.verify(stream).pushMockup(1L, a);
        order.verify(stream).pushStatus(1L, InterviewStatus.PLAN_READY);
    }

    @Test
    void 목업이_없으면_pushMockup을_부르지_않는다() {
        when(interviewService.recordQuestion(1L, "w1", QUESTION))
                .thenReturn(InterviewTurn.of(1L, 7, "assistant", "question", QUESTION.content(), null));
        when(interviewService.recordPlan(1L, "w1", PLAN)).thenReturn(plan());
        when(interviewService.mockupSummaries(1L)).thenReturn(List.of());

        controller.question(1L, "w1", QUESTION);
        controller.plan(1L, "w1", PLAN);

        verify(stream, never()).pushMockup(anyLong(), any());
        verify(stream).pushStatus(1L, InterviewStatus.AWAITING_INPUT);
        verify(stream).pushStatus(1L, InterviewStatus.PLAN_READY);
    }

    @Test
    void 목업_조회가_던져도_status는_보낸다() {
        when(interviewService.recordQuestion(1L, "w1", QUESTION))
                .thenReturn(InterviewTurn.of(1L, 7, "assistant", "question", QUESTION.content(), null));
        when(interviewService.recordPlan(1L, "w1", PLAN)).thenReturn(plan());
        when(interviewService.mockupSummaries(1L)).thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatCode(() -> controller.question(1L, "w1", QUESTION)).doesNotThrowAnyException();
        assertThatCode(() -> controller.plan(1L, "w1", PLAN)).doesNotThrowAnyException();

        verify(stream).pushQuestion(1L, 7, QUESTION.content());
        verify(stream).pushStatus(1L, InterviewStatus.AWAITING_INPUT);
        verify(stream).pushStatus(1L, InterviewStatus.PLAN_READY);
        verify(stream, never()).pushMockup(anyLong(), any());
    }
}
