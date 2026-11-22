package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.Candidates;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.RefCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionSuggestServiceClaimTest {

    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final QuestionSuggestCandidates candidates = mock(QuestionSuggestCandidates.class);

    private QuestionSuggestService service(boolean enabled) {
        return new QuestionSuggestService(sessions, turns, candidates,
                mock(com.hamonsoft.netismaker.repository.QuestionSuggestionRepository.class),
                mock(com.hamonsoft.netismaker.repository.RepoCatalogRepository.class), enabled, 10);
    }

    private InterviewSession question() {
        return InterviewSession.createQuestion("acme/x", "main", "t", "q?", "user1", List.of(), "claude-opus-5", "high");
    }

    private Candidates oneOfEach() {
        return new Candidates(List.of(new DbConnectionDto.Chip(4L, "기본 DB", "MARIADB")),
                List.of(new RefCandidate(11L, "Netis7.0", "7.0", "main")), 3, 3);
    }

    @Test
    void 후보가_있으면_claim용으로_옮긴다() {
        InterviewSession s = question();
        when(sessions.findById(5L)).thenReturn(Optional.of(s));
        when(turns.countBySessionIdAndRole(5L, "assistant")).thenReturn(0L);
        when(candidates.forSession(s)).thenReturn(oneOfEach());

        Optional<InterviewClaimResponse.Suggest> r = service(true).claimCandidates(5L);

        assertThat(r).isPresent();
        assertThat(r.get().dbCandidates()).containsExactly(new InterviewClaimResponse.DbCandidate(4L, "기본 DB", "MARIADB"));
        assertThat(r.get().refCandidates()).containsExactly(new InterviewClaimResponse.RefCandidate(11L, "Netis7.0", "7.0"));
    }

    @Test
    void claim용_참조_후보에는_기본_브랜치를_싣지_않는다() {
        assertThat(InterviewClaimResponse.RefCandidate.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("catalogId", "alias", "hint");
    }

    @Test
    void 기능이_꺼져_있으면_빈_값이다() {
        assertThat(service(false).claimCandidates(5L)).isEmpty();
        verifyNoInteractions(sessions, turns, candidates);
    }

    @Test
    void 질문_세션이_아니면_빈_값이다() {
        InterviewSession s = InterviewSession.create("acme/x", "main", "t", "d", "user1", List.of(), "claude-opus-5", "high");
        when(sessions.findById(5L)).thenReturn(Optional.of(s));

        assertThat(service(true).claimCandidates(5L)).isEmpty();
        verifyNoInteractions(candidates);
    }

    @Test
    void 세션이_없으면_빈_값이다() {
        when(sessions.findById(5L)).thenReturn(Optional.empty());

        assertThat(service(true).claimCandidates(5L)).isEmpty();
    }

    @Test
    void 이번_답변이_마지막_문답이면_빈_값이다() {
        InterviewSession s = question();
        when(sessions.findById(5L)).thenReturn(Optional.of(s));
        when(candidates.forSession(any())).thenReturn(oneOfEach());

        when(turns.countBySessionIdAndRole(5L, "assistant")).thenReturn(9L);
        assertThat(service(true).claimCandidates(5L)).isEmpty();

        when(turns.countBySessionIdAndRole(5L, "assistant")).thenReturn(8L);
        assertThat(service(true).claimCandidates(5L)).isPresent();
    }

    @Test
    void 후보가_없으면_빈_값이다() {
        InterviewSession s = question();
        when(sessions.findById(5L)).thenReturn(Optional.of(s));
        when(turns.countBySessionIdAndRole(5L, "assistant")).thenReturn(0L);
        when(candidates.forSession(s)).thenReturn(new Candidates(List.of(), List.of(), 3, 3));

        assertThat(service(true).claimCandidates(5L)).isEmpty();
    }
}
