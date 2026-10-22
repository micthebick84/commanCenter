package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 추가 질문의 SSE 계약: 대화 중 MCP·DB·참조 repo 선택이 실제로 바뀌면 service가 돌려준 system note 턴을 status(QUEUED)
 * 다음에 note 이벤트로 밀어 프론트가 새로고침 없이 바로 보여 준다 (스펙 2026-09-13 §5.3, 2026-10-22 §4.2).
 */
class QuestionControllerAskNotesTest {

    private final QuestionService questionService = mock(QuestionService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final QuestionController controller = new QuestionController(questionService, stream);

    private static JwtAuthenticationToken user() {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1").build());
    }

    private static InterviewTurn note(int seq, String content) {
        return InterviewTurn.of(5L, seq, "system", "note", content, null);
    }

    @Test
    void ask_pushes_the_reference_note_after_the_status_and_the_mcp_and_db_notes() {
        QuestionAskRequest req = new QuestionAskRequest("추가", 3, null, null);
        when(questionService.ask(eq(5L), eq("user1"), eq(false), eq(req), isNull())).thenReturn(
                new QuestionService.AskResult(mock(InterviewSession.class),
                        note(5, "MCP 도구 변경: ctx7"), note(6, "DB 연결 변경: 분석 DB"),
                        note(7, "참조 repo 변경: ＋ 배치 서버(master)")));

        controller.ask(5L, req, user());

        InOrder order = inOrder(stream);
        order.verify(stream).pushStatus(5L, InterviewStatus.QUEUED);
        order.verify(stream).pushNote(5L, 5, "MCP 도구 변경: ctx7");
        order.verify(stream).pushNote(5L, 6, "DB 연결 변경: 분석 DB");
        order.verify(stream).pushNote(5L, 7, "참조 repo 변경: ＋ 배치 서버(master)");
    }

    @Test
    void ask_pushes_only_the_reference_note_when_only_the_references_changed() {
        QuestionAskRequest req = new QuestionAskRequest("추가", 3, null, null);
        when(questionService.ask(eq(5L), eq("user1"), eq(false), eq(req), isNull())).thenReturn(
                new QuestionService.AskResult(mock(InterviewSession.class), null, null,
                        note(7, "참조 repo 변경: 없음(전부 해제)")));

        controller.ask(5L, req, user());

        verify(stream).pushNote(5L, 7, "참조 repo 변경: 없음(전부 해제)");
        verify(stream, times(1)).pushNote(any(Long.class), anyInt(), anyString());
    }

    @Test
    void ask_pushes_no_note_when_nothing_changed() {
        QuestionAskRequest req = new QuestionAskRequest("추가", 3, null, null);
        when(questionService.ask(eq(5L), eq("user1"), eq(false), eq(req), isNull())).thenReturn(
                new QuestionService.AskResult(mock(InterviewSession.class), null));

        controller.ask(5L, req, user());

        verify(stream).pushStatus(5L, InterviewStatus.QUEUED);
        verify(stream, never()).pushNote(any(Long.class), anyInt(), anyString());
    }
}
