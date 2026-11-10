package com.hamonsoft.netismaker.workerdaemon.patch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 종료 부분 패치 워커 설정 (스펙 2026-11-10).
 *  ignore          전·후 비교에서 뺄 산출물 항목(glob, 쉼표 구분) — 비면 기본값
 *  upload-timeout  패치 zip 업로드 상한
 *  keep-manifests  레포별로 남길 "후" 해시 목록 수(다음 패치의 "전" 캐시)
 */
@ConfigurationProperties(prefix = "netis-maker.worker.close.patch")
public record ClosePatchWorkerProperties(List<String> ignore, Duration uploadTimeout, Integer keepManifests) {

    public static final List<String> DEFAULT_IGNORE = List.of("META-INF/MANIFEST.MF", "META-INF/maven/**");

    public ClosePatchWorkerProperties {
        List<String> globs = ignore == null ? List.of()
                : ignore.stream().filter(s -> s != null).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ignore = globs.isEmpty() ? DEFAULT_IGNORE : globs;
        if (uploadTimeout == null) uploadTimeout = Duration.ofMinutes(10);
        if (keepManifests == null) keepManifests = 20;
    }
}
