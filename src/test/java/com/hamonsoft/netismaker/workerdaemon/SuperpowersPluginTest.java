package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** SDD에 붙일 superpowers 플러그인 경로 (스펙 2026-10-15 §6.4·§6.9). */
class SuperpowersPluginTest {

    @TempDir Path tmp;

    @Test
    void a_plugin_dir_with_the_sdd_skill_is_ready() throws Exception {
        Files.createDirectories(tmp.resolve("skills/subagent-driven-development"));
        Files.writeString(tmp.resolve(SuperpowersPlugin.SDD_SKILL), "# SDD");

        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).contains(tmp);
        assertThat(new SuperpowersPlugin("  " + tmp + "  ").sddReadyPath()).contains(tmp);
    }

    @Test
    void blank_missing_or_incomplete_paths_are_not_ready() {
        assertThat(new SuperpowersPlugin("").sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(null).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.resolve("nope").toString()).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).isEmpty();   // SKILL.md 없음
    }
}
