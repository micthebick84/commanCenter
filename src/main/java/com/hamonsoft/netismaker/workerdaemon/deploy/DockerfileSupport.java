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
     * 생성한 Dockerfile 끝에 붙이는 JVM 기본 로케일 — 사내 앱은 서버 기본 로케일이 ko_KR이라고 가정하고
     * 로케일 없이 메시지를 찾는다. tomcat·openjdk 이미지는 LANG=C.UTF-8이라 JVM 기본 언어가 en이 되고,
     * message_ko_KR만 키가 있으면 NoSuchMessageException이 난다(2026-11-03 라이나 생명 작업 20~23 첫 화면 오류).
     * JAVA_TOOL_OPTIONS는 JVM이 직접 읽어 Dockerfile의 JAVA_OPTS·CATALINA_OPTS와 겹치지 않고,
     * 배포 env로 같은 이름을 주면(docker -e) 그 값이 이긴다. JVM이 없는 이미지에서는 쓰이지 않는다.
     */
    public static final String JAVA_LOCALE_BLOCK = """
            # netis-maker: 사내 앱은 JVM 기본 로케일 ko_KR을 가정한다 (배포 env JAVA_TOOL_OPTIONS로 바꿀 수 있음)
            ENV JAVA_TOOL_OPTIONS="-Duser.language=ko -Duser.country=KR"
            """;

    /**
     * JAVA_LOCALE_BLOCK을 끝에 붙인 내용. 주석이 아닌 줄에 JAVA_TOOL_OPTIONS나 user.language가 이미 있으면
     * (Dockerfile이 정한 값을 존중) empty.
     */
    public static Optional<String> appendJavaLocale(String dockerfileContent) {
        if (dockerfileContent == null) return Optional.empty();
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (line.contains("JAVA_TOOL_OPTIONS") || line.contains("user.language")) return Optional.empty();
        }
        String sep = dockerfileContent.isEmpty() || dockerfileContent.endsWith("\n") ? "" : "\n";
        return Optional.of(dockerfileContent + sep + JAVA_LOCALE_BLOCK);
    }

    /**
     * 생성한 Dockerfile 끝에 붙이는 납품 파일 COPY(스펙 2026-11-04 §6.2) — 미리 빌드가 .netis-prebuild/artifact/에
     * 스테이징한 파일을 이미지 /opt/netis-artifact/로. 파일 끝 = 마지막 FROM 뒤(최종 단계)라 종료 때 꺼낼 이미지에 들어간다.
     */
    public static final String ARTIFACT_COPY_BLOCK = """
            # 종료 산출물 납품 파일 (Symphony 워커가 붙임)
            COPY .netis-prebuild/artifact/ /opt/netis-artifact/
            """;

    /** ARTIFACT_COPY_BLOCK을 끝에 붙인 내용(언제나 붙인다 — 생성 Dockerfile 전용). 줄바꿈 규칙은 appendJavaLocale과 같다. */
    public static String appendArtifactCopy(String dockerfileContent) {
        String content = dockerfileContent == null ? "" : dockerfileContent;
        String sep = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        return content + sep + ARTIFACT_COPY_BLOCK;
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
