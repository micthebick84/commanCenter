package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMasksTest {

    @Test
    void 맨_토큰은_별표_짧은_접두는_그대로() {
        assertThat(SecretMasks.maskBareTokens("x ghp_" + "a".repeat(20) + " y")).isEqualTo("x *** y");
        assertThat(SecretMasks.maskBareTokens("ghp_abc")).isEqualTo("ghp_abc");
        assertThat(SecretMasks.maskBareTokens(null)).isNull();
    }

    @Test
    void 네_종류_접두를_모두_가린다() {
        String text = "a ghp_" + "1".repeat(20)
                + " b github_pat_" + "2".repeat(20)
                + " c glpat-" + "3".repeat(16)
                + " d sk-ant-" + "4".repeat(16) + " e";

        assertThat(SecretMasks.maskBareTokens(text)).isEqualTo("a *** b *** c *** d *** e");
    }

    @Test
    void 공개_패턴은_글자_수_하한을_지킨다() {
        assertThat(SecretMasks.BARE_TOKENS.matcher("glpat-" + "z".repeat(16)).matches()).isTrue();
        assertThat(SecretMasks.BARE_TOKENS.matcher("glpat-" + "z".repeat(15)).matches()).isFalse();
    }

    @Test
    void URL_자격증명과_토큰을_함께() {
        assertThat(SecretMasks.maskUrlAndTokens("https://oauth2:glpat-" + "b".repeat(20) + "@git.example/x sk-ant-" + "c".repeat(16)))
                .isEqualTo("https://***@git.example/x ***");
    }

    @Test
    void URL_가리기는_null이면_null() {
        assertThat(SecretMasks.maskUrlAndTokens(null)).isNull();
    }
}
