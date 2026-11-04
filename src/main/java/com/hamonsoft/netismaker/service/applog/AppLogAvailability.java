package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.config.AppLogProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 앱 로그 기능이 지금 켜져 있는지 정한다 (스펙 2026-11-03 §3·§7). 켜짐 = 설정 enabled 이고 docker를 찾았을 때.
 * 시작할 때 한 번 정하고 켜짐/꺼짐 줄을 한 번만 로그로 남긴다(RenderTargetPolicy 선례). 꺼져 있으면 docker를 찾지 않는다.
 * 줄에는 DOCKER_HOST의 값이 아니라 종류(원격·로컬·없음)만 싣는다 — 값에 사용자·주소가 들어 있을 수 있다.
 */
@Slf4j
@Component
@Profile("api")
public class AppLogAvailability {

    private final Optional<Path> docker;
    private final boolean active;
    private final String startupLine;

    /** 생성자가 둘이라 @Autowired가 없으면 Spring이 기본 생성자를 찾다 기동에 실패한다(RenderTargetPolicy 선례). */
    @Autowired
    public AppLogAvailability(AppLogProperties props) {
        this(props, props.enabled() ? DockerLocator.resolveFromEnvironment(props.dockerPath()) : Optional.empty(),
                System.getenv("DOCKER_HOST"));
    }

    /** 테스트용 — docker 탐색 결과와 DOCKER_HOST 값을 직접 넘긴다. */
    AppLogAvailability(AppLogProperties props, Optional<Path> docker, String dockerHostEnv) {
        this.docker = docker;
        this.active = props.enabled() && docker.isPresent();
        if (!props.enabled()) {
            this.startupLine = "앱 로그: 꺼짐(설정 off)";
        } else if (docker.isPresent()) {
            this.startupLine = "앱 로그: 켜짐(docker=" + docker.get() + ", DOCKER_HOST=" + dockerHostKind(dockerHostEnv) + ")";
        } else {
            this.startupLine = "앱 로그: 꺼짐(docker 없음)";
        }
        log.info("{}", startupLine);
    }

    public boolean active() {
        return active;
    }

    /** 앱 로그에 쓸 docker 경로 — 꺼짐이면 empty. */
    public Optional<Path> docker() {
        return active ? docker : Optional.empty();
    }

    /** 기동 로그에 남긴 줄. */
    String startupLine() {
        return startupLine;
    }

    /**
     * DOCKER_HOST의 종류 — 비었으면 없음, 로컬 전송(npipe·unix)이면 로컬, 그 밖(ssh·tcp …)은 원격.
     * LocalDockerTarget.isRemoteDaemon과 같은 규칙이다(그쪽은 워커 패키지의 package-private이라 공유하지 않는다).
     */
    static String dockerHostKind(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank()) return "없음";
        String h = dockerHost.trim().toLowerCase(Locale.ROOT);
        return h.startsWith("npipe://") || h.startsWith("unix://") ? "로컬" : "원격";
    }
}
