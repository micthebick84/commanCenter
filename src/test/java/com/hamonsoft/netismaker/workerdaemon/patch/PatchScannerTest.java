package com.hamonsoft.netismaker.workerdaemon.patch;

import static com.hamonsoft.netismaker.workerdaemon.patch.PatchTestZips.b;
import static com.hamonsoft.netismaker.workerdaemon.patch.PatchTestZips.zip;
import static com.hamonsoft.netismaker.workerdaemon.patch.PatchTestZips.zipBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchScannerTest {

    private static final long T1 = 1_700_000_000_000L;
    private static final long T2 = 1_750_000_000_000L;
    private static final List<String> IGNORE = List.of("META-INF/MANIFEST.MF", "META-INF/maven/**");

    @TempDir
    Path dir;

    private static Map<String, byte[]> entries(Object... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof String s ? b(s) : (byte[]) v);
        }
        return m;
    }

    private PatchScanner scanner() {
        return new PatchScanner(IGNORE);
    }

    @Test
    void singleWarPathsAreEntryNames() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries("WEB-INF/classes/A.class", "x", "index.jsp", "y"), T1);

        PatchTree tree = scanner().scan(war);

        assertThat(tree.shape()).isEqualTo("single:war");
        assertThat(tree.hashes().keySet()).containsExactly("WEB-INF/classes/A.class", "index.jsp");
        assertThat(tree.hashes().get("index.jsp")).hasSize(64);
    }

    @Test
    void singleJarShapeAndExtensionCaseInsensitive() throws Exception {
        Path jar = zip(dir.resolve("a.JAR"), entries("p/A.class", "x"), T1);

        PatchTree tree = scanner().scan(jar);

        assertThat(tree.shape()).isEqualTo("single:jar");
        assertThat(tree.hashes().keySet()).containsExactly("p/A.class");
    }

    @Test
    void directoriesAreSkipped() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries("WEB-INF/", "", "WEB-INF/web.xml", "x"), T1);

        assertThat(scanner().scan(war).hashes().keySet()).containsExactly("WEB-INF/web.xml");
    }

    @Test
    void ignoredEntriesAreAbsent() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries(
                "META-INF/MANIFEST.MF", "m", "META-INF/maven/g/a/pom.properties", "p",
                "META-INF/context.xml", "c"), T1);

        assertThat(scanner().scan(war).hashes().keySet()).containsExactly("META-INF/context.xml");
    }

    @Test
    void nestedJarWithOnlyTimestampDifferenceIsEqual() throws Exception {
        byte[] jar1 = zipBytes(entries("a/A.class", "AAA", "b/B.class", "BBB"), T1);
        byte[] jar2 = zipBytes(entries("b/B.class", "BBB", "a/A.class", "AAA"), T2);
        assertThat(jar1).isNotEqualTo(jar2);
        Path war1 = zip(dir.resolve("1.war"), entries("WEB-INF/lib/x.jar", jar1, "index.jsp", "i"), T1);
        Path war2 = zip(dir.resolve("2.war"), entries("WEB-INF/lib/x.jar", jar2, "index.jsp", "i"), T2);

        PatchTree t1 = scanner().scan(war1);
        PatchTree t2 = scanner().scan(war2);

        assertThat(t1.hashes()).containsOnlyKeys("WEB-INF/lib/x.jar", "index.jsp");
        assertThat(t1.hashes().get("WEB-INF/lib/x.jar")).isEqualTo(t2.hashes().get("WEB-INF/lib/x.jar"));
        assertThat(t1).isEqualTo(t2);
    }

    @Test
    void nestedJarWithChangedClassDiffers() throws Exception {
        byte[] jar1 = zipBytes(entries("a/A.class", "AAA"), T1);
        byte[] jar2 = zipBytes(entries("a/A.class", "AAB"), T1);
        PatchTree t1 = scanner().scan(zip(dir.resolve("1.war"), entries("WEB-INF/lib/x.jar", jar1), T1));
        PatchTree t2 = scanner().scan(zip(dir.resolve("2.war"), entries("WEB-INF/lib/x.jar", jar2), T1));

        assertThat(t1.hashes().get("WEB-INF/lib/x.jar")).isNotEqualTo(t2.hashes().get("WEB-INF/lib/x.jar"));
    }

    @Test
    void nestedJarIgnoresManifestInside() throws Exception {
        byte[] jar1 = zipBytes(entries("META-INF/MANIFEST.MF", "built 1", "a/A.class", "AAA"), T1);
        byte[] jar2 = zipBytes(entries("META-INF/MANIFEST.MF", "built 2", "a/A.class", "AAA"), T1);
        PatchTree t1 = scanner().scan(zip(dir.resolve("1.war"), entries("WEB-INF/lib/x.jar", jar1), T1));
        PatchTree t2 = scanner().scan(zip(dir.resolve("2.war"), entries("WEB-INF/lib/x.jar", jar2), T1));

        assertThat(t1).isEqualTo(t2);
    }

    @Test
    void nestedJarIsNotExpandedIntoTree() throws Exception {
        byte[] jar = zipBytes(entries("a/A.class", "AAA"), T1);
        PatchTree t = scanner().scan(zip(dir.resolve("1.war"), entries("WEB-INF/lib/x.jar", jar), T1));

        assertThat(t.hashes().keySet()).containsExactly("WEB-INF/lib/x.jar");
    }

    private Path bundle() throws IOException {
        byte[] jar = zipBytes(entries("a/A.class", "AAA"), T1);
        byte[] daegu = zipBytes(entries("WEB-INF/classes/D.class", "d", "META-INF/MANIFEST.MF", "m",
                "WEB-INF/lib/x.jar", jar), T1);
        byte[] sangju = zipBytes(entries("WEB-INF/classes/S.class", "s"), T1);
        return zip(dir.resolve("bundle.zip"), entries(
                "hm-dash-sangju.war", sangju, "hm-dash-daegu.war", daegu, "README.txt", "readme"), T1);
    }

    @Test
    void bundleZipPrefixesWarBaseName() throws Exception {
        PatchTree tree = scanner().scan(bundle());

        assertThat(tree.shape()).isEqualTo("bundle:[hm-dash-daegu.war, hm-dash-sangju.war]");
        assertThat(tree.hashes().keySet()).containsExactly(
                "README.txt",
                "hm-dash-daegu/WEB-INF/classes/D.class",
                "hm-dash-daegu/WEB-INF/lib/x.jar",
                "hm-dash-sangju/WEB-INF/classes/S.class");
    }

    @Test
    void bundleNonWarFileKeepsItsPath() throws Exception {
        assertThat(scanner().scan(bundle()).hashes()).containsKey("README.txt");
    }

    @Test
    void bundleWarInSubfolderUsesLastSegmentOnly() throws Exception {
        byte[] war = zipBytes(entries("index.jsp", "i"), T1);
        Path zip = zip(dir.resolve("b.zip"), entries("dist/x.war", war), T1);

        PatchTree tree = scanner().scan(zip);

        assertThat(tree.shape()).isEqualTo("bundle:[x.war]");
        assertThat(tree.hashes().keySet()).containsExactly("x/index.jsp");
    }

    @Test
    void tarGzIsRejectedWithMessage() throws Exception {
        Path tgz = Files.write(dir.resolve("a.tar.gz"), new byte[] {1});
        Path tgz2 = Files.write(dir.resolve("a.TGZ"), new byte[] {1});

        assertThatThrownBy(() -> scanner().scan(tgz))
                .isInstanceOf(PatchException.class).hasMessage("tar.gz 묶음은 부분 패치를 지원하지 않습니다");
        assertThatThrownBy(() -> scanner().scan(tgz2))
                .isInstanceOf(PatchException.class).hasMessage("tar.gz 묶음은 부분 패치를 지원하지 않습니다");
    }

    @Test
    void unsupportedFormatIsRejected() throws Exception {
        Path f = Files.write(dir.resolve("a.exe"), new byte[] {1});

        assertThatThrownBy(() -> scanner().scan(f))
                .isInstanceOf(PatchException.class).hasMessage("지원하지 않는 산출물 형식입니다: a.exe");
    }

    @Test
    void entryEscapingRootIsRejected() throws Exception {
        Path up = zip(dir.resolve("up.war"), entries("../evil", "x"), T1);
        Path abs = zip(dir.resolve("abs.war"), entries("/abs", "x"), T1);

        assertThatThrownBy(() -> scanner().scan(up))
                .isInstanceOf(PatchException.class).hasMessage("압축 항목 경로가 잘못됐습니다: ../evil");
        assertThatThrownBy(() -> scanner().scan(abs))
                .isInstanceOf(PatchException.class).hasMessage("압축 항목 경로가 잘못됐습니다: /abs");
    }

    @Test
    void extractWritesOnlyRequestedPathsFromSingleWar() throws Exception {
        byte[] jar = zipBytes(entries("a/A.class", "AAA"), T1);
        Path war = zip(dir.resolve("a.war"), entries(
                "WEB-INF/classes/A.class", "AAA", "index.jsp", "idx", "WEB-INF/lib/x.jar", jar), T1);
        Path out = dir.resolve("out");

        scanner().extract(war, List.of("index.jsp", "WEB-INF/lib/x.jar"), out);

        assertThat(Files.readAllBytes(out.resolve("index.jsp"))).isEqualTo(b("idx"));
        assertThat(Files.readAllBytes(out.resolve("WEB-INF/lib/x.jar"))).isEqualTo(jar);
        assertThat(files(out)).containsExactlyInAnyOrder("index.jsp", "WEB-INF/lib/x.jar");
    }

    @Test
    void extractWritesOnlyRequestedPathsFromBundle() throws Exception {
        Path out = dir.resolve("out");

        scanner().extract(bundle(), List.of("README.txt", "hm-dash-daegu/WEB-INF/classes/D.class"), out);

        assertThat(Files.readAllBytes(out.resolve("README.txt"))).isEqualTo(b("readme"));
        assertThat(Files.readAllBytes(out.resolve("hm-dash-daegu/WEB-INF/classes/D.class"))).isEqualTo(b("d"));
        assertThat(files(out)).containsExactlyInAnyOrder("README.txt", "hm-dash-daegu/WEB-INF/classes/D.class");
    }

    @Test
    void extractBundleNestedJarIsByteIdentical() throws Exception {
        byte[] jar = zipBytes(entries("a/A.class", "AAA"), T1);
        byte[] war = zipBytes(entries("WEB-INF/lib/x.jar", jar), T1);
        Path zip = zip(dir.resolve("b.zip"), entries("x.war", war), T1);
        Path out = dir.resolve("out");

        scanner().extract(zip, List.of("x/WEB-INF/lib/x.jar"), out);

        assertThat(Files.readAllBytes(out.resolve("x/WEB-INF/lib/x.jar"))).isEqualTo(jar);
    }

    @Test
    void extractMissingPathFails() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries("index.jsp", "i"), T1);

        assertThatThrownBy(() -> scanner().extract(war, List.of("nope.jsp"), dir.resolve("out")))
                .isInstanceOf(PatchException.class).hasMessage("패치 항목을 찾지 못했습니다: nope.jsp");
    }

    @Test
    void extractRejectsRequestedPathEscapingDestination() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries("index.jsp", "i"), T1);

        assertThatThrownBy(() -> scanner().extract(war, List.of("../x"), dir.resolve("out")))
                .isInstanceOf(PatchException.class).hasMessageContaining("압축 항목 경로가 잘못됐습니다");
    }

    @Test
    void extractRejectsEscapingEntryInArtifact() throws Exception {
        Path war = zip(dir.resolve("a.war"), entries("../evil", "x", "index.jsp", "i"), T1);

        assertThatThrownBy(() -> scanner().extract(war, List.of("index.jsp"), dir.resolve("out")))
                .isInstanceOf(PatchException.class).hasMessage("압축 항목 경로가 잘못됐습니다: ../evil");
    }

    @Test
    void globSemantics() {
        PatchScanner s = new PatchScanner(List.of("a/*.txt", "b/**", "**/c.xml", "exact.txt"));

        assertThat(s.isIgnored("a/x.txt")).isTrue();
        assertThat(s.isIgnored("a/d/x.txt")).isFalse();
        assertThat(s.isIgnored("b/d/e/x")).isTrue();
        assertThat(s.isIgnored("c.xml")).isTrue();
        assertThat(s.isIgnored("x/y/c.xml")).isTrue();
        assertThat(s.isIgnored("exact.txt")).isTrue();
        assertThat(s.isIgnored("exact.txt2")).isFalse();
        assertThat(s.isIgnored("a.txt")).isFalse();
    }

    private static List<String> files(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
        }
    }
}
