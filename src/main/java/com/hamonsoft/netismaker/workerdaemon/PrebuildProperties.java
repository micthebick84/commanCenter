package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 배포 전 미리 빌드 설정 (스펙 2026-10-28 §5.1). JDK·Maven 경로는 워커 설정에만 두고 API DB에는 도구 이름만 둔다.
 *  timeout     미리 빌드 명령 시간 상한(기본 20분)
 *  toolchains  도구 이름 → JAVA_HOME + PATH 앞에 붙일 폴더. java-home이 빈 항목은 "설정 안 됨"(기동은 막지 않음)
 * WorkerProperties.Deploy와 분리 — 기존 생성자 호출부를 건드리지 않는다(Deploy 바인딩은 prebuild 하위 키를 무시).
 */
@ConfigurationProperties(prefix = "netis-maker.worker.deploy.prebuild")
public record PrebuildProperties(Duration timeout, Map<String, Toolchain> toolchains) {

    public PrebuildProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = Duration.ofMinutes(20);
        toolchains = toolchains == null ? Map.of() : Map.copyOf(toolchains);
    }

    /** path: PATH 앞에 붙일 폴더들, 워커 OS의 경로 구분자(';' Windows · ':' 그 밖)로 나눈다. */
    public record Toolchain(String javaHome, String path) {}

    /** 이름의 툴체인이 있고 java-home이 채워졌을 때만. */
    public Optional<Toolchain> configured(String name) {
        if (name == null) return Optional.empty();
        Toolchain tc = toolchains.get(name);
        if (tc == null || tc.javaHome() == null || tc.javaHome().isBlank()) return Optional.empty();
        return Optional.of(tc);
    }
}
