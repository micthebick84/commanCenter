package com.hamonsoft.netismaker.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 산출물 1회용 내려받기 토큰 (스펙 2026-10-14 §6). API 메모리에만 둔다 — 재기동하면 사라지고 버튼을 다시 누르면 된다.
 * URL에 JWT를 싣지 않으려고 둔 장치라 수명이 짧다(60초).
 */
@Component
@Profile("api")
public class ArtifactDownloadTokens {

    public static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(long taskId, Instant expiresAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ArtifactDownloadTokens() {
        this(Clock.systemUTC());
    }

    ArtifactDownloadTokens(Clock clock) {
        this.clock = clock;
    }

    public String issue(long taskId) {
        Instant now = clock.instant();
        tokens.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.put(token, new Entry(taskId, now.plus(TTL)));
        return token;
    }

    /** 1회용 — 꺼내면서 지운다. 없거나 만료면 empty. */
    public OptionalLong redeem(String token) {
        if (token == null) return OptionalLong.empty();
        Entry e = tokens.remove(token);
        if (e == null || !e.expiresAt().isAfter(clock.instant())) return OptionalLong.empty();
        return OptionalLong.of(e.taskId());
    }
}
