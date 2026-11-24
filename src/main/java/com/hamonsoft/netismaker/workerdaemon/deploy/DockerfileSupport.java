package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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
            String[] from = parseFrom(raw);
            if (from == null) continue;
            String image = from[0];
            String key = image.toLowerCase(Locale.ROOT);
            // 이 줄의 AS 이름보다 먼저 본다 — 앞 단계만 단계 참조다(FROM alpine AS alpine은 레지스트리 이미지).
            boolean registryImage = !key.equals("scratch") && !image.contains("$") && !stages.contains(key);
            if (registryImage && !images.contains(image)) images.add(image);
            if (from[1] != null) stages.add(from[1].toLowerCase(Locale.ROOT));
        }
        return images;
    }

    /** 한 줄이 FROM이면 {이미지 토큰, AS 이름(없으면 null)}, 주석·다른 지시어면 null. --platform 같은 플래그는 건너뛴다. */
    private static String[] parseFrom(String raw) {
        String line = raw.trim();
        if (line.startsWith("#")) return null;
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2 || !tokens[0].equalsIgnoreCase("FROM")) return null;
        int i = 1;
        while (i < tokens.length && tokens[i].startsWith("--")) i++;
        if (i >= tokens.length) return null;
        String alias = i + 2 < tokens.length && tokens[i + 1].equalsIgnoreCase("AS") ? tokens[i + 2] : null;
        return new String[] {tokens[i], alias};
    }

    /**
     * 마지막 FROM(최종 단계)의 이미지. 앞 단계 이름이면 그 단계의 FROM까지 거슬러 올라간다.
     * FROM이 없으면 empty. scratch·변수가 든 이름은 그대로 돌려준다(tomcatMajor가 empty로 거른다).
     */
    public static Optional<String> finalStageImage(String dockerfileContent) {
        if (dockerfileContent == null) return Optional.empty();
        java.util.Map<String, String> stageImages = new java.util.HashMap<>();
        String last = null;
        for (String raw : dockerfileContent.split("\\R")) {
            String[] from = parseFrom(raw);
            if (from == null) continue;
            String resolved = stageImages.getOrDefault(from[0].toLowerCase(Locale.ROOT), from[0]);
            if (from[1] != null) stageImages.put(from[1].toLowerCase(Locale.ROOT), resolved);
            last = resolved;
        }
        return Optional.ofNullable(last);
    }

    /**
     * 공식 tomcat 이미지의 주 버전. 이름 끝 경로 조각이 정확히 tomcat이고 태그가 숫자로 시작해야 한다
     * (태그 없음·latest·다른 이미지는 empty). 레지스트리 포트(host:5000/…)와 다이제스트(@sha256)는 태그로 보지 않는다.
     */
    public static OptionalInt tomcatMajor(String image) {
        if (image == null) return OptionalInt.empty();
        String ref = image.trim();
        int at = ref.indexOf('@');
        if (at >= 0) ref = ref.substring(0, at);
        int slash = ref.lastIndexOf('/');
        String last = ref.substring(slash + 1);
        int colon = last.indexOf(':');
        if (colon < 0) return OptionalInt.empty();
        if (!last.substring(0, colon).equalsIgnoreCase("tomcat")) return OptionalInt.empty();
        String tag = last.substring(colon + 1);
        int end = 0;
        while (end < tag.length() && Character.isDigit(tag.charAt(end))) end++;
        if (end == 0 || end > 3) return OptionalInt.empty();
        return OptionalInt.of(Integer.parseInt(tag.substring(0, end)));
    }

    private static final String OVERRIDE_DIR = "/opt/netis-override/WEB-INF/classes";
    private static final String SED_TOMCAT7 = "s#</Context>#<Loader className=\"org.apache.catalina.loader.VirtualWebappLoader\""
            + " virtualClasspath=\"" + OVERRIDE_DIR + "\" searchVirtualFirst=\"true\"/></Context>#";
    private static final String SED_TOMCAT8 = "s#</Context>#<Resources><PreResources className=\"org.apache.catalina.webresources.DirResourceSet\""
            + " base=\"" + OVERRIDE_DIR + "\" webAppMount=\"/WEB-INF/classes\"/></Resources></Context>#";

    /**
     * 설정 파일 덮어쓰기 블록(스펙 2026-11-24 §14)을 끝에 붙인 내용 — Tomcat이 /opt/netis-override/WEB-INF/classes를
     * WAR의 classes보다 먼저 읽게 context.xml을 고친다(7 = VirtualWebappLoader, 8 이상 = PreResources).
     * root로 바꿔 쓰고, 최종 단계의 마지막 USER가 있으면 그 USER로 되돌린다. BuildKit 전용 문법은 쓰지 않는다.
     */
    public static String appendConfigOverride(String dockerfileContent, int tomcatMajor) {
        String content = dockerfileContent == null ? "" : dockerfileContent;
        String sep = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        String sed = tomcatMajor <= 7 ? SED_TOMCAT7 : SED_TOMCAT8;
        StringBuilder block = new StringBuilder()
                .append("# Symphony 배포 덮어쓰기 (설정 파일)\n")
                .append("USER root\n")
                .append("RUN mkdir -p ").append(OVERRIDE_DIR).append(" && chmod -R a+rX /opt/netis-override && sed -i '")
                .append(sed).append("' \"$CATALINA_HOME/conf/context.xml\"\n");
        finalStageLastUser(content).ifPresent(u -> block.append("USER ").append(u).append('\n'));
        return content + sep + block;
    }

    /** 최종 단계(마지막 FROM 뒤)의 마지막 USER 값. */
    private static Optional<String> finalStageLastUser(String dockerfileContent) {
        String user = null;
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (parseFrom(raw) != null) {
                user = null;
            } else if (line.regionMatches(true, 0, "USER", 0, 4) && line.length() > 4 && Character.isWhitespace(line.charAt(4))) {
                String value = line.substring(4).trim();
                if (!value.isEmpty()) user = value;
            }
        }
        return Optional.ofNullable(user);
    }

    /** 주석이 아닌 줄에 Tomcat context 설정(VirtualWebappLoader·PreResources·context.xml)을 직접 건드린 흔적이 있는지. */
    public static boolean touchesTomcatContext(String dockerfileContent) {
        if (dockerfileContent == null) return false;
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (line.contains("VirtualWebappLoader") || line.contains("PreResources") || line.contains("context.xml")) return true;
        }
        return false;
    }
}
