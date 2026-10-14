package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosePropertiesTest {

    @Test
    void 빈_값은_기본값으로() {
        CloseProperties p = new CloseProperties(" ", null, "", null, null, null);
        assertThat(p.vaultDir()).isNull();
        assertThat(p.noteFolder()).isEqualTo("HamonSoft/netisMaker");
        assertThat(p.mergeMethod()).isEqualTo("merge");
        assertThat(p.noteModel()).isEqualTo("sonnet");
        assertThat(p.noteTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 볼트_경로와_병합방식을_읽는다() {
        CloseProperties p = new CloseProperties("C:/Users/mic/Documents/Obsidian Vault", "X/Y", "Squash", "haiku",
                Duration.ofMinutes(3), null);
        assertThat(p.vaultDir()).isEqualTo(Path.of("C:/Users/mic/Documents/Obsidian Vault"));
        assertThat(p.mergeMethod()).isEqualTo("squash");
    }

    @Test
    void 모르는_병합방식은_부팅을_막는다() {
        assertThatThrownBy(() -> new CloseProperties(null, null, "fast-forward", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CLOSE_MERGE_METHOD");
    }
}
