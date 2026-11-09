package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** docker build 명령 — 커밋 값 빌드 인자(스펙 2026-11-04 §7). */
class LocalDockerTargetBuildArgsTest {

    @Test
    void 빌드_인자는_라벨_뒤_컨텍스트_앞에_들어간다() {
        var spec = new DeployTarget.DeploySpec(7, Path.of("/tmp/wt"), "netis-task-7:abc1234", "netis-task-7", 8080,
                Map.of(), Map.of("netis-maker.task", "7"), Map.of("NETIS_GIT_SHA", "abc1234def"));
        assertThat(LocalDockerTarget.buildCommand(spec)).containsExactly(
                "docker", "build", "-t", "netis-task-7:abc1234", "--label", "netis-maker.task=7",
                "--build-arg", "NETIS_GIT_SHA=abc1234def", ".");
    }

    @Test
    void head_sha가_없으면_빌드_인자를_넣지_않는다() {
        var spec = new DeployTarget.DeploySpec(7, Path.of("/tmp/wt"), "netis-task-7:latest", "netis-task-7", 8080,
                Map.of(), Map.of("netis-maker.task", "7"), Map.of());
        assertThat(LocalDockerTarget.buildCommand(spec)).doesNotContain("--build-arg")
                .containsExactly("docker", "build", "-t", "netis-task-7:latest", "--label", "netis-maker.task=7", ".");
    }
}
