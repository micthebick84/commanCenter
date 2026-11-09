package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 수신 이벤트 정리(순수). 이상한 이벤트는 건너뛰어 skipped로 세고, 문자열은 칸 길이로 자르며,
 * 음수 토큰은 0으로 하고, 같은 키는 토큰 칸별 최댓값으로 한 건에 합친다(다른 칸은 먼저 온 값).
 */
public final class ClaudeCodeUsageSanitizer {

    static final int KEY_MAX = 200;
    static final int MODEL_MAX = 100;
    static final int SPEED_MAX = 20;
    static final int TIER_MAX = 30;
    static final int ENTRYPOINT_MAX = 30;
    static final int SESSION_MAX = 100;

    public record Sanitized(List<ClaudeCodeUsageEvent> events, int skipped, OffsetDateTime lastEventAt) {}

    private ClaudeCodeUsageSanitizer() {}

    public static Sanitized sanitize(List<ClaudeCodeUsageEvent> raw) {
        if (raw == null || raw.isEmpty()) {
            return new Sanitized(List.of(), 0, null);
        }
        Map<String, ClaudeCodeUsageEvent> byKey = new TreeMap<>();
        int skipped = 0;
        for (ClaudeCodeUsageEvent e : raw) {
            ClaudeCodeUsageEvent clean = clean(e);
            if (clean == null) {
                skipped++;
                continue;
            }
            byKey.merge(clean.eventKey(), clean, ClaudeCodeUsageSanitizer::maxTokens);
        }
        List<ClaudeCodeUsageEvent> events = new ArrayList<>(byKey.values());
        OffsetDateTime last = null;
        for (ClaudeCodeUsageEvent e : events) {
            if (last == null || e.occurredAt().toInstant().isAfter(last.toInstant())) {
                last = e.occurredAt();
            }
        }
        return new Sanitized(events, skipped, last);
    }

    private static ClaudeCodeUsageEvent clean(ClaudeCodeUsageEvent e) {
        if (e == null || e.eventKey() == null || e.eventKey().isBlank() || e.eventKey().length() > KEY_MAX
                || e.occurredAt() == null
                || !("SYMPHONY".equals(e.category()) || "DIRECT".equals(e.category()))) {
            return null;
        }
        String model = e.model() == null || e.model().isBlank() ? "unknown" : cut(e.model(), MODEL_MAX);
        return new ClaudeCodeUsageEvent(
                e.eventKey(), e.occurredAt(), e.category(), model,
                cut(e.speed(), SPEED_MAX), cut(e.serviceTier(), TIER_MAX), cut(e.entrypoint(), ENTRYPOINT_MAX),
                cut(e.sessionId(), SESSION_MAX), Boolean.TRUE.equals(e.sidechain()),
                nonNeg(e.inputTokens()), nonNeg(e.outputTokens()), nonNeg(e.cacheWrite5mTokens()),
                nonNeg(e.cacheWrite1hTokens()), nonNeg(e.cacheReadTokens()));
    }

    private static ClaudeCodeUsageEvent maxTokens(ClaudeCodeUsageEvent a, ClaudeCodeUsageEvent b) {
        return new ClaudeCodeUsageEvent(
                a.eventKey(), a.occurredAt(), a.category(), a.model(), a.speed(), a.serviceTier(), a.entrypoint(),
                a.sessionId(), a.sidechain(),
                Math.max(a.inputTokens(), b.inputTokens()), Math.max(a.outputTokens(), b.outputTokens()),
                Math.max(a.cacheWrite5mTokens(), b.cacheWrite5mTokens()),
                Math.max(a.cacheWrite1hTokens(), b.cacheWrite1hTokens()),
                Math.max(a.cacheReadTokens(), b.cacheReadTokens()));
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long nonNeg(long v) {
        return Math.max(0, v);
    }
}
