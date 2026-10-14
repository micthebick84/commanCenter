package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 종료 산출물 꺼내기 설정 (스펙 2026-10-14 §4). CloseProperties와 분리 — 기존 생성자 호출부를 건드리지 않는다.
 *  search-dirs     카탈로그 경로가 없을 때 이미지 안에서 *.war·*.jar를 찾는 폴더(쉼표 구분)
 *  copy-timeout    docker cp 상한, upload-timeout API 업로드 상한
 */
@ConfigurationProperties(prefix = "netis-maker.worker.close.artifact")
public record ArtifactProperties(List<String> searchDirs, Duration copyTimeout, Duration uploadTimeout) {

    public ArtifactProperties {
        List<String> dirs = searchDirs == null ? List.of()
                : searchDirs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        searchDirs = dirs.isEmpty() ? List.of("/usr/local/tomcat/webapps", "/app") : dirs;
        if (copyTimeout == null) copyTimeout = Duration.ofMinutes(10);
        if (uploadTimeout == null) uploadTimeout = Duration.ofMinutes(10);
    }
}
