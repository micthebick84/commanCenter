package com.hamonsoft.netismaker.service.applog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * docker CLI 실행 파일 찾기 (스펙 2026-11-03 §2·§3). 순서: 설정값 → PATH(Windows는 docker.exe, 그 밖은 docker) →
 * Windows면 {userHome}\.local\bin\docker.exe. 마지막 후보는 실측 위치인데(스펙 §2), 예약 작업으로 뜬 API가 그 폴더를
 * PATH에 갖는지는 확인되지 않아 한 번 더 본다.
 * 짝은 render의 SofficeLocator지만 공유하지 않는다 — 후보 순서(soffice는 Windows 기본 위치가 PATH보다 앞)와 이름이 다르고,
 * 여기서는 경로로 쓸 수 없는 PATH 항목(따옴표 등)이 있어도 기동을 막지 않고 건너뛴다.
 * 환경(OS·존재 확인·PATH·홈)을 인자로 받는 순수 함수라 단위 테스트가 가능하다 — 실제 환경 호출은 {@link #resolveFromEnvironment}.
 */
public final class DockerLocator {

    private DockerLocator() {
    }

    public static Optional<Path> resolve(String configured, String osName, Function<Path, Boolean> exists,
                                         String pathEnv, String userHome) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");

        if (configured != null && !configured.isBlank()) {
            Optional<Path> found = existing(exists, configured.trim());
            if (found.isPresent()) return found;
        }
        String name = windows ? "docker.exe" : "docker";
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Optional<Path> found = existing(exists, dir.trim(), name);
                if (found.isPresent()) return found;
            }
        }
        if (windows && userHome != null && !userHome.isBlank()) {
            return existing(exists, userHome.trim(), ".local", "bin", "docker.exe");
        }
        return Optional.empty();
    }

    /** 실제 OS·파일시스템·PATH·홈으로 찾는다. */
    public static Optional<Path> resolveFromEnvironment(String configured) {
        return resolve(configured, System.getProperty("os.name"), Files::isRegularFile,
                System.getenv("PATH"), System.getProperty("user.home"));
    }

    /** 경로로 만들 수 없는 값(InvalidPathException)은 없는 것으로 본다 — 기동 중 예외로 API가 못 뜨면 안 되고 기능만 꺼져야 한다. */
    private static Optional<Path> existing(Function<Path, Boolean> exists, String first, String... more) {
        try {
            Path p = Path.of(first, more);
            return Boolean.TRUE.equals(exists.apply(p)) ? Optional.of(p) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
