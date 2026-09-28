package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOCKER_HOST로 다른 PC의 docker 데몬(예: ssh://Administrator@10.1.1.75)에 배포할 때의 판정.
 * docker CLI는 DOCKER_HOST를 스스로 읽으므로 워커는 "원격인가"만 알면 된다 —
 * 포트 할당의 로컬 bind 시험 생략, 그리고 publicHost 오설정 경고.
 */
class LocalDockerTargetRemoteDaemonTest {

    @Test
    void unset_or_local_transports_are_local() {
        assertThat(LocalDockerTarget.isRemoteDaemon(null)).isFalse();
        assertThat(LocalDockerTarget.isRemoteDaemon("")).isFalse();
        assertThat(LocalDockerTarget.isRemoteDaemon("   ")).isFalse();
        assertThat(LocalDockerTarget.isRemoteDaemon("npipe:////./pipe/docker_engine")).isFalse();
        assertThat(LocalDockerTarget.isRemoteDaemon("unix:///var/run/docker.sock")).isFalse();
    }

    @Test
    void ssh_and_tcp_transports_are_remote() {
        assertThat(LocalDockerTarget.isRemoteDaemon("ssh://Administrator@10.1.1.75")).isTrue();
        assertThat(LocalDockerTarget.isRemoteDaemon("tcp://10.1.1.75:2376")).isTrue();
        assertThat(LocalDockerTarget.isRemoteDaemon(" SSH://host ")).isTrue();
    }

    @Test
    void remote_daemon_with_localhost_public_host_is_warned() {
        // readiness·deploy_url이 http://{publicHost}:{port}라 원격인데 localhost면 전부 워커 PC를 두드린다.
        assertThat(LocalDockerTarget.remoteConfigWarning(true, "localhost")).contains("DEPLOY_PUBLIC_HOST");
        assertThat(LocalDockerTarget.remoteConfigWarning(true, "127.0.0.1")).contains("DEPLOY_PUBLIC_HOST");
    }

    @Test
    void no_warning_when_consistent() {
        assertThat(LocalDockerTarget.remoteConfigWarning(true, "10.1.1.75")).isNull();
        assertThat(LocalDockerTarget.remoteConfigWarning(false, "localhost")).isNull();
    }
}
