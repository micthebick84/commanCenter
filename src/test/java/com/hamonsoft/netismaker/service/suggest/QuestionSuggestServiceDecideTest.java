package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.entity.QuestionSuggestion;
import com.hamonsoft.netismaker.entity.SuggestionStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionSuggestionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.service.TaskException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 받아들이기·지나감(ask 안)과 닫기(자기 트랜잭션) — 제안 표만 읽고 쓴다 (스펙 2026-11-22 §6.3). */
class QuestionSuggestServiceDecideTest {

    private final QuestionSuggestionRepository store = mock(QuestionSuggestionRepository.class);
    private final QuestionSuggestService service = new QuestionSuggestService(mock(InterviewSessionRepository.class),
            mock(InterviewTurnRepository.class), mock(QuestionSuggestCandidates.class), store,
            mock(RepoCatalogRepository.class), true, 10);

    private final List<QuestionSuggestion> rows = new ArrayList<>();

    private QuestionSuggestion row(long id, long sessionId) {
        QuestionSuggestion q = QuestionSuggestion.refRepo(sessionId, 1, 11L, "이유");
        ReflectionTestUtils.setField(q, "id", id);
        rows.add(q);
        return q;
    }

    @BeforeEach
    void setUp() {
        when(store.findBySessionIdAndStatusOrderByIdAsc(5L, SuggestionStatus.PENDING)).thenAnswer(i ->
                rows.stream().filter(q -> q.getSessionId() == 5L && q.getStatus() == SuggestionStatus.PENDING).toList());
        when(store.findById(1L)).thenAnswer(i -> rows.stream().filter(q -> q.getId() == 1L).findFirst());
    }

    @Test
    void 받아들인_id는_ACCEPTED_나머지_PENDING은_SUPERSEDED가_된다() {
        QuestionSuggestion a = row(1, 5), b = row(2, 5), c = row(3, 5);

        List<Long> changed = service.onAsk(5L, List.of(2L), "user1");

        assertThat(changed).containsExactly(1L, 2L, 3L);
        assertThat(b.getStatus()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(b.getDecidedBy()).isEqualTo("user1");
        assertThat(a.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(c.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
    }

    @Test
    void acceptIds가_null이면_남은_PENDING이_모두_SUPERSEDED가_된다() {
        QuestionSuggestion a = row(1, 5), b = row(2, 5);

        assertThat(service.onAsk(5L, null, "user1")).containsExactly(1L, 2L);
        assertThat(a.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(b.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
    }

    @Test
    void 다른_세션의_id와_이미_결정된_id는_무시한다() {
        QuestionSuggestion a = row(1, 5), other = row(7, 6), done = row(8, 5);
        done.decide(SuggestionStatus.DISMISSED, "user1", OffsetDateTime.now());

        List<Long> changed = service.onAsk(5L, List.of(7L, 8L), "user1");

        assertThat(changed).containsExactly(1L);
        assertThat(a.getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(other.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(done.getStatus()).isEqualTo(SuggestionStatus.DISMISSED);
    }

    @Test
    void PENDING이_없으면_빈_목록이다() {
        assertThat(service.onAsk(5L, List.of(1L), "user1")).isEmpty();
    }

    @Test
    void 닫기는_PENDING을_DISMISSED로_바꾼다() {
        QuestionSuggestion a = row(1, 5);

        assertThat(service.dismiss(5L, 1L, "user1")).isEqualTo(1L);
        assertThat(a.getStatus()).isEqualTo(SuggestionStatus.DISMISSED);
        assertThat(a.getDecidedBy()).isEqualTo("user1");
    }

    @Test
    void 다른_세션의_제안을_닫으면_404다() {
        row(1, 6);

        assertThatThrownBy(() -> service.dismiss(5L, 1L, "user1"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("제안을 찾을 수 없습니다");
                });
        when(store.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dismiss(5L, 9L, "user1")).isInstanceOf(TaskException.class);
    }

    @Test
    void 이미_결정된_제안을_닫으면_409다() {
        QuestionSuggestion a = row(1, 5);
        a.decide(SuggestionStatus.ACCEPTED, "user1", OffsetDateTime.now());

        assertThatThrownBy(() -> service.dismiss(5L, 1L, "user1"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("이미 처리된 제안입니다");
                });
    }
}
