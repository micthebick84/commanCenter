package com.hamonsoft.netismaker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 질문 레포 추천 설정 (스펙 2026-11-20). yml의 app.question-route.* — 값이 빠져도 아래 기본값으로 동작한다.
 * apiKey는 env TYPESAFE_API_KEY에서 오며 로그·예외·응답에 남기지 않는다. 키가 비면 추천만 꺼진다.
 */
@ConfigurationProperties("app.question-route")
public record QuestionRouteProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("jev-1.14.0") String model,
        @DefaultValue("https://api.typesafe.ai/v1/systemone") String endpoint,
        @DefaultValue("3000") int timeoutMs,
        @DefaultValue("500") int excerptChars,
        @DefaultValue Threshold threshold) {

    /** 판정 임계값 시작값(스펙 §6.2). */
    public record Threshold(
            @DefaultValue("0.7") double mentions,
            @DefaultValue("0.7") double listedYes,
            @DefaultValue("0.3") double listedNo,
            @DefaultValue("0.5") double topProbability,
            @DefaultValue("0.6") double needsDb) {
    }
}
