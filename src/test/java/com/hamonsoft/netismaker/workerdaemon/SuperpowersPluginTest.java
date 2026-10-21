package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SDD에 붙일 superpowers 플러그인 경로 (스펙 2026-10-15 §6.4·§6.9). */
class SuperpowersPluginTest {

    @TempDir Path tmp;

    private Path installSddSkill() throws Exception {
        Files.createDirectories(tmp.resolve("skills/subagent-driven-development"));
        Path skill = tmp.resolve(SuperpowersPlugin.SDD_SKILL);
        Files.writeString(skill, "# SDD");
        return skill;
    }

    @Test
    void a_plugin_dir_with_the_sdd_skill_is_ready() throws Exception {
        installSddSkill();

        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).contains(tmp);
        assertThat(new SuperpowersPlugin("  " + tmp + "  ").sddReadyPath()).contains(tmp);
    }

    /** claude는 worktree를 작업 디렉터리로 돈다 — 워커의 작업 디렉터리 기준 상대 경로는 거기서 안 맞는다 (최종 리뷰). */
    @Test
    void a_relative_path_becomes_an_absolute_normalized_path() throws Exception {
        installSddSkill();
        Path cwd = Path.of("").toAbsolutePath();
        assumeTrue(Objects.equals(cwd.getRoot(), tmp.toAbsolutePath().getRoot()), "다른 드라이브라 상대 경로를 만들 수 없다");
        Path relative = cwd.relativize(tmp.toAbsolutePath());
        assertThat(relative.isAbsolute()).isFalse();

        Optional<Path> ready = new SuperpowersPlugin(relative.toString()).sddReadyPath();

        assertThat(ready).isPresent();
        assertThat(ready.get().isAbsolute()).isTrue();
        assertThat(ready.get()).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(new SuperpowersPlugin(tmp + "/./skills/..").sddReadyPath()).contains(tmp.toAbsolutePath().normalize());
    }

    /** 기동 뒤에 플러그인 폴더가 지워지거나 바뀌면 낡은 --plugin-dir 대신 리뷰로 대신 진행해야 한다. */
    @Test
    void the_skill_is_checked_on_every_call() throws Exception {
        Path skill = installSddSkill();
        SuperpowersPlugin plugin = new SuperpowersPlugin(tmp.toString());
        assertThat(plugin.sddReadyPath()).contains(tmp);

        Files.delete(skill);
        assertThat(plugin.sddReadyPath()).isEmpty();

        Files.writeString(skill, "# SDD again");   // 다시 설치되면 다시 쓸 수 있다
        assertThat(plugin.sddReadyPath()).contains(tmp);
    }

    @Test
    void blank_missing_or_incomplete_paths_are_not_ready() {
        assertThat(new SuperpowersPlugin("").sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(null).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.resolve("nope").toString()).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).isEmpty();   // SKILL.md 없음
    }
}
