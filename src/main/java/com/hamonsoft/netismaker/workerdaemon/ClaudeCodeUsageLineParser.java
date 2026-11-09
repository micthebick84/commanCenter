package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Claude Code 기록(jsonl) 한 줄을 읽는다(순수). assistant 줄 중 message.usage가 있는 것만 EVENT이고,
 * 그 밖의 줄은 SKIP, 깨졌거나 키·시각을 못 얻는 usage 줄은 UNREADABLE이다.
 * message.content·도구 입력은 읽지 않는다. cwd는 구분에만 쓰이고 이벤트에는 실리지 않는다.
 */
public final class ClaudeCodeUsageLineParser {

    static final int KEY_MAX = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ParsedLine SKIP = new ParsedLine(Kind.SKIP, null);
    private static final ParsedLine UNREADABLE = new ParsedLine(Kind.UNREADABLE, null);

    public enum Kind { SKIP, UNREADABLE, EVENT }

    public record ParsedLine(Kind kind, UsageLine line) {}

    public record UsageLine(String eventKey, OffsetDateTime occurredAt, String cwd, String model, String speed,
                            String serviceTier, String entrypoint, String sessionId, boolean sidechain,
                            long input, long output, long cacheWrite5m, long cacheWrite1h, long cacheRead) {

        /** 토큰은 칸별 최댓값, 나머지는 this(같은 응답이 여러 줄로 남는다 — 스펙 §4). */
        public UsageLine maxWith(UsageLine o) {
            return new UsageLine(eventKey, occurredAt, cwd, model, speed, serviceTier, entrypoint, sessionId, sidechain,
                    Math.max(input, o.input), Math.max(output, o.output),
                    Math.max(cacheWrite5m, o.cacheWrite5m), Math.max(cacheWrite1h, o.cacheWrite1h),
                    Math.max(cacheRead, o.cacheRead));
        }

        /** cwd는 싣지 않는다. */
        public ClaudeCodeUsageEvent toEvent(UsagePathClassifier.Category c) {
            return new ClaudeCodeUsageEvent(eventKey, occurredAt, c.name(), model, speed, serviceTier, entrypoint,
                    sessionId, sidechain, input, output, cacheWrite5m, cacheWrite1h, cacheRead);
        }
    }

    private ClaudeCodeUsageLineParser() {}

    public static ParsedLine parse(byte[] utf8Line) {
        if (utf8Line == null || utf8Line.length == 0) return SKIP;
        JsonNode root;
        try {
            root = MAPPER.readTree(utf8Line);
        } catch (Exception e) {
            return UNREADABLE;
        }
        if (root == null || root.isMissingNode()) return SKIP;
        if (!root.isObject()) return UNREADABLE;
        if (!"assistant".equals(text(root, "type"))) return SKIP;
        JsonNode message = root.get("message");
        JsonNode usage = message == null ? null : message.get("usage");
        if (usage == null || !usage.isObject()) return SKIP;

        String messageId = text(message, "id");
        if (messageId == null || messageId.isBlank()) return UNREADABLE;
        String requestId = text(root, "requestId");
        String key = requestId == null || requestId.isBlank() ? messageId : messageId + ":" + requestId;
        if (key.length() > KEY_MAX) return UNREADABLE;

        String ts = text(root, "timestamp");
        if (ts == null) return UNREADABLE;
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(ts);
        } catch (DateTimeParseException e) {
            return UNREADABLE;
        }

        long w5 = 0;
        long w1 = 0;
        JsonNode split = usage.get("cache_creation");
        if (split != null && split.isObject()) {
            w5 = tokens(split, "ephemeral_5m_input_tokens");
            w1 = tokens(split, "ephemeral_1h_input_tokens");
        }
        if (w5 + w1 == 0) {
            w5 = tokens(usage, "cache_creation_input_tokens");
            w1 = 0;
        }
        String model = text(message, "model");
        if (model == null || model.isBlank()) model = "unknown";
        JsonNode side = root.get("isSidechain");
        UsageLine line = new UsageLine(key, at, text(root, "cwd"), model, text(usage, "speed"),
                text(usage, "service_tier"), text(root, "entrypoint"), text(root, "sessionId"),
                side != null && side.asBoolean(false),
                tokens(usage, "input_tokens"), tokens(usage, "output_tokens"), w5, w1,
                tokens(usage, "cache_read_input_tokens"));
        return new ParsedLine(Kind.EVENT, line);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static long tokens(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber() || !v.canConvertToLong()) return 0;
        return Math.max(0, v.asLong());
    }
}
