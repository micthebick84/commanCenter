package com.hamonsoft.netismaker.workerdaemon.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchZipWriterTest {

    private static final String BEFORE = "a".repeat(40);
    private static final String AFTER = "b".repeat(40);

    @TempDir
    Path tmp;

    private PatchInfoDoc doc(PatchDelta delta) {
        return new PatchInfoDoc(7, "로그인 수정", "repo-a", BEFORE, AFTER,
                OffsetDateTime.parse("2026-11-10T10:00:00+09:00"), "mvn -q package", delta, List.of("src/A.java"));
    }

    private Path patchDir() throws IOException {
        Path dir = tmp.resolve("patchdir");
        Files.createDirectories(dir.resolve("WEB-INF/classes"));
        Files.createDirectories(dir.resolve("jsp"));
        Files.writeString(dir.resolve("WEB-INF/classes/A.class"), "x");
        Files.writeString(dir.resolve("jsp/login.jsp"), "y");
        return dir;
    }

    private Set<String> names(Path zip) throws IOException {
        Set<String> out = new HashSet<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().map(ZipEntry::getName).forEach(out::add);
        }
        return out;
    }

    private String read(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void zipHasInfoDeletedAndPatchTree() throws Exception {
        Path zip = tmp.resolve("out.zip");
        PatchZipWriter.write(zip, patchDir(),
                doc(new PatchDelta(List.of("jsp/login.jsp"), List.of("WEB-INF/classes/A.class"), List.of("old.jsp"))));

        assertThat(names(zip)).containsExactlyInAnyOrder("PATCH-INFO.txt", "DELETED.txt",
                "patch/WEB-INF/classes/A.class", "patch/jsp/login.jsp");
        assertThat(read(zip, "DELETED.txt")).isEqualTo("old.jsp\n");
    }

    @Test
    void deletedTxtSaysNoneWhenEmpty() throws Exception {
        Path zip = tmp.resolve("out.zip");
        PatchZipWriter.write(zip, patchDir(),
                doc(new PatchDelta(List.of("jsp/login.jsp"), List.of("WEB-INF/classes/A.class"), List.of())));

        assertThat(read(zip, "DELETED.txt")).contains("(없음)");
    }

    @Test
    void infoTextHasWarningAndBothCommits() {
        String text = PatchZipWriter.infoText(
                doc(new PatchDelta(List.of("jsp/login.jsp"), List.of("WEB-INF/classes/A.class"), List.of())));

        assertThat(text).contains("※ 이 패치는 '패치 전' 커밋 상태의 배포본에만 적용한다.");
        assertThat(text).contains("패치 전 커밋: " + BEFORE).contains("패치 후 커밋: " + AFTER);
        assertThat(text).contains("추가 1 · 변경 1 · 삭제 0");
        assertThat(text).contains("작업: #7 로그인 수정").contains("레포: repo-a")
                .contains("만든 시각: 2026-11-10T10:00:00+09:00").contains("빌드 명령: mvn -q package");
        assertThat(text).contains("[삭제]\n(없음)").contains("[소스 변경(참고)]\nsrc/A.java");
        assertThat(text).doesNotContain("\r");
    }

    @Test
    void entryNamesUseForwardSlashOnWindows() throws Exception {
        Path zip = tmp.resolve("out.zip");
        PatchZipWriter.write(zip, patchDir(), doc(new PatchDelta(List.of(), List.of(), List.of())));

        assertThat(names(zip)).noneMatch(n -> n.contains("\\"));
        assertThat(names(zip)).contains("patch/WEB-INF/classes/A.class");
    }
}
