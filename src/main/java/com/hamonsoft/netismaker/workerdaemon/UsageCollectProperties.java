package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/**
 * Claude Code 사용량 수집 설정 (스펙 2026-11-03 §4). null만으로 기본값을 채우고 예외를 내지 않는다 —
 * 프로필 없는 UsageCollectConfig라 api 프로필에서도 바인딩되기 때문이다. 잘못된 값만 env 이름과 함께 거부한다.
 *  enabled 수집 켜기 · intervalSeconds 회차 주기(30 이상) · includeDirect 직접 사용분도 보냄
 *  symphonyRoots 추가 Symphony 폴더 · countFrom 세는 시작일(서울 자정) · machine PC 이름(비면 호스트 이름)
 *  maxMbPerRun 한 회차에 읽는 상한(MB)
 */
@ConfigurationProperties(prefix = "netis-maker.worker.usage-collect")
public record UsageCollectProperties(
        Boolean enabled,
        Integer intervalSeconds,
        Boolean includeDirect,
        List<String> symphonyRoots,
        String countFrom,
        String machine,
        Integer maxMbPerRun) {

    static final String DEFAULT_COUNT_FROM = "2026-10-01";

    public UsageCollectProperties {
        if (enabled == null) enabled = true;
        if (intervalSeconds == null) intervalSeconds = 300;
        if (intervalSeconds < 30) {
            throw new IllegalArgumentException("USAGE_COLLECT_INTERVAL_SECONDS는 30 이상이어야 합니다: " + intervalSeconds);
        }
        if (includeDirect == null) includeDirect = false;
        symphonyRoots = symphonyRoots == null ? List.of()
                : symphonyRoots.stream().filter(s -> s != null).map(String::trim).filter(s -> !s.isEmpty()).toList();
        countFrom = countFrom == null || countFrom.isBlank() ? DEFAULT_COUNT_FROM : countFrom.trim();
        try {
            LocalDate.parse(countFrom);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("USAGE_COLLECT_COUNT_FROM은 YYYY-MM-DD 형식이어야 합니다: " + countFrom);
        }
        machine = machine == null || machine.isBlank() ? null : machine.trim();
        if (maxMbPerRun == null) maxMbPerRun = 256;
        if (maxMbPerRun < 1) {
            throw new IllegalArgumentException("USAGE_COLLECT_MAX_MB_PER_RUN은 1 이상이어야 합니다: " + maxMbPerRun);
        }
    }

    public LocalDate countFromDate() {
        return LocalDate.parse(countFrom);
    }

    public long maxBytesPerRun() {
        return maxMbPerRun.longValue() * 1024L * 1024L;
    }
}
