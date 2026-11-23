package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.SuggestionView;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest.Suggestion;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionSuggestion;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.SuggestionKind;
import com.hamonsoft.netismaker.entity.SuggestionStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionSuggestionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.Candidates;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.RefCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 러너가 답변과 함께 보고한 제안의 정규화·저장과 화면 뷰 (스펙 2026-11-22 §6.2). */
class QuestionSuggestServiceRecordTest {

    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final QuestionSuggestCandidates candidates = mock(QuestionSuggestCandidates.class);
    private final QuestionSuggestionRepository store = mock(QuestionSuggestionRepository.class);
    private final RepoCatalogRepository catalog = mock(RepoCatalogRepository.class);
    private final QuestionSuggestService service =
            new QuestionSuggestService(sessions, turns, candidates, store, catalog, true, 10);

    private InterviewSession session;

    @BeforeEach
    void setUp() {
        session = InterviewSession.createQuestion("acme/x", "main", "t", "q?", "user1", List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(session, "id", 5L);
        when(store.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sessions.findById(5L)).thenReturn(Optional.of(session));
        when(candidates.forSession(session)).thenReturn(cands(3));
    }

    private static Candidates cands(int refSlots) {
        return new Candidates(List.of(new DbConnectionDto.Chip(4L, "기본 DB", "MARIADB")),
                List.of(new RefCandidate(11L, "Netis7.0", "7.0", "main"), new RefCandidate(12L, "acme/y", null, "dev"),
                        new RefCandidate(13L, "acme/z", null, "main")), 2, refSlots);
    }

    private static Suggestion db(Long connId, String reason, List<String> targets) {
        return new Suggestion("DB", connId, null, reason, targets);
    }

    private static Suggestion ref(Long catalogId, String reason) {
        return new Suggestion("REF_REPO", null, catalogId, reason, null);
    }

    @Test
    void 후보에_있는_DB_제안을_저장한다() {
        List<QuestionSuggestion> saved = service.record(5L, 2, List.of(db(4L, "주문 테이블이 필요합니다", List.of("orders"))));

        assertThat(saved).hasSize(1);
        QuestionSuggestion q = saved.get(0);
        assertThat(q.getKind()).isEqualTo(SuggestionKind.DB);
        assertThat(q.getSessionId()).isEqualTo(5L);
        assertThat(q.getTurnSeq()).isEqualTo(2);
        assertThat(q.getDbConnectionId()).isEqualTo(4L);
        assertThat(q.getTargets()).containsExactly("orders");
        assertThat(q.getStatus()).isEqualTo(SuggestionStatus.PENDING);
    }

    @Test
    void DB_제안이_고른_연결이_후보에_없으면_연결_없는_제안으로_받는다() {
        List<QuestionSuggestion> saved = service.record(5L, 2, List.of(db(99L, "이유", List.of("orders"))));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDbConnectionId()).isNull();
    }

    @Test
    void DB_후보가_없으면_DB_제안을_버린다() {
        when(candidates.forSession(session)).thenReturn(new Candidates(List.of(),
                List.of(new RefCandidate(11L, "Netis7.0", null, "main")), 3, 3));

        assertThat(service.record(5L, 1, List.of(db(4L, "이유", List.of())))).isEmpty();
        verify(store, never()).save(any());
    }

    @Test
    void 한_턴에_DB_제안은_첫_1개만_받는다() {
        List<QuestionSuggestion> saved = service.record(5L, 1,
                List.of(db(4L, "첫째", List.of()), db(4L, "둘째", List.of())));

        assertThat(saved).extracting(QuestionSuggestion::getReason).containsExactly("첫째");
    }

    @Test
    void 후보에_없는_레포의_참조_제안은_버린다() {
        assertThat(service.record(5L, 1, List.of(ref(99L, "이유"), ref(null, "이유")))).isEmpty();
    }

    @Test
    void 참조_제안은_2개까지이고_같은_레포는_한_번만_받는다() {
        List<QuestionSuggestion> saved = service.record(5L, 1,
                List.of(ref(11L, "a"), ref(11L, "중복"), ref(12L, "b"), ref(13L, "c")));

        assertThat(saved).extracting(QuestionSuggestion::getRepoCatalogId).containsExactly(11L, 12L);
    }

    @Test
    void 참조_자리가_1개_남았으면_1개만_받는다() {
        when(candidates.forSession(session)).thenReturn(cands(1));

        List<QuestionSuggestion> saved = service.record(5L, 1, List.of(ref(11L, "a"), ref(12L, "b")));

        assertThat(saved).extracting(QuestionSuggestion::getRepoCatalogId).containsExactly(11L);
    }

    @Test
    void 이유가_비면_버리고_200자를_넘으면_자른다() {
        String emoji = "😀".repeat(250); // 코드 포인트로 자르므로 서로게이트 쌍이 갈라지지 않는다
        List<QuestionSuggestion> saved = service.record(5L, 1,
                List.of(ref(11L, "   "), ref(12L, null), ref(13L, "  " + emoji + "  ")));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getReason()).isEqualTo("😀".repeat(200));
    }

    @Test
    void 이유와_대상의_토큰_모양_문자열을_가린다() {
        String leak = "https://oauth2:abc123@host/x";
        List<QuestionSuggestion> saved = service.record(5L, 1, List.of(db(4L, "원격 " + leak, List.of(leak))));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getReason()).contains("://***@").doesNotContain("abc123");
        assertThat(saved.get(0).getTargets().get(0)).contains("://***@").doesNotContain("abc123");
    }

    @Test
    void 대상은_5개_80자까지다() {
        List<String> targets = new ArrayList<>(List.of("t1", " ", "t2", "t3", "t4", "t5", "t6", "x".repeat(90)));
        List<QuestionSuggestion> saved = service.record(5L, 1, List.of(db(4L, "이유", targets)));

        assertThat(saved.get(0).getTargets()).containsExactly("t1", "t2", "t3", "t4", "t5");

        List<QuestionSuggestion> long80 = service.record(5L, 1, List.of(db(4L, "이유", List.of("x".repeat(90)))));
        assertThat(long80.get(0).getTargets().get(0)).hasSize(80);
    }

    @Test
    void 질문_세션이_아니면_아무것도_저장하지_않는다() {
        InterviewSession interview = InterviewSession.create("acme/x", "main", "t", "d", "user1", List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(interview, "id", 6L);
        when(sessions.findById(6L)).thenReturn(Optional.of(interview));

        assertThat(service.record(6L, 1, List.of(ref(11L, "이유")))).isEmpty();
        assertThat(service.record(5L, 1, null)).isEmpty();
        assertThat(service.record(5L, 1, List.of())).isEmpty();
        verify(store, never()).save(any());
        verifyNoInteractions(candidates);
    }

    @Test
    void 세션이_없으면_아무것도_저장하지_않는다() {
        when(sessions.findById(77L)).thenReturn(Optional.empty());

        assertThat(service.record(77L, 1, List.of(ref(11L, "이유")))).isEmpty();
        verify(store, never()).save(any());
        verifyNoInteractions(candidates);
    }

    @Test
    void 제안_저장은_세션의_연결과_참조를_바꾸지_않는다() {
        session.setDbConnectionIds(new ArrayList<>(List.of(9L)));
        session.setRefRepos(new ArrayList<>(List.of(new RefRepo(21L, "붙은참조", "acme/att",
                "https://x/acme/att.git", "main", null, null, null))));
        List<Long> dbBefore = new ArrayList<>(session.getDbConnectionIds());
        List<RefRepo> refsBefore = new ArrayList<>(session.getRefRepos());

        List<QuestionSuggestion> saved = service.record(5L, 2,
                List.of(db(4L, "이유", List.of("orders")), ref(11L, "참조 이유")));

        assertThat(saved).hasSize(2);
        assertThat(session.getDbConnectionIds()).isEqualTo(dbBefore);
        assertThat(session.getRefRepos()).isEqualTo(refsBefore);
        verify(sessions, never()).save(any());
        verify(sessions, never()).saveAndFlush(any());
    }

    // ── 화면 뷰 ──────────────────────────────────────────────────────

    private static QuestionSuggestion withId(QuestionSuggestion q, long id) {
        ReflectionTestUtils.setField(q, "id", id);
        return q;
    }

    @Test
    void PENDING_DB_제안의_선택지는_요청자_기준이다() {
        Candidates c = cands(3);
        when(candidates.forSession(session)).thenReturn(c);
        when(store.findBySessionIdOrderByIdAsc(5L))
                .thenReturn(List.of(withId(QuestionSuggestion.db(5L, 1, null, "이유", List.of("orders")), 1L)));

        List<SuggestionView> v = service.views(5L);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).dbOptions()).isEqualTo(c.db());
        assertThat(v.get(0).dbSlotsLeft()).isEqualTo(2);
        assertThat(v.get(0).refSlotsLeft()).isEqualTo(3);
        assertThat(v.get(0).status()).isEqualTo("PENDING");
        assertThat(v.get(0).kind()).isEqualTo("DB");
        // 선택지는 세션(요청자 포함)으로 계산한다 - 보는 사람이 아니다
        verify(candidates).forSession(session);
    }

    @Test
    void 결정된_제안의_뷰에는_선택지가_없다() {
        QuestionSuggestion q = withId(QuestionSuggestion.db(5L, 1, 4L, "이유", List.of()), 1L);
        q.decide(SuggestionStatus.DISMISSED, "user1", OffsetDateTime.now());
        when(store.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of(q));

        List<SuggestionView> v = service.views(5L);

        assertThat(v.get(0).dbOptions()).isEmpty();
        assertThat(v.get(0).status()).isEqualTo("DISMISSED");
    }

    @Test
    void 참조_제안의_뷰에는_별칭과_기본_브랜치가_있다() {
        RepoCatalogEntry e = mock(RepoCatalogEntry.class);
        when(e.getId()).thenReturn(11L);
        when(e.getAlias()).thenReturn("Netis7.0");
        when(e.getDefaultBranch()).thenReturn("main");
        when(catalog.findAllById(any())).thenReturn(List.of(e));
        when(store.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of(
                withId(QuestionSuggestion.refRepo(5L, 1, 11L, "이유"), 1L),
                withId(QuestionSuggestion.refRepo(5L, 1, 77L, "지워진 레포"), 2L)));

        List<SuggestionView> v = service.views(5L);

        assertThat(v.get(0).repoAlias()).isEqualTo("Netis7.0");
        assertThat(v.get(0).defaultBranch()).isEqualTo("main");
        assertThat(v.get(0).dbOptions()).isEmpty();
        assertThat(v.get(1).repoAlias()).isNull();
        assertThat(v.get(1).defaultBranch()).isNull();
    }

    @Test
    void PENDING이_없으면_후보를_계산하지_않는다() {
        session.setDbConnectionIds(new ArrayList<>(List.of(9L)));
        session.setRefRepos(new ArrayList<>(List.of(
                new RefRepo(21L, "붙은참조1", "acme/a1", "https://x/acme/a1.git", "main", null, null, null),
                new RefRepo(22L, "붙은참조2", "acme/a2", "https://x/acme/a2.git", "main", null, null, null))));
        QuestionSuggestion decided = withId(QuestionSuggestion.db(5L, 1, 4L, "이유", List.of()), 1L);
        decided.decide(SuggestionStatus.ACCEPTED, "user1", OffsetDateTime.now());
        when(store.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of(decided));

        List<SuggestionView> v = service.views(5L);

        // 남은 자리는 세션에서 바로 센다 - 상한 3에서 붙은 수를 뺀 값
        assertThat(v.get(0).dbOptions()).isEmpty();
        assertThat(v.get(0).dbSlotsLeft()).isEqualTo(2);
        assertThat(v.get(0).refSlotsLeft()).isEqualTo(1);
        verifyNoInteractions(candidates);
    }

    @Test
    void viewsOf는_고른_id만_돌려준다() {
        when(store.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of(
                withId(QuestionSuggestion.db(5L, 1, null, "a", List.of()), 1L),
                withId(QuestionSuggestion.db(5L, 2, null, "b", List.of()), 2L)));

        assertThat(service.viewsOf(5L, List.of(2L))).extracting(SuggestionView::id).containsExactly(2L);
    }
}
