package com.hamonsoft.netismaker.service.route;

import com.hamonsoft.netismaker.config.QuestionRouteProperties;

import java.util.Comparator;
import java.util.List;

/**
 * Jev 판단 수치 → 라우팅 상태(스펙 2026-11-20 §6.2). 순수 함수 — 모든 비교는 경계 포함.
 */
public final class RouteDecision {

    public enum RouteState { CONFIDENT, AMBIGUOUS, NO_MATCH, UNAVAILABLE }

    /** JSON 직렬화는 {repoCatalogId, probability}. */
    public record Candidate(Long repoCatalogId, double probability) {}

    public record Decision(RouteState state, Long recommendedRepoCatalogId, List<Candidate> candidates, Boolean dbNeeded) {}

    static final int MAX_CANDIDATES = 5;

    private RouteDecision() {}

    public static Decision decide(JevClient.JevAnswers a, QuestionRouteProperties.Threshold t) {
        List<Candidate> candidates = a.repoProbabilities().entrySet().stream()
                .map(e -> new Candidate(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(Candidate::probability).reversed()
                        .thenComparing(Candidate::repoCatalogId))
                .limit(MAX_CANDIDATES)
                .toList();
        boolean dbNeeded = a.needsDb() >= t.needsDb();
        boolean mentions = a.mentionsTarget() >= t.mentions();

        if (mentions && a.targetListed() >= t.listedYes() && !candidates.isEmpty()
                && candidates.get(0).probability() >= t.topProbability()) {
            return new Decision(RouteState.CONFIDENT, candidates.get(0).repoCatalogId(), candidates, dbNeeded);
        }
        if (mentions && a.targetListed() <= t.listedNo()) {
            return new Decision(RouteState.NO_MATCH, null, candidates, dbNeeded);
        }
        return new Decision(RouteState.AMBIGUOUS, null, candidates, dbNeeded);
    }

    public static Decision unavailable() {
        return new Decision(RouteState.UNAVAILABLE, null, List.of(), null);
    }
}
