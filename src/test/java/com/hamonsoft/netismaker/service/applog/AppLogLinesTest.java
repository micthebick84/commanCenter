package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.entity.EnvVar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** docker logs 한 줄 → 화면 줄(시각·본문 분리, ANSI 제거, 가리기, 자르기). */
class AppLogLinesTest {

    static final SecretMasker NONE = SecretMasker.of(List.of(), Map.of());

    @Test
    void 시각_접두를_떼고_본문() {
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.123456789Z hello\r", "out", NONE, 4000))
                .isEqualTo(new AppLogLine("2026-11-03T01:02:03.123456789Z", "out", "hello"));
    }

    @Test
    void 빈_본문_줄() {
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.000000000Z", "err", NONE, 4000))
                .isEqualTo(new AppLogLine("2026-11-03T01:02:03.000000000Z", "err", ""));
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.000000000Z \r", "out", NONE, 4000).text()).isEmpty();
    }

    @Test
    void 시각_접두_뒤_공백은_하나만_뗀다() {
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.000000000Z   indented", "out", NONE, 4000).text())
                .isEqualTo("  indented");
    }

    @Test
    void 본문_중간_CR과_줄구분_문자도_한_줄() {
        AppLogLine line = AppLogLines.toLine(
                "2026-11-03T01:02:03.000000000Z a\rb\u0085c d e f", "out", NONE, 4000);

        assertThat(line).isNotNull();
        assertThat(line.text()).isEqualTo("a\rb\u0085c d e f");
    }

    @Test
    void ANSI_색_코드를_지운다() {
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.000000000Z \u001b[31mERROR\u001b[0m x", "out", NONE, 4000).text())
                .isEqualTo("ERROR x");
        assertThat(AppLogLines.toInfo("\u001b[33mWARNING\u001b[0m y", NONE, 4000)).isEqualTo("WARNING y");
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03Z \u001b[1;38;5;196mfatal\u001b[m", "out", NONE, 4000).text())
                .isEqualTo("fatal");
    }

    @Test
    void 시각_없는_줄은_null() {
        String daemon = "Error response from daemon: No such container: netis-task-42";

        assertThat(AppLogLines.toLine(daemon, "err", NONE, 4000)).isNull();
        assertThat(AppLogLines.toInfo(daemon, NONE, 4000)).isEqualTo(daemon);
    }

    @Test
    void 시각처럼_보여도_접두_뒤가_공백이_아니면_null() {
        assertThat(AppLogLines.toLine("2026-11-03T01:02:03.000000000Zextra", "out", NONE, 4000)).isNull();
        assertThat(AppLogLines.toLine("  2026-11-03T01:02:03.000000000Z late", "out", NONE, 4000)).isNull();
    }

    @Test
    void 시간대_오프셋_시각도_접두() {
        assertThat(AppLogLines.toLine("2026-11-03T10:02:03.5+09:00 hi", "out", NONE, 4000))
                .isEqualTo(new AppLogLine("2026-11-03T10:02:03.5+09:00", "out", "hi"));
    }

    @Test
    void 넘으면_자르고_표시() {
        AppLogLine line = AppLogLines.toLine("2026-11-03T01:02:03.000000000Z " + "x".repeat(5000), "out", NONE, 4000);

        assertThat(line.text()).hasSize(4000 + AppLogLines.CUT_MARK.length()).endsWith("…(잘림)");
        assertThat(line.text()).startsWith("x".repeat(4000));
        assertThat(AppLogLines.toInfo("y".repeat(5000), NONE, 4000)).hasSize(4000 + AppLogLines.CUT_MARK.length());
    }

    @Test
    void 상한_이하는_그대로() {
        assertThat(AppLogLines.cut("x".repeat(4000), 4000)).isEqualTo("x".repeat(4000));
        assertThat(AppLogLines.cut("", 4000)).isEmpty();
    }

    @Test
    void 서로게이트_앞_절반에서_잘리면_그_반쪽은_버린다() {
        // U+1F600은 서로게이트 쌍(2자) — 상한 4000자가 쌍 한가운데에 떨어진다
        String text = "a".repeat(3999) + "😀" + "tail";

        assertThat(AppLogLines.cut(text, 4000)).isEqualTo("a".repeat(3999) + AppLogLines.CUT_MARK);
        // 쌍이 온전히 들어가는 경계는 그대로 둔다
        assertThat(AppLogLines.cut("a".repeat(3998) + "😀" + "tail", 4000))
                .isEqualTo("a".repeat(3998) + "😀" + AppLogLines.CUT_MARK);
    }

    @Test
    void 가린_뒤에_자른다() {
        // 비밀이 4000자 경계에 걸친다 — 먼저 자르면 "S3cr"까지만 남아 가릴 수 없다
        SecretMasker masker = SecretMasker.of(List.of(new EnvVar("PW", "S3cretValue!", true)), Map.of());
        String raw = "2026-11-03T01:02:03.000000000Z " + "a".repeat(3995) + "S3cretValue!";

        AppLogLine line = AppLogLines.toLine(raw, "out", masker, 4000);

        assertThat(line.text()).doesNotContain("S3cr");
        assertThat(line.text()).endsWith("••••");
        assertThat(line.text()).isEqualTo("a".repeat(3995) + "••••");
    }

    @Test
    void 가린_뒤에_넘으면_그때_자른다() {
        SecretMasker masker = SecretMasker.of(List.of(new EnvVar("PW", "S3cretValue!", true)), Map.of());

        String info = AppLogLines.toInfo("S3cretValue!" + "b".repeat(5000), masker, 4000);

        assertThat(info).startsWith("••••bbb").endsWith(AppLogLines.CUT_MARK)
                .hasSize(4000 + AppLogLines.CUT_MARK.length());
    }

    @Test
    void toInfo는_가리고_끝_CR을_지운다() {
        SecretMasker masker = SecretMasker.of(List.of(new EnvVar("PW", "hunter22", true)), Map.of());

        assertThat(AppLogLines.toInfo("login hunter22 failed\r", masker, 4000)).isEqualTo("login •••• failed");
    }
}
