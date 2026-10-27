package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작업 인터뷰 답변의 참조 변경 (스펙 2026-10-24 §4.3) — 컨트롤러는 답변과 참조 변경을 한 트랜잭션인 서비스 래퍼에 맡기고,
 * 참조가 바뀌었으면 status(QUEUED) 다음에 시스템 메모를 note 이벤트로 민다(질문 ask와 같은 순서).
 */
class InterviewControllerAnswerRefsTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final InterviewController controller = new InterviewController(interviewService, stream);

    private static JwtAuthenticationToken admin() {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", "admin").build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static InterviewService.AnswerResult result(InterviewTurn refNote) {
        return new InterviewService.AnswerResult(
                new InterviewService.AnswerOutcome(mock(InterviewSession.class), 6), refNote);
    }

    @Test
    void answer_pushes_queued_then_the_ref_note() {
        AnswerRequest req = new AnswerRequest("답", 5, List.of(new RefRepoInput(12L, "master")));
        when(interviewService.submitAnswerWithRefs(1L, "admin", req)).thenReturn(
                result(InterviewTurn.of(1L, 7, "system", "note", "참조 repo 변경: ＋ 배치 서버(master)", null)));

        controller.answer(1L, req, admin());

        verify(interviewService).requireKind(1L, InterviewKind.INTERVIEW);
        InOrder o = inOrder(interviewService, stream);
        o.verify(interviewService).submitAnswerWithRefs(1L, "admin", req);
        o.verify(stream).pushStatus(1L, InterviewStatus.QUEUED);
        o.verify(stream).pushNote(1L, 7, "참조 repo 변경: ＋ 배치 서버(master)");
        o.verify(interviewService).getResponse(1L, "admin", true);
        verify(interviewService, never()).submitAnswer(any(), any(), any(Boolean.class), any());
    }

    @Test
    void answer_without_a_ref_change_pushes_no_note() {
        AnswerRequest req = new AnswerRequest("답", 5);
        when(interviewService.submitAnswerWithRefs(1L, "admin", req)).thenReturn(result(null));

        controller.answer(1L, req, admin());

        verify(stream).pushStatus(1L, InterviewStatus.QUEUED);
        verify(stream, never()).pushNote(any(Long.class), anyInt(), anyString());
    }
}
