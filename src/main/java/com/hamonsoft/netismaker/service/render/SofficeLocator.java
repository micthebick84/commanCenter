package com.hamonsoft.netismaker.service.render;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * LibreOffice 실행 파일 찾기 (스펙 2026-10-27 §4). 순서: 설정값 → Windows 기본 설치 위치 → PATH의 soffice/soffice.com.
 * 환경(OS·존재 확인·PATH)을 인자로 받는 순수 함수라 단위 테스트가 가능하다 — 실제 환경 호출은 {@link #resolveFromEnvironment}.
 */
public final class SofficeLocator {

    static final Path WINDOWS_DEFAULT = Path.of("C:\\Program Files\\LibreOffice\\program\\soffice.com");
    private static final List<String> PATH_NAMES = List.of("soffice", "soffice.com");

    private SofficeLocator() {}

    public static Optional<Path> resolve(String configured, String osName,
                                         Function<Path, Boolean> exists, String pathEnv) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Boolean.TRUE.equals(exists.apply(p))) return Optional.of(p);
        }
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("win")
                && Boolean.TRUE.equals(exists.apply(WINDOWS_DEFAULT))) {
            return Optional.of(WINDOWS_DEFAULT);
        }
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String name : PATH_NAMES) {
                    Path candidate = Path.of(dir.trim(), name);
                    if (Boolean.TRUE.equals(exists.apply(candidate))) return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** 실제 OS·파일시스템·PATH로 찾는다. */
    public static Optional<Path> resolveFromEnvironment(String configured) {
        return resolve(configured, System.getProperty("os.name"), Files::isRegularFile, System.getenv("PATH"));
    }
}
