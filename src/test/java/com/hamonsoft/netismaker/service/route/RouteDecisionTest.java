package com.hamonsoft.netismaker.service.route;

import com.hamonsoft.netismaker.config.QuestionRouteProperties.Threshold;
import com.hamonsoft.netismaker.service.route.JevClient.JevAnswers;
import com.hamonsoft.netismaker.service.route.RouteDecision.Candidate;
import com.hamonsoft.netismaker.service.route.RouteDecision.Decision;
import com.hamonsoft.netismaker.service.route.RouteDecision.RouteState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDecisionTest {

    private static final Threshold T = new Threshold(0.7, 0.7, 0.3, 0.5, 0.6);

    private static JevAnswers answers(double mentions, double listed, double top, double needsDb) {
        return new JevAnswers("jev", Map.of(1L, top, 2L, 0.1), mentions, listed, needsDb);
    }

    @Test
    void 셋_다_넘으면_CONFIDENT이고_1위를_추천한다() {
        Decision d = RouteDecision.decide(answers(0.7, 0.7, 0.5, 0.0), T);
        assertThat(d.state()).isEqualTo(RouteState.CONFIDENT);
        assertThat(d.recommendedRepoCatalogId()).isEqualTo(1L);
    }

    @Test
    void 일위_확률이_모자라면_AMBIGUOUS() {
        Decision d = RouteDecision.decide(answers(0.9, 0.9, 0.49, 0.0), T);
        assertThat(d.state()).isEqualTo(RouteState.AMBIGUOUS);
        assertThat(d.recommendedRepoCatalogId()).isNull();
    }

    @Test
    void 가리키는_곳이_없으면_AMBIGUOUS() {
        Decision d = RouteDecision.decide(answers(0.69, 0.9, 0.9, 0.0), T);
        assertThat(d.state()).isEqualTo(RouteState.AMBIGUOUS);
        assertThat(d.recommendedRepoCatalogId()).isNull();
    }

    @Test
    void 가리키는데_목록에_없으면_NO_MATCH() {
        Decision d = RouteDecision.decide(answers(0.7, 0.3, 0.9, 0.0), T);
        assertThat(d.state()).isEqualTo(RouteState.NO_MATCH);
        assertThat(d.recommendedRepoCatalogId()).isNull();
        assertThat(d.candidates()).isNotEmpty();
    }

    @Test
    void 목록_여부가_어중간하면_AMBIGUOUS() {
        Decision d = RouteDecision.decide(answers(0.9, 0.5, 0.9, 0.0), T);
        assertThat(d.state()).isEqualTo(RouteState.AMBIGUOUS);
        assertThat(d.recommendedRepoCatalogId()).isNull();
    }

    @Test
    void 후보는_확률_내림차순_상위_5개다() {
        Map<Long, Double> p = Map.of(1L, 0.05, 2L, 0.30, 3L, 0.30, 4L, 0.10, 5L, 0.20, 6L, 0.02, 7L, 0.03);
        Decision d = RouteDecision.decide(new JevAnswers("jev", p, 0.9, 0.9, 0.0), T);
        assertThat(d.candidates()).extracting(Candidate::repoCatalogId).containsExactly(2L, 3L, 5L, 4L, 1L);
        assertThat(d.candidates().get(0).probability()).isEqualTo(0.30);
    }

    @Test
    void needsDb는_경계_포함으로_참이다() {
        assertThat(RouteDecision.decide(answers(0.9, 0.9, 0.9, 0.6), T).dbNeeded()).isTrue();
        assertThat(RouteDecision.decide(answers(0.9, 0.9, 0.9, 0.59), T).dbNeeded()).isFalse();
    }

    @Test
    void unavailable은_빈_후보와_null_dbNeeded() {
        Decision d = RouteDecision.unavailable();
        assertThat(d.state()).isEqualTo(RouteState.UNAVAILABLE);
        assertThat(d.recommendedRepoCatalogId()).isNull();
        assertThat(d.candidates()).isEqualTo(List.of());
        assertThat(d.dbNeeded()).isNull();
    }
}
