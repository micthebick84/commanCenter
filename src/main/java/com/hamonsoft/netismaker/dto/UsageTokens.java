package com.hamonsoft.netismaker.dto;

/** 토큰 다섯 칸(입력·출력·5분 캐시 쓰기·1시간 캐시 쓰기·캐시 읽기). */
public record UsageTokens(long input, long output, long cacheWrite5m, long cacheWrite1h, long cacheRead) {

    public static final UsageTokens ZERO = new UsageTokens(0, 0, 0, 0, 0);

    public UsageTokens plus(UsageTokens o) {
        return new UsageTokens(input + o.input, output + o.output, cacheWrite5m + o.cacheWrite5m,
                cacheWrite1h + o.cacheWrite1h, cacheRead + o.cacheRead);
    }

    public long total() {
        return input + output + cacheWrite5m + cacheWrite1h + cacheRead;
    }

    /** 프롬프트 길이 = 응답이 읽은 입력 쪽 토큰 전부(출력 제외). */
    public long promptTokens() {
        return input + cacheWrite5m + cacheWrite1h + cacheRead;
    }
}
