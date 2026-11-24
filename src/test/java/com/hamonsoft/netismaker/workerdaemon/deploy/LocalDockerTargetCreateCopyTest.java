package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDockerTargetCreateCopyTest {

    private DeployTarget.DeploySpec spec() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "bar");
        return new DeployTarget.DeploySpec(
                54, Path.of("/tmp/wt"),
                "netis-task-54:abc1234", "netis-task-54", 8080,
                env, Map.of("netis-maker.task", "54"), Map.of());
    }

    @Test
    void create_인자는_run과_같고_명령만_다르다() {
        for (boolean pub : new boolean[]{false, true}) {
            List<String> run = LocalDockerTarget.buildRunArgs(spec(), 19000, pub, "netis-deploy", "micthebick.dev", "");
            List<String> create = LocalDockerTarget.buildCreateArgs(spec(), 19000, pub, "netis-deploy", "micthebick.dev", "");

            List<String> expected = new ArrayList<>(run);
            assertThat(expected.subList(0, 3)).containsExactly("docker", "run", "-d");
            expected.subList(1, 3).clear();
            expected.add(1, "create");

            assertThat(create).isEqualTo(expected);
            assertThat(create).doesNotContain("-d", "run");
            assertThat(create.get(1)).isEqualTo("create");
        }
    }

    @Test
    void cp_인자() {
        Path dir = Path.of("C:\\tmp\\ovr");
        List<String> args = LocalDockerTarget.buildCopyArgs(dir, "netis-task-54");

        assertThat(args).hasSize(4);
        assertThat(args.subList(0, 2)).containsExactly("docker", "cp");
        assertThat(args.get(2)).endsWith(File.separator + ".");
        assertThat(args.get(2)).isEqualTo(dir.toAbsolutePath() + File.separator + ".");
        assertThat(args.get(3)).isEqualTo("netis-task-54:/opt/netis-override/");
    }

    @Test
    void overrideDir가_없으면_spec_8인자와_같다() {
        DeployTarget.DeploySpec s = spec();
        assertThat(s.overrideDir()).isNull();

        DeployTarget.DeploySpec withDir = new DeployTarget.DeploySpec(
                s.taskId(), s.contextDir(), s.imageName(), s.containerName(), s.containerPort(),
                s.env(), s.labels(), s.buildArgs(), Path.of("/tmp/ovr"));
        assertThat(withDir.overrideDir()).isEqualTo(Path.of("/tmp/ovr"));
    }
}
