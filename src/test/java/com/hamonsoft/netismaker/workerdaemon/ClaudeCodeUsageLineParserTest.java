package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.Kind;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.ParsedLine;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.UsageLine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 줄은 모두 시험 안 문자열이다(실제 기록이 아님). */
class ClaudeCodeUsageLineParserTest {

    private static ParsedLine parse(String json) {
        return ClaudeCodeUsageLineParser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String assistant(String messageExtra, String usage, String top) {
        return "{\"type\":\"assistant\",\"timestamp\":\"2026-10-31T15:30:00.000Z\"" + top
                + ",\"message\":{\"id\":\"msg_1\"" + messageExtra + ",\"content\":[{\"type\":\"text\",\"text\":\"비밀 내용\"}],\"usage\":" + usage + "}}";
    }

    @Test
    void assistant_usage_줄() {
        String usage = "{\"input_tokens\":10,\"output_tokens\":20,\"cache_creation_input_tokens\":150,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":50},"
                + "\"cache_read_input_tokens\":7,\"service_tier\":\"standard\",\"speed\":\"fast\"}";
        ParsedLine p = parse(assistant(",\"model\":\"claude-test-1\"", usage,
                ",\"requestId\":\"req_1\",\"cwd\":\"C:\\\\x\",\"sessionId\":\"s1\",\"entrypoint\":\"cli\",\"isSidechain\":true"));
        assertThat(p.kind()).isEqualTo(Kind.EVENT);
        UsageLine l = p.line();
        assertThat(l.eventKey()).isEqualTo("msg_1:req_1");
        assertThat(l.occurredAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-31T15:30:00Z").toInstant());
        assertThat(l.input()).isEqualTo(10);
        assertThat(l.output()).isEqualTo(20);
        assertThat(l.cacheWrite5m()).isEqualTo(100);
        assertThat(l.cacheWrite1h()).isEqualTo(50);
        assertThat(l.cacheRead()).isEqualTo(7);
        assertThat(l.model()).isEqualTo("claude-test-1");
        assertThat(l.speed()).isEqualTo("fast");
        assertThat(l.serviceTier()).isEqualTo("standard");
        assertThat(l.entrypoint()).isEqualTo("cli");
        assertThat(l.sessionId()).isEqualTo("s1");
        assertThat(l.sidechain()).isTrue();
        assertThat(l.cwd()).isEqualTo("C:\\x");
    }

    @Test
    void 분리가_없으면_전체를_5분() {
        ParsedLine a = parse(assistant("", "{\"cache_creation_input_tokens\":300}", ""));
        assertThat(a.line().cacheWrite5m()).isEqualTo(300);
        assertThat(a.line().cacheWrite1h()).isZero();
        ParsedLine b = parse(assistant("", "{\"cache_creation_input_tokens\":300,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0}}", ""));
        assertThat(b.line().cacheWrite5m()).isEqualTo(300);
        assertThat(b.line().cacheWrite1h()).isZero();
    }

    @Test
    void requestId가_없으면_message_id만() {
        assertThat(parse(assistant("", "{}", "")).line().eventKey()).isEqualTo("msg_1");
    }

    @Test
    void 다른_줄은_SKIP() {
        assertThat(parse("{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}").kind()).isEqualTo(Kind.SKIP);
        assertThat(parse("{\"type\":\"assistant\",\"timestamp\":\"2026-10-31T15:30:00Z\",\"message\":{\"id\":\"m\"}}").kind())
                .isEqualTo(Kind.SKIP);
        assertThat(parse("{\"type\":\"summary\",\"summary\":\"x\"}").kind()).isEqualTo(Kind.SKIP);
        assertThat(ClaudeCodeUsageLineParser.parse(new byte[0]).kind()).isEqualTo(Kind.SKIP);
    }

    @Test
    void 못_읽은_줄() {
        assertThat(parse("{\"type\":\"assistant\",\"mess").kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(parse("{\"type\":\"assistant\",\"timestamp\":\"2026-10-31T15:30:00Z\",\"message\":{\"usage\":{}}}").kind())
                .isEqualTo(Kind.UNREADABLE);
        assertThat(parse("{\"type\":\"assistant\",\"message\":{\"id\":\"m\",\"usage\":{}}}").kind())
                .isEqualTo(Kind.UNREADABLE);
        assertThat(parse("{\"type\":\"assistant\",\"timestamp\":\"어제\",\"message\":{\"id\":\"m\",\"usage\":{}}}").kind())
                .isEqualTo(Kind.UNREADABLE);
        String longId = "m".repeat(201);
        assertThat(parse("{\"type\":\"assistant\",\"timestamp\":\"2026-10-31T15:30:00Z\",\"message\":{\"id\":\""
                + longId + "\",\"usage\":{}}}").kind()).isEqualTo(Kind.UNREADABLE);
    }

    @Test
    void 모르는_칸과_빠진_칸을_견딘다() {
        ParsedLine p = parse(assistant("", "{\"input_tokens\":-5,\"foo\":\"bar\"}", ""));
        assertThat(p.kind()).isEqualTo(Kind.EVENT);
        assertThat(p.line().output()).isZero();
        assertThat(p.line().input()).isZero();
        assertThat(p.line().model()).isEqualTo("unknown");
    }

    @Test
    void maxWith는_칸별_최댓값() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-31T15:30:00Z");
        UsageLine a = new UsageLine("k", t, "c", "m", "fast", "standard", "cli", "s", false, 5, 1, 10, 0, 3);
        UsageLine b = new UsageLine("k", t, "other", "m2", null, null, null, null, true, 4, 9, 2, 8, 3);
        UsageLine m = a.maxWith(b);
        assertThat(m.input()).isEqualTo(5);
        assertThat(m.output()).isEqualTo(9);
        assertThat(m.cacheWrite5m()).isEqualTo(10);
        assertThat(m.cacheWrite1h()).isEqualTo(8);
        assertThat(m.cacheRead()).isEqualTo(3);
        assertThat(m.model()).isEqualTo("m");
        assertThat(m.cwd()).isEqualTo("c");
        assertThat(m.sidechain()).isFalse();
    }

    @Test
    void toEvent는_구분을_싣는다() {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-31T15:30:00Z");
        UsageLine a = new UsageLine("k", t, "c", "m", null, null, null, null, false, 1, 2, 3, 4, 5);
        ClaudeCodeUsageEvent e = a.toEvent(UsagePathClassifier.Category.SYMPHONY);
        assertThat(e.category()).isEqualTo("SYMPHONY");
        assertThat(e.cacheWrite1hTokens()).isEqualTo(4);
    }
}
