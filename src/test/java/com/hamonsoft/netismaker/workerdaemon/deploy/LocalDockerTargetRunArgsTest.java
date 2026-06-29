package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDockerTargetRunArgsTest {

    private DeployTarget.DeploySpec spec() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "bar");
        return new DeployTarget.DeploySpec(
                7, Path.of("/tmp/wt"),
                "netis-task-7:abc1234", "netis-task-7", 8080,
                env, Map.of("netis-maker.task", "7"));
    }

    @Test
    void local_mode_publishes_port_no_network_no_traefik() {
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19000, false, "netis-deploy", "micthebick.dev");
        assertThat(args).containsSequence("--name", "netis-task-7");
        assertThat(args).containsSequence("-p", "19000:8080");
        assertThat(args).containsSequence("--label", "netis-maker.task=7");
        assertThat(args).containsSequence("-e", "FOO=bar");
        assertThat(args).doesNotContain("--network");
        assertThat(args).noneMatch(a -> a.startsWith("traefik."));
        assertThat(args.get(args.size() - 1)).isEqualTo("netis-task-7:abc1234");
    }

    @Test
    void public_mode_adds_network_and_traefik_labels_and_keeps_port() {
        List<String> args = LocalDockerTarget.buildRunArgs(spec(), 19042, true, "netis-deploy", "micthebick.dev");
        assertThat(args).containsSequence("-p", "19042:8080");                 // host-port 유지
        assertThat(args).containsSequence("--network", "netis-deploy");
        assertThat(args).contains("traefik.enable=true");
        assertThat(args).contains("traefik.http.routers.task-7.rule=Host(`task-7.micthebick.dev`)");
        assertThat(args).contains("traefik.http.services.task-7.loadbalancer.server.port=8080");
        assertThat(args.get(args.size() - 1)).isEqualTo("netis-task-7:abc1234"); // 이미지가 마지막
    }
}
