package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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

    /**
     * FROM 지시어가 가리키는 레지스트리 이미지 목록(나온 순서, 중복 없이).
     * 앞 단계 이름(FROM build — 대소문자 무시)·scratch·변수가 든 이름(${BASE} — 값을 알 수 없다)은 뺀다.
     * --platform 같은 플래그는 건너뛴다.
     */
    public static List<String> parseBaseImages(String dockerfileContent) {
        List<String> images = new ArrayList<>();
        if (dockerfileContent == null) return images;
        Set<String> stages = new HashSet<>();
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            String[] tokens = line.split("\\s+");
            if (tokens.length < 2 || !tokens[0].equalsIgnoreCase("FROM")) continue;
            int i = 1;
            while (i < tokens.length && tokens[i].startsWith("--")) i++;
            if (i >= tokens.length) continue;
            String image = tokens[i];
            String key = image.toLowerCase(Locale.ROOT);
            // 이 줄의 AS 이름보다 먼저 본다 — 앞 단계만 단계 참조다(FROM alpine AS alpine은 레지스트리 이미지).
            boolean registryImage = !key.equals("scratch") && !image.contains("$") && !stages.contains(key);
            if (registryImage && !images.contains(image)) images.add(image);
            if (i + 2 < tokens.length && tokens[i + 1].equalsIgnoreCase("AS")) {
                stages.add(tokens[i + 2].toLowerCase(Locale.ROOT));
            }
        }
        return images;
    }
}
