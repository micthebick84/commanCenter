package com.hamonsoft.netismaker.service.suggest;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.SuggestionView;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.QuestionSuggestion;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.SuggestionKind;
import com.hamonsoft.netismaker.entity.SuggestionStatus;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionSuggestionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.Candidates;
import com.hamonsoft.netismaker.service.suggest.QuestionSuggestCandidates.RefCandidate;
import com.hamonsoft.netismaker.util.SecretMasks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 질문 세션 대화 중 제안(DB 연결·참조 repo) — claim에 후보를 싣고, 러너가 보고한 제안을 후보와 대조해 저장하고, 화면 뷰를 만든다.
 * Claude는 제안만 하고 붙이는 것은 QuestionService.ask뿐이다 — 여기서는 세션의 연결·참조를 절대 바꾸지 않는다.
 */
@Service
@Profile("api")
public class QuestionSuggestService {

    static final int REASON_MAX = 200;
    static final int TARGET_MAX = 80;
    static final int TARGETS_PER_SUGGESTION = 5;
    static final int REFS_PER_TURN = 2;

    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final QuestionSuggestCandidates candidates;
    private final QuestionSuggestionRepository suggestions;
    private final RepoCatalogRepository catalog;
    private final boolean enabled;
    private final int maxQaTurns;

    public QuestionSuggestService(InterviewSessionRepository sessions, InterviewTurnRepository turns,
                                  QuestionSuggestCandidates candidates,
                                  QuestionSuggestionRepository suggestions, RepoCatalogRepository catalog,
                                  @Value("${app.question.suggest.enabled:true}") boolean enabled,
                                  @Value("${app.question.max-qa-turns:10}") int maxQaTurns) {
        this.sessions = sessions;
        this.turns = turns;
        this.candidates = candidates;
        this.suggestions = suggestions;
        this.catalog = catalog;
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

    /**
     * 러너가 답변과 함께 보고한 제안을 정규화해 저장한다(스펙 §6.2). 자기 트랜잭션이다 - 답변 턴이 커밋된 뒤 워커 컨트롤러가 부르고,
     * 예외는 호출자가 잡는다(후보 계산이 거치는 다른 서비스의 예외가 답변 트랜잭션을 롤백 전용으로 만들지 못하게 밖으로 뺐다).
     * 후보는 한 번만 계산한다. 러너 보고는 믿지 않는다: 후보에 없는 레포는 버리고, 후보에 없는 연결은 연결 없는 제안으로 받는다.
     * 세션의 연결·참조는 읽기만 한다 - 붙이는 것은 QuestionService.ask뿐이다.
     */
    @Transactional
    public List<QuestionSuggestion> record(long sessionId, int turnSeq, List<WorkerQuestionRequest.Suggestion> reqs) {
        if (reqs == null || reqs.isEmpty()) return List.of();
        InterviewSession s = sessions.findById(sessionId).orElse(null);
        if (s == null || s.getKind() != InterviewKind.QUESTION) return List.of();
        Candidates c = candidates.forSession(s);
        Set<Long> dbIds = new HashSet<>();
        for (DbConnectionDto.Chip chip : c.db()) dbIds.add(chip.id());
        Set<Long> refIds = new HashSet<>();
        for (RefCandidate r : c.refs()) refIds.add(r.catalogId());
        int refLimit = Math.min(REFS_PER_TURN, c.refSlotsLeft());

        List<QuestionSuggestion> saved = new ArrayList<>();
        boolean dbTaken = false;
        Set<Long> refTaken = new HashSet<>();
        for (WorkerQuestionRequest.Suggestion req : reqs) {
            if (req == null) continue;
            String reason = clean(req.reason(), REASON_MAX);
            if (reason.isEmpty()) continue;
            SuggestionKind kind = parseKind(req.kind());
            if (kind == SuggestionKind.DB) {
                if (dbTaken || c.db().isEmpty()) continue;
                Long connId = req.dbConnectionId() != null && dbIds.contains(req.dbConnectionId())
                        ? req.dbConnectionId() : null;
                dbTaken = true;
                saved.add(suggestions.save(QuestionSuggestion.db(sessionId, turnSeq, connId, reason, cleanTargets(req.targets()))));
            } else if (kind == SuggestionKind.REF_REPO) {
                Long id = req.repoCatalogId();
                if (id == null || !refIds.contains(id) || refTaken.contains(id) || refTaken.size() >= refLimit) continue;
                refTaken.add(id);
                saved.add(suggestions.save(QuestionSuggestion.refRepo(sessionId, turnSeq, id, reason)));
            }
        }
        return saved;
    }

    /** 세션의 제안 전부(id 순) - 화면 스냅샷용. */
    @Transactional(readOnly = true)
    public List<SuggestionView> views(long sessionId) {
        return build(sessionId, null);
    }

    /** 세션의 제안 중 ids만(id 순). */
    @Transactional(readOnly = true)
    public List<SuggestionView> viewsOf(long sessionId, Collection<Long> ids) {
        return build(sessionId, new HashSet<>(ids));
    }

    private List<SuggestionView> build(long sessionId, Set<Long> only) {
        List<QuestionSuggestion> rows = suggestions.findBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(q -> only == null || only.contains(q.getId()))
                .toList();
        if (rows.isEmpty()) return List.of();
        InterviewSession s = sessions.findById(sessionId).orElse(null);
        if (s == null) return List.of();
        // 선택지·남은 자리는 세션(요청자) 기준 - 보는 사람이 관리자여도 남의 개인 연결이 섞이지 않는다
        Candidates c = candidates.forSession(s);
        Set<Long> catalogIds = new HashSet<>();
        for (QuestionSuggestion q : rows) if (q.getRepoCatalogId() != null) catalogIds.add(q.getRepoCatalogId());
        Map<Long, RepoCatalogEntry> byId = new HashMap<>();
        if (!catalogIds.isEmpty()) {
            for (RepoCatalogEntry e : catalog.findAllById(catalogIds)) byId.put(e.getId(), e);
        }
        List<SuggestionView> out = new ArrayList<>();
        for (QuestionSuggestion q : rows) {
            RepoCatalogEntry e = q.getKind() == SuggestionKind.REF_REPO && q.getRepoCatalogId() != null
                    ? byId.get(q.getRepoCatalogId()) : null;
            List<DbConnectionDto.Chip> options =
                    q.getKind() == SuggestionKind.DB && q.getStatus() == SuggestionStatus.PENDING ? c.db() : List.of();
            out.add(new SuggestionView(q.getId(), q.getTurnSeq(), q.getKind().name(), q.getStatus().name(), q.getReason(),
                    q.getTargets(), q.getDbConnectionId(), q.getRepoCatalogId(),
                    e == null ? null : e.getAlias(), e == null ? null : e.getDefaultBranch(),
                    options, c.dbSlotsLeft(), c.refSlotsLeft()));
        }
        return out;
    }

    private static SuggestionKind parseKind(String kind) {
        if ("DB".equals(kind)) return SuggestionKind.DB;
        if ("REF_REPO".equals(kind)) return SuggestionKind.REF_REPO;
        return null;
    }

    private static List<String> cleanTargets(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String t : raw) {
            String v = clean(t, TARGET_MAX);
            if (v.isEmpty()) continue;
            out.add(v);
            if (out.size() == TARGETS_PER_SUGGESTION) break;
        }
        return out;
    }

    /** strip, URL 자격증명 가림, 맨 토큰 가림, 코드 포인트로 자르기 순서 - 가린 뒤에 자른다(먼저 자르면 토큰이 반쪽으로 남는다). */
    private static String clean(String text, int maxCodePoints) {
        if (text == null) return "";
        String masked = SecretMasks.maskBareTokens(GitRemotes.mask(text.strip())).strip();
        if (masked.codePointCount(0, masked.length()) <= maxCodePoints) return masked;
        return masked.substring(0, masked.offsetByCodePoints(0, maxCodePoints)).strip();
    }
}
