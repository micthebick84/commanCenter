package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

/**
 * 실패 사유 전달 계약: doFail이 만든 system note 턴을 터미널 status보다 **먼저** SSE로 밀어야 한다.
 * status=FAILED를 받은 프론트는 즉시 스트림을 닫으므로, 순서가 뒤집히면 사유가 영영 전달되지 않는다
 * (2026-09-22 라이브 검증에서 "답변 실패: 알 수 없는 오류"로 드러난 결함).
 */
class InterviewWorkerControllerFailTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, stream);

    @Test
    void fail_pushes_the_reason_note_before_the_terminal_status() {
        InterviewTurn note = InterviewTurn.of(1L, 0, "system", "note", "인터뷰 실패: OAuth 세션 만료", null);
        when(interviewService.failFromWorker(1L, "w1", "OAuth 세션 만료"))
                .thenReturn(new InterviewService.FailOutcome(null, note));

        controller.fail(1L, "w1", "OAuth 세션 만료");

        InOrder order = inOrder(stream);
        order.verify(stream).pushNote(1L, 0, "인터뷰 실패: OAuth 세션 만료");
        order.verify(stream).pushStatus(1L, InterviewStatus.FAILED);
        order.verify(stream).finish(1L);
    }
}
