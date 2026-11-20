package com.hamonsoft.netismaker.service.route;

import com.hamonsoft.netismaker.config.QuestionRouteProperties;
import com.hamonsoft.netismaker.dto.QuestionRouteResponse;
import com.hamonsoft.netismaker.entity.QuestionRouteLog;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.QuestionRouteLogRepository;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문 → 레포 추천 오케스트레이션 (스펙 2026-11-20 §6.1·§6.4). Jev 요청·응답의 모양은 JevClient만 안다.
 * 밖으로 나가는 것은 질문 발췌와 카탈로그 별칭·추천 키워드뿐이다. 질문 본문은 로그에 남기지 않는다.
 *
 * 메서드 전체를 트랜잭션으로 감싸지 않는다: Jev 호출(최대 수 초) 동안 DB 연결을 잡지 않고, 기록 저장 실패가
 * 바깥 트랜잭션을 rollback-only로 만들어 응답까지 막는 일(스펙 §8)을 피하려고 각 저장소 호출이 제 트랜잭션을 쓴다.
 */
@Slf4j
@Service
@Profile("api")
public class QuestionRouteService {

    static final int RECENT_FETCH = 10;
    static final int RECENT_MAX = 3;
    private static final Pattern WHITESPACE_RUN = Pattern.compile("(?U)[\\s\\p{Z}]+");

    private final RepoCatalogService catalog;
    private final JevClient jev;
    private final InterviewSessionRepository sessions;
    private final QuestionRouteLogRepository logs;
    private final QuestionRouteProperties props;

    public QuestionRouteService(RepoCatalogService catalog, JevClient jev, InterviewSessionRepository sessions,
                                QuestionRouteLogRepository logs, QuestionRouteProperties props) {
        this.catalog = catalog;
        this.jev = jev;
        this.sessions = sessions;
        this.logs = logs;
        this.props = props;
        if (!props.enabled()) {
            log.info("질문 레포 추천: 꺼짐(설정 off)");
        } else if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.info("질문 레포 추천: 꺼짐(키 없음)");
        } else {
            log.info("질문 레포 추천: 켜짐(model={})", props.model());
        }
    }

    public QuestionRouteResponse route(String question, String userId) {
        String excerpt = excerpt(question, props.excerptChars());
        List<RepoCatalogEntry> routable = catalog.listRoutable();
        List<Long> eligible = routable.stream().map(RepoCatalogEntry::getId).toList();
        Set<Long> eligibleSet = new HashSet<>(eligible);
        List<Long> recent = sessions.findRecentQuestionRepoCatalogIds(userId, PageRequest.of(0, RECENT_FETCH)).stream()
                .filter(eligibleSet::contains).limit(RECENT_MAX).toList();

        RouteDecision.Decision decision = RouteDecision.unavailable();
        JevClient.JevAnswers answers = null;
        Integer latencyMs = null;
        if (!routable.isEmpty() && jev.available()) {
            List<JevClient.RepoOption> options = routable.stream()
                    .map(e -> new JevClient.RepoOption(e.getId(), e.getAlias(), e.getRouteHint())).toList();
            long start = System.nanoTime();
            try {
                Optional<JevClient.JevAnswers> got = jev.ask(excerpt, options);
                if (got.isPresent()) {
                    answers = got.get();
                    decision = guard(RouteDecision.decide(answers, props.threshold()), eligibleSet);
                }
            } catch (RuntimeException e) {
                // ask는 예상한 실패를 스스로 삼키지만, 뜻밖의 예외도 500이 아니라 200 + UNAVAILABLE이어야 한다. 종류만 남긴다.
                log.warn("질문 레포 추천 판단 중 예외 type={}", e.getClass().getSimpleName());
                answers = null;
                decision = RouteDecision.unavailable();
            }
            latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);
        }

        Long routeId = null;
        try {
            routeId = logs.save(QuestionRouteLog.of(userId, excerpt, decision, answers, latencyMs)).getId();
        } catch (RuntimeException e) {
            // 질문 본문이 섞일 수 있는 메시지는 남기지 않는다 — 예외 종류만.
            log.warn("질문 레포 추천 기록 저장 실패 type={}", e.getClass().getSimpleName());
        }
        log.info("질문 레포 추천 state={} latencyMs={}", decision.state(), latencyMs);
        return new QuestionRouteResponse(routeId, decision.state().name(), decision.recommendedRepoCatalogId(),
                decision.candidates(), recent, eligible, decision.dbNeeded());
    }

    /**
     * 사용자가 실제로 고른 레포와 만든 세션을 추천 기록에 적는다. @Transactional을 달지 않는다 — 질문 등록 트랜잭션에
     * 참여해 관리 중인 엔티티의 변경 감지로 반영된다(save 호출 없음). 없는·남의·이미 선택된 기록은 조용히 넘기고,
     * 이 안에서 나는 예외는 잡아 로그만 남긴다(스펙 §6.3). 다만 질문 등록 트랜잭션 안에서 도므로 진짜 DB 장애는
     * (어차피 등록도 실패할) 예외다 — "어떤 경우에도 등록이 실패하지 않는다"는 뜻이 아니다.
     */
    public void linkChoice(Long routeId, String userId, Long chosenRepoCatalogId, Long sessionId) {
        if (routeId == null) return;
        try {
            QuestionRouteLog row = logs.findById(routeId).orElse(null);
            if (row == null || !java.util.Objects.equals(row.getUserId(), userId) || row.getChosenAt() != null) return;
            row.markChosen(chosenRepoCatalogId, sessionId);
        } catch (RuntimeException e) {
            log.warn("질문 레포 추천 선택 기록 실패 type={}", e.getClass().getSimpleName());
        }
    }

    /** 후보·추천을 등록 가능한 id로 제한한다. 추천이 걸러지면 AMBIGUOUS(추천 없음). */
    private static RouteDecision.Decision guard(RouteDecision.Decision d, Set<Long> eligible) {
        List<RouteDecision.Candidate> candidates = d.candidates().stream()
                .filter(c -> eligible.contains(c.repoCatalogId())).toList();
        Long rec = d.recommendedRepoCatalogId();
        if (rec != null && !eligible.contains(rec)) {
            return new RouteDecision.Decision(RouteDecision.RouteState.AMBIGUOUS, null, candidates, d.dbNeeded());
        }
        return new RouteDecision.Decision(d.state(), rec, candidates, d.dbNeeded());
    }

    /** 공백류 연속을 한 칸으로 줄이고 trim한 뒤 코드포인트 기준으로 앞 maxChars개만 남긴다. */
    public static String excerpt(String question, int maxChars) {
        if (question == null || maxChars <= 0) return "";
        String s = WHITESPACE_RUN.matcher(question).replaceAll(" ").strip();
        if (s.codePointCount(0, s.length()) <= maxChars) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }
}
