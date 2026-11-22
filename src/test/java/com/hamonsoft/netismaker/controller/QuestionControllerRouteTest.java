package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.QuestionRouteRequest;
import com.hamonsoft.netismaker.dto.QuestionRouteResponse;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import com.hamonsoft.netismaker.service.route.QuestionRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionControllerRouteTest {

    private final QuestionRouteService routeService = mock(QuestionRouteService.class);
    private final QuestionController controller = new QuestionController(
            mock(QuestionService.class), mock(InterviewStreamService.class), routeService,
            mock(com.hamonsoft.netismaker.service.suggest.QuestionSuggestService.class));

    @Test
    void route는_사용자_id로_서비스를_부르고_응답을_그대로_돌려준다() {
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1").build());
        QuestionRouteResponse expected = new QuestionRouteResponse(5L, "CONFIDENT", 8L, List.of(), List.of(8L), List.of(3L, 8L), false);
        when(routeService.route("질문", "user1")).thenReturn(expected);

        QuestionRouteResponse got = controller.route(new QuestionRouteRequest("질문"), auth);

        assertThat(got).isSameAs(expected);
        verify(routeService).route("질문", "user1");
    }
}
