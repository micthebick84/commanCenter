package com.hamonsoft.netismaker.workerdaemon.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchManifestStoreTest {

    @TempDir
    Path dir;

    private static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    private static PatchTree tree() {
        TreeMap<String, String> m = new TreeMap<>();
        m.put("b.jsp", "2");
        m.put("a.jsp", "1");
        return new PatchTree("single:war", m);
    }

    @Test
    void saveThenLoadRoundTrips() {
        PatchManifestStore s = new PatchManifestStore(dir, 5);
        s.save("owner/repo", sha('a'), "key-1234567890abcdef-rest", tree());

        PatchTree loaded = s.load("owner/repo", sha('a'), "key-1234567890abcdef-rest").orElseThrow();
        assertThat(loaded.shape()).isEqualTo("single:war");
        assertThat(loaded.hashes()).containsExactly(Map.entry("a.jsp", "1"), Map.entry("b.jsp", "2"));
        assertThat(Files.isDirectory(dir.resolve("owner+repo"))).isTrue();
    }

    @Test
    void differentKeyMisses() {
        PatchManifestStore s = new PatchManifestStore(dir, 5);
        s.save("_gitlab/a+b", sha('a'), "1111111111111111xx", tree());

        assertThat(s.load("_gitlab/a+b", sha('a'), "2222222222222222xx")).isEmpty();
        assertThat(s.load("_gitlab/a+b", sha('c'), "1111111111111111xx")).isEmpty();
    }

    @Test
    void corruptFileLoadsAsEmpty() throws IOException {
        PatchManifestStore s = new PatchManifestStore(dir, 5);
        s.save("o/r", sha('a'), "1111111111111111", tree());
        Path file = dir.resolve("o+r").resolve(sha('a') + "-1111111111111111.json");
        Files.writeString(file, "{not json");

        assertThat(s.load("o/r", sha('a'), "1111111111111111")).isEmpty();
    }

    @Test
    void badCommitIsIgnored() {
        PatchManifestStore s = new PatchManifestStore(dir, 5);
        s.save("o/r", "../../evil", "1111111111111111", tree());

        assertThat(s.load("o/r", "../../evil", "1111111111111111")).isEmpty();
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void keepsOnlyNewestN() throws IOException {
        PatchManifestStore s = new PatchManifestStore(dir, 2);
        String key = "1111111111111111";
        s.save("o/r", sha('a'), key, tree());
        Files.setLastModifiedTime(dir.resolve("o+r").resolve(sha('a') + "-" + key + ".json"),
                FileTime.fromMillis(1_000_000L));
        s.save("o/r", sha('b'), key, tree());
        Files.setLastModifiedTime(dir.resolve("o+r").resolve(sha('b') + "-" + key + ".json"),
                FileTime.fromMillis(2_000_000L));
        s.save("o/r", sha('c'), key, tree());

        assertThat(s.load("o/r", sha('a'), key)).isEmpty();
        assertThat(s.load("o/r", sha('b'), key)).isPresent();
        assertThat(s.load("o/r", sha('c'), key)).isPresent();
    }

    @Test
    void keyChangesWhenIgnoreListChanges() {
        String k1 = PatchManifestStore.key("cmd", "tc", "art", List.of("a"));
        String k2 = PatchManifestStore.key("cmd", "tc", "art", List.of("a", "b"));

        assertThat(k1).hasSize(64).isNotEqualTo(k2);
        assertThat(PatchManifestStore.key("cmd", "tc", "art", List.of("a"))).isEqualTo(k1);
    }
}
