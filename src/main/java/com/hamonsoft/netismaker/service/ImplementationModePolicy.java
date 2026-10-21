package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 방식별 구현 상한·멈춤 판정 (스펙 2026-10-15 §5.4). 상한은 claim 때 작업에 기록해 워커에 보내고(워커가 claude를 끊는 시간),
 * 판정은 StaleTaskRecoveryJob이 쓴다. 워커가 먼저 끊고 보고할 수 있게 방식마다 0 < 상한 < 판정이어야 하며,
 * 아니면 API가 뜨지 않는다.
 */
@Component
@Profile("api")
public class ImplementationModePolicy {

    private final Map<ImplementationMode, Integer> timeouts = new EnumMap<>(ImplementationMode.class);
    private final Map<ImplementationMode, Integer> staleThresholds = new EnumMap<>(ImplementationMode.class);

    public ImplementationModePolicy(
            @Value("${app.task.implementation-timeout-minutes:45}") int directTimeout,
            @Value("${app.task.implementation-review-timeout-minutes:80}") int reviewTimeout,
            @Value("${app.task.implementation-sdd-timeout-minutes:240}") int sddTimeout,
            @Value("${app.task.implementation-stale-threshold-minutes:60}") int directStale,
            @Value("${app.task.implementation-review-stale-threshold-minutes:95}") int reviewStale,
            @Value("${app.task.implementation-sdd-stale-threshold-minutes:260}") int sddStale) {
        timeouts.put(ImplementationMode.DIRECT, directTimeout);
        timeouts.put(ImplementationMode.REVIEW, reviewTimeout);
        timeouts.put(ImplementationMode.SDD, sddTimeout);
        staleThresholds.put(ImplementationMode.DIRECT, directStale);
        staleThresholds.put(ImplementationMode.REVIEW, reviewStale);
        staleThresholds.put(ImplementationMode.SDD, sddStale);
        for (ImplementationMode m : ImplementationMode.values()) {
            int timeout = timeouts.get(m);
            int stale = staleThresholds.get(m);
            if (timeout <= 0 || timeout >= stale) {
                throw new IllegalStateException("구현 상한은 0보다 크고 멈춤 판정보다 작아야 한다 ("
                        + m.name() + ": 상한 " + timeout + "분, 판정 " + stale + "분)");
            }
        }
    }

    /** 설정 없이 쓰는 기본값(45/80/240, 60/95/260) — setter 주입 전 단위 테스트용. */
    public static ImplementationModePolicy defaults() {
        return new ImplementationModePolicy(45, 80, 240, 60, 95, 260);
    }

    public int timeoutMinutes(ImplementationMode mode) {
        return timeouts.get(mode == null ? ImplementationMode.DIRECT : mode);
    }

    public int staleThresholdMinutes(ImplementationMode mode) {
        return staleThresholds.get(mode == null ? ImplementationMode.DIRECT : mode);
    }

    /** 확정·재시도 바디 값 해석 — null·공백이면 whenBlank, enum 이름(대소문자 구분)이 아니면 400. */
    public static ImplementationMode parse(String raw, ImplementationMode whenBlank) {
        if (raw == null || raw.isBlank()) return whenBlank;
        try {
            return ImplementationMode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 구현 방식: " + raw);
        }
    }
}
