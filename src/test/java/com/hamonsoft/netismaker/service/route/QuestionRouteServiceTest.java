package com.hamonsoft.netismaker.service.route;

import com.hamonsoft.netismaker.config.QuestionRouteProperties;
import com.hamonsoft.netismaker.dto.QuestionRouteResponse;
import com.hamonsoft.netismaker.entity.QuestionRouteLog;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.QuestionRouteLogRepository;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionRouteServiceTest {

    private final RepoCatalogService catalog = mock(RepoCatalogService.class);
    private final JevClient jev = mock(JevClient.class);
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final QuestionRouteLogRepository logs = mock(QuestionRouteLogRepository.class);
    private final QuestionRouteProperties props = new QuestionRouteProperties(
            true, "key", "jev-1.14.0", "http://x", 3000, 500, new QuestionRouteProperties.Threshold(0.7, 0.7, 0.3, 0.5, 0.6));
    private QuestionRouteService service;

    @BeforeEach
    void setUp() {
        service = new QuestionRouteService(catalog, jev, sessions, logs, props);
        when(jev.available()).thenReturn(true);
        when(logs.save(any())).thenAnswer(inv -> {
            QuestionRouteLog l = inv.getArgument(0);
            ReflectionTestUtils.setField(l, "id", 77L);
            return l;
        });
        routable(3L, 8L, 11L);
        when(sessions.findRecentQuestionRepoCatalogIds(anyString(), any(Pageable.class))).thenReturn(List.of());
    }

    private void routable(Long... ids) {
        List<RepoCatalogEntry> list = new ArrayList<>();
        for (Long id : ids) {
            RepoCatalogEntry e = RepoCatalogEntry.create("별칭" + id, "https://github.com/a/r" + id + ".git",
                    "github", "a/r" + id, "main", "설명" + id, "admin");
            ReflectionTestUtils.setField(e, "id", id);
            e.setRouteHint("키워드" + id);
            list.add(e);
        }
        when(catalog.listRoutable()).thenReturn(list);
    }

    private static JevClient.JevAnswers answers(Map<Long, Double> probs) {
        return new JevClient.JevAnswers("jev-1.14.0", probs, 0.9, 0.9, 0.2);
    }

    private QuestionRouteLog savedLog() {
        ArgumentCaptor<QuestionRouteLog> c = ArgumentCaptor.forClass(QuestionRouteLog.class);
        verify(logs).save(c.capture());
        return c.getValue();
    }

    @Test
    void 추천이_있으면_CONFIDENT와_후보_최근_등록가능_목록을_돌려주고_기록한다() {
        when(sessions.findRecentQuestionRepoCatalogIds(anyString(), any(Pageable.class))).thenReturn(List.of(8L, 99L, 3L));
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.of(answers(Map.of(8L, 0.8, 3L, 0.1))));

        QuestionRouteResponse r = service.route("질문입니다", "user1");

        assertThat(r.state()).isEqualTo("CONFIDENT");
        assertThat(r.routeId()).isEqualTo(77L);
        assertThat(r.recommendedRepoCatalogId()).isEqualTo(8L);
        assertThat(r.candidates()).extracting(RouteDecision.Candidate::repoCatalogId).containsExactly(8L, 3L);
        assertThat(r.recentRepoCatalogIds()).containsExactly(8L, 3L);
        assertThat(r.eligibleRepoCatalogIds()).containsExactly(3L, 8L, 11L);
        assertThat(r.dbNeeded()).isFalse();
        QuestionRouteLog l = savedLog();
        assertThat(l.getState()).isEqualTo("CONFIDENT");
        assertThat(l.getModel()).isEqualTo("jev-1.14.0");
        assertThat(l.getRecommendedRepoCatalogId()).isEqualTo(8L);
        assertThat(l.getLatencyMs()).isNotNull();
        assertThat(l.getUserId()).isEqualTo("user1");
    }

    @Test
    void 클라이언트를_쓸_수_없으면_UNAVAILABLE이고_Jev를_부르지_않지만_기록은_남긴다() {
        when(jev.available()).thenReturn(false);
        when(sessions.findRecentQuestionRepoCatalogIds(anyString(), any(Pageable.class))).thenReturn(List.of(8L));

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.state()).isEqualTo("UNAVAILABLE");
        assertThat(r.candidates()).isEmpty();
        assertThat(r.dbNeeded()).isNull();
        assertThat(r.recentRepoCatalogIds()).containsExactly(8L);
        assertThat(r.eligibleRepoCatalogIds()).containsExactly(3L, 8L, 11L);
        verify(jev, never()).ask(anyString(), anyList());
        QuestionRouteLog l = savedLog();
        assertThat(l.getModel()).isNull();
        assertThat(l.getMentionsTarget()).isNull();
        assertThat(l.getTargetListed()).isNull();
        assertThat(l.getNeedsDb()).isNull();
        assertThat(l.getLatencyMs()).isNull();
    }

    @Test
    void Jev가_빈_값이면_UNAVAILABLE() {
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.empty());

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.state()).isEqualTo("UNAVAILABLE");
        assertThat(r.candidates()).isEmpty();
        assertThat(r.dbNeeded()).isNull();
        assertThat(savedLog().getLatencyMs()).isNotNull();
    }

    @Test
    void 등록_가능한_레포가_없으면_UNAVAILABLE이고_Jev를_부르지_않는다() {
        when(catalog.listRoutable()).thenReturn(List.of());

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.state()).isEqualTo("UNAVAILABLE");
        assertThat(r.eligibleRepoCatalogIds()).isEmpty();
        verify(jev, never()).ask(anyString(), anyList());
    }

    @Test
    void Jev에는_발췌와_별칭_키워드만_넘긴다() {
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.empty());

        service.route("가".repeat(600), "user1");

        ArgumentCaptor<String> ex = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JevClient.RepoOption>> repos = ArgumentCaptor.forClass(List.class);
        verify(jev).ask(ex.capture(), repos.capture());
        assertThat(ex.getValue().codePointCount(0, ex.getValue().length())).isEqualTo(500);
        assertThat(repos.getValue()).containsExactly(
                new JevClient.RepoOption(3L, "별칭3", "키워드3"),
                new JevClient.RepoOption(8L, "별칭8", "키워드8"),
                new JevClient.RepoOption(11L, "별칭11", "키워드11"));
    }

    @Test
    void 기록의_발췌는_Jev에_보낸_것과_같다() {
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.empty());

        service.route("  앞 \n\t 뒤  " + "x".repeat(700), "user1");

        ArgumentCaptor<String> ex = ArgumentCaptor.forClass(String.class);
        verify(jev).ask(ex.capture(), anyList());
        assertThat(savedLog().getQuestionExcerpt()).isEqualTo(ex.getValue());
        assertThat(ex.getValue()).startsWith("앞 뒤 x");
    }

    @Test
    void excerpt는_공백을_한_칸으로_줄이고_앞에서_자른다() {
        assertThat(QuestionRouteService.excerpt("a \n\t b", 500)).isEqualTo("a b");
        assertThat(QuestionRouteService.excerpt("a".repeat(600), 500)).hasSize(500);
        assertThat(QuestionRouteService.excerpt("a 　b", 500)).isEqualTo("a b");
        assertThat(QuestionRouteService.excerpt("   ", 500)).isEmpty();
    }

    @Test
    void excerpt는_서로게이트_쌍_가운데서_자르지_않는다() {
        String s = QuestionRouteService.excerpt("a".repeat(499) + "😀" + "x", 500);
        assertThat(s.codePointCount(0, s.length())).isEqualTo(500);
        assertThat(s).endsWith("😀");
    }

    @Test
    void 후보와_추천은_등록_가능한_id만_담는다() {
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.of(answers(Map.of(99L, 0.9, 3L, 0.05))));

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.candidates()).extracting(RouteDecision.Candidate::repoCatalogId).containsExactly(3L);
        assertThat(r.recommendedRepoCatalogId()).isNull();
        assertThat(r.state()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void 기록_저장이_실패해도_응답하고_routeId는_null이다() {
        when(jev.ask(anyString(), anyList())).thenReturn(Optional.of(answers(Map.of(8L, 0.8))));
        doThrow(new RuntimeException("db down")).when(logs).save(any());

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.routeId()).isNull();
        assertThat(r.state()).isEqualTo("CONFIDENT");
    }

    @Test
    void Jev_호출이_뜻밖의_예외를_던져도_UNAVAILABLE이고_기록은_남는다() {
        when(jev.ask(anyString(), anyList())).thenThrow(new IllegalStateException("boom"));

        QuestionRouteResponse r = service.route("질문", "user1");

        assertThat(r.state()).isEqualTo("UNAVAILABLE");
        assertThat(r.routeId()).isNotNull();
        verify(logs).save(any());
    }

    private QuestionRouteLog existingLog(Long id, String userId) {
        QuestionRouteLog l = QuestionRouteLog.of(userId, "발췌", RouteDecision.unavailable(), null, null);
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }

    @Test
    void linkChoice는_고른_레포와_세션과_시각을_적는다() {
        QuestionRouteLog l = existingLog(77L, "u1");
        when(logs.findById(77L)).thenReturn(Optional.of(l));
        service.linkChoice(77L, "u1", 8L, 500L);
        assertThat(l.getChosenRepoCatalogId()).isEqualTo(8L);
        assertThat(l.getSessionId()).isEqualTo(500L);
        assertThat(l.getChosenAt()).isNotNull();
    }

    @Test
    void linkChoice는_routeId가_null이면_저장소를_건드리지_않는다() {
        service.linkChoice(null, "u1", 8L, 500L);
        org.mockito.Mockito.verifyNoInteractions(logs);
    }

    @Test
    void linkChoice는_없는_기록과_남의_기록을_조용히_무시한다() {
        when(logs.findById(1L)).thenReturn(Optional.empty());
        QuestionRouteLog other = existingLog(2L, "someone-else");
        when(logs.findById(2L)).thenReturn(Optional.of(other));
        service.linkChoice(1L, "u1", 8L, 500L);
        service.linkChoice(2L, "u1", 8L, 500L);
        assertThat(other.getChosenAt()).isNull();
        assertThat(other.getChosenRepoCatalogId()).isNull();
    }

    @Test
    void linkChoice는_이미_선택된_기록을_덮지_않는다() {
        QuestionRouteLog l = existingLog(77L, "u1");
        when(logs.findById(77L)).thenReturn(Optional.of(l));
        service.linkChoice(77L, "u1", 8L, 500L);
        service.linkChoice(77L, "u1", 3L, 501L);
        assertThat(l.getChosenRepoCatalogId()).isEqualTo(8L);
        assertThat(l.getSessionId()).isEqualTo(500L);
    }

    @Test
    void linkChoice는_저장소가_예외를_던져도_밖으로_내지_않는다() {
        when(logs.findById(org.mockito.ArgumentMatchers.anyLong())).thenThrow(new IllegalStateException("db down"));
        org.assertj.core.api.Assertions.assertThatCode(() -> service.linkChoice(77L, "u1", 8L, 500L))
                .doesNotThrowAnyException();
    }
}
