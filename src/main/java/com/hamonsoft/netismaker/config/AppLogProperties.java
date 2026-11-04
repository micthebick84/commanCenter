package com.hamonsoft.netismaker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 배포된 앱 컨테이너 로그 실시간 보기 설정 (스펙 2026-11-03 §4·§7). yml의 app.app-log.* — 값이 빠져도 아래 기본값으로 동작한다.
 * dockerPath가 비면 AppLogAvailability가 PATH·Windows 기본 위치에서 docker를 찾고, 못 찾으면 기능만 꺼진다.
 * 정수 칸은 1 미만이면 설정 실수로 보고 기동을 막는다 — 0이 "무제한"이나 "끔"으로 통하지 않고(시간 상한·핑이 사라지면
 * docker 프로세스가 남는다), 조용히 기본값으로 바뀌지도 않는다.
 */
@ConfigurationProperties("app.app-log")
public record AppLogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String dockerPath,
        @DefaultValue("500") int tailLines,
        @DefaultValue("6") int maxStreams,
        @DefaultValue("3") int maxStreamsPerTask,
        @DefaultValue("30") int maxDurationMinutes,
        @DefaultValue("10") int pingSeconds,
        @DefaultValue("300") int batchMillis,
        @DefaultValue("200") int batchLines,
        @DefaultValue("4000") int maxLineChars) {

    public AppLogProperties {
        requirePositive("tail-lines", tailLines);
        requirePositive("max-streams", maxStreams);
        requirePositive("max-streams-per-task", maxStreamsPerTask);
        requirePositive("max-duration-minutes", maxDurationMinutes);
        requirePositive("ping-seconds", pingSeconds);
        requirePositive("batch-millis", batchMillis);
        requirePositive("batch-lines", batchLines);
        requirePositive("max-line-chars", maxLineChars);
    }

    private static void requirePositive(String key, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("app.app-log." + key + "는 1 이상이어야 합니다");
        }
    }
}
