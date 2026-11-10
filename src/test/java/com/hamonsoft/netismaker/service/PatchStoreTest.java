package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchStoreTest {

    @TempDir Path root;

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 파일_위치는_작업_폴더의_patch_폴더() {
        PatchStore store = new PatchStore(root.toString());
        assertThat(store.file(7L)).isEqualTo(root.toAbsolutePath().normalize().resolve("task-7/patch/patch.zip"));
        assertThat(store.exists(7L)).isFalse();
    }

    @Test
    void 쓰면_해시와_크기를_돌려주고_파일이_생긴다() throws Exception {
        PatchStore store = new PatchStore(root.toString());
        byte[] b = "ZIPDATA".getBytes(StandardCharsets.UTF_8);
        long size = store.write(7L, new ByteArrayInputStream(b), ArtifactStoreTest.sha(b), 1024);
        assertThat(size).isEqualTo(b.length);
        assertThat(store.exists(7L)).isTrue();
        assertThat(Files.readAllBytes(store.file(7L))).isEqualTo(b);
    }

    @Test
    void 해시가_다르면_거절하고_파일을_남기지_않는다() {
        PatchStore store = new PatchStore(root.toString());
        assertThatThrownBy(() -> store.write(7L, in("x"), "0".repeat(64), 1024))
                .isInstanceOf(ArtifactStore.HashMismatch.class);
        assertThat(store.exists(7L)).isFalse();
        try (Stream<Path> s = Files.list(store.file(7L).getParent())) {
            assertThat(s.toList()).isEmpty();   // .part도 없다
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 쓰면_이전_파일을_바꾼다_다른_폴더는_건드리지_않는다() throws Exception {
        PatchStore store = new PatchStore(root.toString());
        Path artifact = Files.createDirectories(root.resolve("task-7/artifact")).resolve("a.war");
        Files.writeString(artifact, "WAR");
        byte[] a = "AAA".getBytes(StandardCharsets.UTF_8);
        byte[] b = "BBBB".getBytes(StandardCharsets.UTF_8);
        store.write(7L, new ByteArrayInputStream(a), ArtifactStoreTest.sha(a), 1024);
        store.write(7L, new ByteArrayInputStream(b), ArtifactStoreTest.sha(b), 1024);
        assertThat(Files.readAllBytes(store.file(7L))).isEqualTo(b);
        assertThat(Files.readString(artifact)).isEqualTo("WAR");
    }

    @Test
    void 상한을_넘으면_거절하고_파일을_남기지_않는다() {
        PatchStore store = new PatchStore(root.toString());
        assertThatThrownBy(() -> store.write(7L, in("0123456789"), "0".repeat(64), 5))
                .isInstanceOf(ArtifactStore.TooLarge.class);
        assertThat(store.exists(7L)).isFalse();
    }
}
