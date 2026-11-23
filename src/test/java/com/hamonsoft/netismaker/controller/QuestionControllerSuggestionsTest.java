package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.SuggestionView;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import com.hamonsoft.netismaker.service.route.QuestionRouteService;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 질문 스냅샷의 제안은 질문 조회 트랜잭션 밖(컨트롤러)에서 붙고, 조회가 실패해도 응답은 나간다 (스펙 2026-11-22 §6.2). */
class QuestionControllerSuggestionsTest {

    private final QuestionService questionService = mock(QuestionService.class);
    private final QuestionSuggestService suggest = mock(QuestionSuggestService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);
    private final QuestionController controller = new QuestionController(questionService,
            stream, mock(QuestionRouteService.class), suggest);

    private static JwtAuthenticationToken user() {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1").build());
    }

    private static InterviewResponse snapshot() {
        InterviewSession s = InterviewSession.createQuestion("a/b", "main", "t", "q", "user1", List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(s, "id", 5L);
        return InterviewResponse.of(s, List.of(), null);
    }

    @Test
    void 질문_응답에_제안을_붙인다() {
        SuggestionView v = new SuggestionView(1, 2, "REF_REPO", "PENDING", "이유", List.of(), null, 11L, "acme/x", "main",
                List.of(), 3, 3);
        when(questionService.get(5L, "user1", false)).thenReturn(snapshot());
        when(suggest.views(5L)).thenReturn(List.of(v));

        assertThat(controller.get(5L, user()).suggestions()).containsExactly(v);
    }

    @Test
    void 제안_뷰_조회가_실패해도_질문_응답은_나간다() {
        when(questionService.get(5L, "user1", false)).thenReturn(snapshot());
        when(suggest.views(5L)).thenThrow(new RuntimeException("boom"));

        InterviewResponse r = controller.get(5L, user());

        assertThat(r.id()).isEqualTo(5L);
        assertThat(r.suggestions()).isEmpty();
    }

    private static SuggestionView view(long id, String status) {
        return new SuggestionView(id, 2, "REF_REPO", status, "이유", List.of(), null, 11L, "acme/x", "main", List.of(), 3, 3);
    }

    private static QuestionAskRequest askReq() {
        return new QuestionAskRequest("보완해 주세요", null, null, null, null, null, null, List.of(2L));
    }

    private static InterviewSession session() {
        return InterviewSession.createQuestion("a/b", "main", "t", "q", "user1", List.of(), "claude-opus-5", "high");
    }

    @Test
    void 닫기는_상태를_바꾼_뒤_뷰를_돌려주고_민다() {
        SuggestionView done = view(2, "DISMISSED");
        when(questionService.dismissSuggestion(5L, 2L, "user1", false)).thenReturn(2L);
        when(suggest.viewsOf(5L, List.of(2L))).thenReturn(List.of(done));

        assertThat(controller.dismissSuggestion(5L, 2L, user())).isEqualTo(done);

        var order = inOrder(questionService, suggest, stream);
        order.verify(questionService).dismissSuggestion(5L, 2L, "user1", false);
        order.verify(suggest).viewsOf(5L, List.of(2L));
        order.verify(stream).pushSuggestion(5L, done);
    }

    @Test
    void ask가_바꾼_제안을_민다() {
        SuggestionView v1 = view(1, "SUPERSEDED");
        SuggestionView v2 = view(2, "ACCEPTED");
        when(questionService.ask(5L, "user1", false, askReq(), null))
                .thenReturn(new QuestionService.AskResult(session(), null, null, null, List.of(1L, 2L)));
        when(questionService.get(5L, "user1", false)).thenReturn(snapshot());
        when(suggest.viewsOf(5L, List.of(1L, 2L))).thenReturn(List.of(v1, v2));

        controller.ask(5L, askReq(), user());

        verify(stream).pushSuggestion(5L, v1);
        verify(stream).pushSuggestion(5L, v2);
    }

    @Test
    void ask_뒤_제안_밀기가_실패해도_응답은_나간다() {
        when(questionService.ask(5L, "user1", false, askReq(), null))
                .thenReturn(new QuestionService.AskResult(session(), null, null, null, List.of(1L)));
        when(questionService.get(5L, "user1", false)).thenReturn(snapshot());
        when(suggest.viewsOf(5L, List.of(1L))).thenReturn(List.of(view(1, "SUPERSEDED")));
        doThrow(new RuntimeException("push")).when(stream).pushSuggestion(anyLong(), any());

        assertThatCode(() -> controller.ask(5L, askReq(), user())).doesNotThrowAnyException();
        assertThat(controller.ask(5L, askReq(), user()).id()).isEqualTo(5L);
    }
}
