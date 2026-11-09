package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeUsageSanitizerTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00+09:00");

    private static ClaudeCodeUsageEvent ev(String key, String model, long out) {
        return new ClaudeCodeUsageEvent(key, T0, "SYMPHONY", model, null, null, null, null, false, 1, out, 0, 0, 0);
    }

    @Test
    void 같은_키는_칸별_최댓값_한_건_키_순서() {
        var r = ClaudeCodeUsageSanitizer.sanitize(List.of(ev("k2", "a", 10), ev("k1", "a", 1), ev("k2", "b", 250)));

        assertThat(r.events()).extracting(ClaudeCodeUsageEvent::eventKey).containsExactly("k1", "k2");
        var k2 = r.events().get(1);
        assertThat(k2.outputTokens()).isEqualTo(250);
        assertThat(k2.model()).isEqualTo("a");
        assertThat(r.skipped()).isZero();
    }

    @Test
    void 이상한_이벤트만_건너뛴다() {
        List<ClaudeCodeUsageEvent> in = new ArrayList<>();
        in.add(ev("", "m", 1));
        in.add(ev("x".repeat(201), "m", 1));
        in.add(new ClaudeCodeUsageEvent("k", null, "SYMPHONY", "m", null, null, null, null, false, 1, 1, 0, 0, 0));
        in.add(new ClaudeCodeUsageEvent("k", T0, "OTHER", "m", null, null, null, null, false, 1, 1, 0, 0, 0));
        in.add(ev("ok", "m", 1));

        var r = ClaudeCodeUsageSanitizer.sanitize(in);

        assertThat(r.skipped()).isEqualTo(4);
        assertThat(r.events()).hasSize(1);
        assertThat(r.events().get(0).eventKey()).isEqualTo("ok");
    }

    @Test
    void 음수는_0_긴_문자열은_칸_길이로() {
        var raw = new ClaudeCodeUsageEvent("k", T0, "DIRECT", "m".repeat(150), "s".repeat(30), "t".repeat(40),
                "e".repeat(40), "i".repeat(120), null, -5, 1, -1, -2, -3);
        var blank = new ClaudeCodeUsageEvent("k2", T0, "DIRECT", "  ", null, null, null, null, null, 0, 0, 0, 0, 0);
        var nullModel = new ClaudeCodeUsageEvent("k3", T0, "DIRECT", null, null, null, null, null, null, 0, 0, 0, 0, 0);

        var r = ClaudeCodeUsageSanitizer.sanitize(List.of(raw, blank, nullModel));

        var a = r.events().get(0);
        assertThat(a.inputTokens()).isZero();
        assertThat(a.cacheWrite5mTokens()).isZero();
        assertThat(a.cacheWrite1hTokens()).isZero();
        assertThat(a.cacheReadTokens()).isZero();
        assertThat(a.outputTokens()).isEqualTo(1);
        assertThat(a.model()).hasSize(100);
        assertThat(a.speed()).hasSize(20);
        assertThat(a.serviceTier()).hasSize(30);
        assertThat(a.entrypoint()).hasSize(30);
        assertThat(a.sessionId()).hasSize(100);
        assertThat(a.sidechain()).isFalse();
        assertThat(r.events().get(1).model()).isEqualTo("unknown");
        assertThat(r.events().get(2).model()).isEqualTo("unknown");
    }

    @Test
    void lastEventAt은_남은_이벤트의_최대_시각() {
        var late = new ClaudeCodeUsageEvent("b", T0.plusHours(3), "SYMPHONY", "m", null, null, null, null, false, 1, 1, 0, 0, 0);
        var bad = new ClaudeCodeUsageEvent("", T0.plusDays(9), "SYMPHONY", "m", null, null, null, null, false, 1, 1, 0, 0, 0);

        assertThat(ClaudeCodeUsageSanitizer.sanitize(List.of(ev("a", "m", 1), late, bad)).lastEventAt())
                .isEqualTo(T0.plusHours(3));
        assertThat(ClaudeCodeUsageSanitizer.sanitize(List.of()).lastEventAt()).isNull();
        assertThat(ClaudeCodeUsageSanitizer.sanitize(null).events()).isEmpty();
    }
}
