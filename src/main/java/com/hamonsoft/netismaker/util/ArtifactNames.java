package com.hamonsoft.netismaker.util;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 종료 산출물 이름 규칙 (스펙 2026-11-04 §3·§4). API(ArtifactStore)와 워커(ArtifactPublisher·DeployPrebuilder)가 함께 쓴다.
 * 레포는 배포 이미지의 {@link #IMAGE_DIR} 바로 아래에 납품 파일 하나를 두고, Symphony는 그 파일을 그대로 보관한다.
 */
public final class ArtifactNames {

    /** 이미지 안 납품 폴더. 미리 빌드가 붙이는 COPY 줄·종료 때 자동 판정·레포 Dockerfile이 모두 이 경로를 쓴다. */
    public static final String IMAGE_DIR = "/opt/netis-artifact";

    /** 사람에게 보이는 허용 확장자 목록. */
    public static final String ALLOWED_LABEL = ".war·.jar·.zip·.tar.gz·.tgz";

    /** 긴 것부터 — tar.gz를 다른 확장자보다 먼저 본다. */
    private static final List<String> EXTENSIONS = List.of("tar.gz", "tgz", "zip", "war", "jar");

    private ArtifactNames() {}

    /** 이름(또는 경로)의 끝을 대소문자 무시로 보고 허용 확장자를 소문자로. 목록 밖이면 empty. */
    public static Optional<String> extension(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().filter(e -> lower.endsWith("." + e)).findFirst();
    }
}
