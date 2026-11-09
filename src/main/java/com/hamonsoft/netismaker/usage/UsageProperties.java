package com.hamonsoft.netismaker.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * app.usage — 세기 시작일과 모델별 API 가격표(USD / 100만 토큰).
 * 모델 id에 '.'이 있으면 yml 키를 "[…]"로 쓴다.
 */
@ConfigurationProperties(prefix = "app.usage")
public record UsageProperties(String countFrom, Map<String, ModelPrice> prices) {

    public static final String DEFAULT_COUNT_FROM = "2026-10-01";

    public UsageProperties {
        if (countFrom == null || countFrom.isBlank()) {
            countFrom = DEFAULT_COUNT_FROM;
        }
        parse(countFrom);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        prices.forEach((model, p) -> {
            if (p == null || p.input() == null || p.output() == null || p.cacheWrite5m() == null
                    || p.cacheWrite1h() == null || p.cacheRead() == null) {
                throw new IllegalArgumentException("app.usage.prices." + model + ": 다섯 단가가 모두 있어야 합니다");
            }
        });
    }

    public LocalDate countFromDate() {
        return parse(countFrom);
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("app.usage.count-from 형식이 틀렸습니다(YYYY-MM-DD): " + value, e);
        }
    }

    /** 한 모델의 단가(USD / 100만 토큰). fastMultiplier·longContext는 공식 가격에 있는 모델만 채운다. */
    public record ModelPrice(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheWrite5m,
            BigDecimal cacheWrite1h,
            BigDecimal cacheRead,
            BigDecimal fastMultiplier,
            LongContext longContext
    ) {}

    /** 프롬프트가 threshold 토큰을 넘는 응답의 단가. null인 칸은 기본 단가를 쓴다. */
    public record LongContext(
            Long threshold,
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheWrite5m,
            BigDecimal cacheWrite1h,
            BigDecimal cacheRead
    ) {}
}
