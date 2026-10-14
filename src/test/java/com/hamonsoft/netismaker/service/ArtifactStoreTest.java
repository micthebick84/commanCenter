package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStoreTest {

    @TempDir Path root;

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void 이름은_허용_문자이고_war나_jar로_끝나야_한다() {
        assertThat(ArtifactStore.validName("netis7.0-task-9-abcdef1.jar")).isTrue();
        assertThat(ArtifactStore.validName("w-task-1-abcdef1.war")).isTrue();
        assertThat(ArtifactStore.validName("../x.war")).isFalse();
        assertThat(ArtifactStore.validName("a b.war")).isFalse();
        assertThat(ArtifactStore.validName("a.zip")).isFalse();
        assertThat(ArtifactStore.validName(null)).isFalse();
    }

    @Test
    void 해시가_맞으면_확정하고_폴더의_다른_파일을_지운다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        Path dir = Files.createDirectories(root.resolve("task-7/artifact"));
        Files.writeString(dir.resolve("old-task-7-1111111.war"), "old");
        byte[] body = "WAR-BYTES".getBytes(StandardCharsets.UTF_8);

        ArtifactInfo info = store.write(7L, "w-task-7-abcdef1.war", new ByteArrayInputStream(body), sha(body), 1024);

        assertThat(info).isEqualTo(new ArtifactInfo("w-task-7-abcdef1.war", body.length, sha(body)));
        assertThat(Files.readAllBytes(dir.resolve("w-task-7-abcdef1.war"))).isEqualTo(body);
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("w-task-7-abcdef1.war");
        }
        assertThat(store.exists(7L, "w-task-7-abcdef1.war")).isTrue();
    }

    @Test
    void 해시가_다르면_임시_파일을_지우고_거절한다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        assertThatThrownBy(() -> store.write(7L, "w-task-7-abcdef1.war",
                new ByteArrayInputStream("A".getBytes()), "0".repeat(64), 1024))
                .isInstanceOf(ArtifactStore.HashMismatch.class);
        try (var s = Files.list(root.resolve("task-7/artifact"))) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    void 상한을_넘으면_임시_파일을_지우고_거절한다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        byte[] body = new byte[2048];
        assertThatThrownBy(() -> store.write(7L, "w-task-7-abcdef1.war", new ByteArrayInputStream(body), sha(body), 1024))
                .isInstanceOf(ArtifactStore.TooLarge.class);
        try (var s = Files.list(root.resolve("task-7/artifact"))) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    void 두_번의_이동이_모두_실패하면_임시_파일을_지우고_원래_예외를_던진다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        // 확정 이름 자리에 비어 있지 않은 폴더 — 원자적 이동도 덮어쓰는 이동도 OS와 무관하게 실패한다
        Path blocker = Files.createDirectories(root.resolve("task-7/artifact/w-task-7-abcdef1.war"));
        Files.writeString(blocker.resolve("child"), "x");
        byte[] body = "WAR-BYTES".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.write(7L, "w-task-7-abcdef1.war", new ByteArrayInputStream(body), sha(body), 1024))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());   // 첫 이동(원자적)의 실패가 suppressed로 남는다

        assertThat(root.resolve("task-7/artifact/w-task-7-abcdef1.war.part")).doesNotExist();
        assertThat(blocker.resolve("child")).exists();
    }

    @Test
    void 임시_파일_삭제가_실패해도_원래_예외를_가리지_않고_suppressed로_붙인다() throws Exception {
        // 비어 있지 않은 폴더는 deleteIfExists가 DirectoryNotEmptyException을 던진다(OS 무관)
        Path stuck = Files.createDirectories(root.resolve("stuck"));
        Files.writeString(stuck.resolve("child"), "x");
        IOException original = new ArtifactStore.TooLarge(1024);

        ArtifactStore.discard(stuck, original);

        assertThat(original.getSuppressed()).hasSize(1);
        assertThat(original.getSuppressed()[0]).isInstanceOf(DirectoryNotEmptyException.class);
        // 정상 삭제와 없는 파일은 아무것도 붙이지 않는다
        Path gone = Files.writeString(root.resolve("gone.part"), "x");
        IOException clean = new IOException("x");
        ArtifactStore.discard(gone, clean);
        ArtifactStore.discard(gone, clean);
        assertThat(gone).doesNotExist();
        assertThat(clean.getSuppressed()).isEmpty();
    }
}
