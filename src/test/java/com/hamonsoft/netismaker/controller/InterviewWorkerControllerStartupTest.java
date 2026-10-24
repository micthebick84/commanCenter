package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewStartupResponse;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * POST /worker/interviews/startup — 기동 정합 결과를 SSE로 밀어 준다(스펙 2026-10-23 §3.3).
 * 노트가 상태보다 먼저(프론트는 FAILED를 받는 즉시 스트림을 닫는다), 세션마다 독립 try/catch.
 */
class InterviewWorkerControllerStartupTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, stream, mock(DbConnectionService.class));

    private static InterviewTurn note(long sid, int seq, String content) {
        return InterviewTurn.of(sid, seq, "system", "note", content, null);
    }

    @Test
    void pushes_note_and_queued_for_released() {
        when(interviewService.runningSessionIdsOf("iw-1")).thenReturn(List.of(1L));
        when(interviewService.releaseForRestart(1L, "iw-1")).thenReturn(Optional.of(
                new InterviewService.RestartOutcome(null, note(1L, 4, "재개"), true)));

        InterviewStartupResponse res = controller.startup("iw-1");

        InOrder order = inOrder(stream);
        order.verify(stream).pushNote(1L, 4, "재개");
        order.verify(stream).pushStatus(1L, InterviewStatus.QUEUED);
        verify(stream, never()).finish(any());
        assertThat(res.released()).containsExactly(1L);
        assertThat(res.failed()).isEmpty();
    }

    @Test
    void pushes_failed_and_finishes_for_failed() {
        when(interviewService.runningSessionIdsOf("iw-1")).thenReturn(List.of(2L));
        when(interviewService.releaseForRestart(2L, "iw-1")).thenReturn(Optional.of(
                new InterviewService.RestartOutcome(null, note(2L, 7, "인터뷰 실패: x"), false)));

        InterviewStartupResponse res = controller.startup("iw-1");

        InOrder order = inOrder(stream);
        order.verify(stream).pushNote(2L, 7, "인터뷰 실패: x");
        order.verify(stream).pushStatus(2L, InterviewStatus.FAILED);
        order.verify(stream).finish(2L);
        assertThat(res.released()).isEmpty();
        assertThat(res.failed()).containsExactly(2L);
    }

    @Test
    void one_session_error_does_not_stop_others() {
        when(interviewService.runningSessionIdsOf("iw-1")).thenReturn(List.of(1L, 2L, 3L));
        when(interviewService.releaseForRestart(1L, "iw-1")).thenThrow(new IllegalStateException("boom"));
        when(interviewService.releaseForRestart(2L, "iw-1")).thenReturn(Optional.empty());
        when(interviewService.releaseForRestart(3L, "iw-1")).thenReturn(Optional.of(
                new InterviewService.RestartOutcome(null, note(3L, 1, "재개"), true)));

        InterviewStartupResponse res = controller.startup("iw-1");

        verify(stream).pushNote(3L, 1, "재개");
        verify(stream).pushStatus(3L, InterviewStatus.QUEUED);
        verify(stream, never()).pushNote(eq(1L), anyInt(), any());
        verify(stream, never()).pushNote(eq(2L), anyInt(), any());
        assertThat(res.released()).containsExactly(3L);
        assertThat(res.failed()).isEmpty();
    }
}
