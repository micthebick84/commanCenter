package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.SuggestionView;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 답변 보고 뒤 제안 push — 그 턴의 제안만, status 앞에, 실패해도 답변 전달은 막지 않는다. */
class InterviewWorkerControllerSuggestPushTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final QuestionSuggestService suggest = mock(QuestionSuggestService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, stream, mock(DbConnectionService.class), suggest);

    private static SuggestionView view(long id, int turnSeq) {
        return new SuggestionView(id, turnSeq, "REF_REPO", "PENDING", "이유", List.of(), null, 11L, "acme/x", "main",
                List.of(), 3, 3);
    }

    private static WorkerQuestionRequest req(List<WorkerQuestionRequest.Suggestion> s) {
        return new WorkerQuestionRequest("답", "sess", "question", BigDecimal.ZERO, null, null, null, null, null, null, null, s);
    }

    private InterviewTurn turn() {
        InterviewTurn t = mock(InterviewTurn.class);
        when(t.getSeq()).thenReturn(4);
        when(t.getKind()).thenReturn("question");
        when(t.getContent()).thenReturn("답");
        return t;
    }

    @Test
    void 제안이_있으면_답변_커밋_뒤에_저장한다() {
        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 11L, "이유", null));
        var r = req(sug);
        InterviewTurn t = turn();
        when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);

        controller.question(9L, "w1", r);

        var order = inOrder(interviewService, suggest, stream);
        order.verify(interviewService).recordQuestion(9L, "w1", r);
        order.verify(suggest).record(9L, 4, sug);
        order.verify(stream).pushQuestion(9L, 4, "답");
        order.verify(stream).pushStatus(9L, InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void 제안_저장이_실패해도_질문과_상태_이벤트는_나간다() {
        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 11L, "이유", null));
        var r = req(sug);
        InterviewTurn t = turn();
        when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);
        when(suggest.record(9L, 4, sug)).thenThrow(new RuntimeException("boom"));

        controller.question(9L, "w1", r);

        verify(stream).pushQuestion(9L, 4, "답");
        verify(stream).pushStatus(9L, InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void 제안이_없으면_저장을_부르지_않는다() {
        for (var none : java.util.Arrays.<List<WorkerQuestionRequest.Suggestion>>asList(null, List.of())) {
            var r = req(none);
            InterviewTurn t = turn();
            when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);

            controller.question(9L, "w1", r);
        }

        verify(suggest, never()).record(anyLong(), anyInt(), any());
    }

    @Test
    void 그_턴의_제안만_status_앞에_민다() {
        var sug = List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 11L, "이유", null));
        var r = req(sug);
        InterviewTurn t = turn();
        when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);
        when(suggest.views(9L)).thenReturn(List.of(view(1, 2), view(2, 4)));

        controller.question(9L, "w1", r);

        var order = inOrder(stream);
        order.verify(stream).pushSuggestion(eq(9L), eq(view(2, 4)));
        order.verify(stream).pushStatus(9L, InterviewStatus.AWAITING_INPUT);
        verify(stream, never()).pushSuggestion(eq(9L), eq(view(1, 2)));
    }

    @Test
    void 제안_조회가_실패해도_status는_보낸다() {
        var r = req(List.of(new WorkerQuestionRequest.Suggestion("REF_REPO", null, 11L, "이유", null)));
        InterviewTurn t = turn();
        when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);
        when(suggest.views(9L)).thenThrow(new RuntimeException("boom"));

        controller.question(9L, "w1", r);

        verify(stream).pushStatus(9L, InterviewStatus.AWAITING_INPUT);
        verify(stream, never()).pushSuggestion(any(), any());
    }

    @Test
    void 제안이_없는_보고는_제안을_조회하지_않는다() {
        var r = req(null);
        InterviewTurn t = turn();
        when(interviewService.recordQuestion(9L, "w1", r)).thenReturn(t);

        controller.question(9L, "w1", r);

        verifyNoInteractions(suggest);
    }
}
