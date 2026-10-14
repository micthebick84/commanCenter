package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactDownloadTokensTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void 토큰은_한_번만_쓴다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String t = tokens.issue(7L);
        assertThat(t).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokens.redeem(t)).hasValue(7L);
        assertThat(tokens.redeem(t)).isEmpty();
    }

    @Test
    void 토큰은_60초가_지나면_쓸_수_없다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String t = tokens.issue(7L);
        now.set(now.get().plusSeconds(60));
        assertThat(tokens.redeem(t)).isEmpty();
        assertThat(tokens.redeem(null)).isEmpty();
        assertThat(tokens.redeem("nope")).isEmpty();
    }

    @Test
    void 만료_직전_59초까지는_쓸_수_있다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String t = tokens.issue(7L);
        now.set(now.get().plusSeconds(59));
        assertThat(tokens.redeem(t)).hasValue(7L);
    }

    @Test
    void 발급마다_다른_토큰이고_서로_영향이_없다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String a = tokens.issue(7L);
        String b = tokens.issue(8L);
        assertThat(a).isNotEqualTo(b);
        assertThat(tokens.redeem(b)).hasValue(8L);
        assertThat(tokens.redeem(a)).hasValue(7L);
    }
}
