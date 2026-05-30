package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.Optional;

/**
 * Dockerfile 파싱 헬퍼 (순수 함수).
 */
public final class DockerfileSupport {

    private DockerfileSupport() {}

    /**
     * 첫 번째 비주석 EXPOSE 지시어의 첫 포트를 반환. 없으면 empty.
     * "EXPOSE 8080", "EXPOSE 3000/tcp" 형태 지원.
     */
    public static Optional<Integer> parseExposedPort(String dockerfileContent) {
        if (dockerfileContent == null) return Optional.empty();
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (!line.regionMatches(true, 0, "EXPOSE", 0, 6)) continue;
            String rest = line.substring(6).trim();
            if (rest.isEmpty()) continue;
            String firstToken = rest.split("\\s+")[0];
            String portStr = firstToken.split("/")[0];
            try {
                return Optional.of(Integer.parseInt(portStr));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
