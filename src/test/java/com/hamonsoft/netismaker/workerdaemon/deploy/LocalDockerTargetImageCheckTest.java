package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 베이스 이미지 확인의 `docker manifest inspect` 결과 판정. "없음"이 확실한 것은 레지스트리의 `no such manifest`뿐이다 —
 * 인증 거부(비공개 이미지일 수 있다)·네트워크 오류는 UNKNOWN으로 두고 빌드에 맡긴다(2026-11-02 작업 23: 없는 tomcat 태그).
 */
class LocalDockerTargetImageCheckTest {

    @Test
    void exit_zero_is_present() {
        assertThat(LocalDockerTarget.classifyManifestInspect(0, "{\"schemaVersion\": 2}"))
                .isEqualTo(DeployTarget.ImageAvailability.PRESENT);
    }

    @Test
    void no_such_manifest_is_missing() {
        assertThat(LocalDockerTarget.classifyManifestInspect(1,
                "no such manifest: docker.io/library/tomcat:7.0.109-jre8-openjdk\n"))
                .isEqualTo(DeployTarget.ImageAvailability.MISSING);
    }

    @Test
    void auth_and_network_errors_are_unknown() {
        assertThat(LocalDockerTarget.classifyManifestInspect(1,
                "errors:\ndenied: requested access to the resource is denied\nunauthorized: authentication required\n"))
                .isEqualTo(DeployTarget.ImageAvailability.UNKNOWN);
        assertThat(LocalDockerTarget.classifyManifestInspect(1,
                "failed to configure transport: error pinging v2 registry: dial tcp: lookup x.invalid: no such host"))
                .isEqualTo(DeployTarget.ImageAvailability.UNKNOWN);
        assertThat(LocalDockerTarget.classifyManifestInspect(1, null))
                .isEqualTo(DeployTarget.ImageAvailability.UNKNOWN);
    }
}
