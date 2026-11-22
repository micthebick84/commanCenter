package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.Candidates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 질문 세션 대화 중 제안(DB 연결·참조 repo) — claim에 후보를 싣는 쪽. Claude는 제안만 하고 붙이는 것은 QuestionService.ask뿐이다. */
@Service
@Profile("api")
public class QuestionSuggestService {

    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final QuestionSuggestCandidates candidates;
    private final boolean enabled;
    private final int maxQaTurns;

    public QuestionSuggestService(InterviewSessionRepository sessions, InterviewTurnRepository turns,
                                  QuestionSuggestCandidates candidates,
                                  @Value("${app.question.suggest.enabled:true}") boolean enabled,
                                  @Value("${app.question.max-qa-turns:10}") int maxQaTurns) {
        this.sessions = sessions;
        this.turns = turns;
        this.candidates = candidates;
        this.enabled = enabled;
        this.maxQaTurns = maxQaTurns;
    }

    /** 빈 값 = 이번 턴에 제안 도구를 줄 이유가 없다(꺼짐·질문 세션 아님·마지막 문답·후보 없음). */
    @Transactional(readOnly = true)
    public Optional<InterviewClaimResponse.Suggest> claimCandidates(long sessionId) {
        if (!enabled) return Optional.empty();
        InterviewSession s = sessions.findById(sessionId).orElse(null);
        if (s == null || s.getKind() != InterviewKind.QUESTION) return Optional.empty();
        // 지금까지 답한 수 + 이번 답변이 상한이면 제안을 받아도 이어서 물을 문답이 남지 않는다
        long answered = turns.countBySessionIdAndRole(sessionId, "assistant");
        if (answered + 1 >= maxQaTurns) return Optional.empty();
        Candidates c = candidates.forSession(s);
        if (c.isEmpty()) return Optional.empty();
        return Optional.of(new InterviewClaimResponse.Suggest(
                c.db().stream()
                        .map(d -> new InterviewClaimResponse.DbCandidate(d.id(), d.name(), d.dbType()))
                        .toList(),
                c.refs().stream()
                        .map(r -> new InterviewClaimResponse.RefCandidate(r.catalogId(), r.alias(), r.hint()))
                        .toList()));
    }
}
