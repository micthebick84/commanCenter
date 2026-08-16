package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStorageTest {

    @TempDir Path tmp;

    /** 테스트용 소형 한도: 최대 2개, 파일당 1MB, 합계 1MB. */
    private AttachmentStorage storage() {
        return new AttachmentStorage(tmp.toString(), 2, 1, 1);
    }

    private static MockMultipartFile file(String name, int bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", new byte[bytes]);
    }

    // ── validate ──────────────────────────────────────────────

    @Test
    void 개수_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(
                file("a.txt", 1), file("b.txt", 1), file("c.txt", 1))))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("최대 2개");
    }

    @Test
    void 파일당_크기_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("big.bin", 1024 * 1024 + 1))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("1MB");
    }

    @Test
    void 합계_크기_초과는_400() {
        assertThatThrownBy(() -> storage().validate(List.of(
                file("a.bin", 600 * 1024), file("b.bin", 600 * 1024))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("합계");
    }

    @Test
    void 빈_파일은_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("empty.txt", 0))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("빈 파일");
    }

    @Test
    void 차단_확장자는_대소문자_무시하고_400() {
        assertThatThrownBy(() -> storage().validate(List.of(file("evil.EXE", 1))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("허용되지 않는 파일 형식");
        assertThatThrownBy(() -> storage().validate(List.of(file("run.sh", 1))))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void 정상_파일들은_통과한다() {
        storage().validate(List.of(file("요구사항.pdf", 100), file("화면.png", 100)));
    }

    // ── sanitize ──────────────────────────────────────────────

    @Test
    void sanitize는_경로_구분자와_탈출_시퀀스를_제거한다() {
        assertThat(AttachmentStorage.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentStorage.sanitize("a/b\\c.txt")).isEqualTo("c.txt");
        assertThat(AttachmentStorage.sanitize("한글 파일명.pdf")).isEqualTo("한글 파일명.pdf");
        assertThat(AttachmentStorage.sanitize("bad name\n.txt")).isEqualTo("bad name.txt");
        assertThat(AttachmentStorage.sanitize("  ")).isEqualTo("file");
        assertThat(AttachmentStorage.sanitize(null)).isEqualTo("file");
        // 200자 초과 → 뒤쪽(확장자 포함)을 보존
        String longName = "a".repeat(300) + ".pdf";
        String out = AttachmentStorage.sanitize(longName);
        assertThat(out).hasSize(200).endsWith(".pdf");
    }

    // ── relativePath / write / resolve / delete ──────────────

    @Test
    void relativePath_형식은_task디렉터리_순번_파일명이다() {
        assertThat(storage().relativePath(42L, 1, "요구사항.pdf"))
                .isEqualTo("task-42/1-요구사항.pdf");
    }

    @Test
    void write는_디렉터리를_만들고_내용을_저장한다() throws Exception {
        AttachmentStorage s = storage();
        MockMultipartFile f = new MockMultipartFile("files", "a.txt", "text/plain", "내용".getBytes());
        s.write("task-1/1-a.txt", f);
        assertThat(Files.readString(tmp.resolve("task-1/1-a.txt"))).isEqualTo("내용");
    }

    @Test
    void resolve는_루트_탈출을_거부한다() {
        assertThatThrownBy(() -> storage().resolve("../outside.txt"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void absolutePathOf는_루트가_prefix인_절대경로를_준다() {
        String abs = storage().absolutePathOf("task-1/1-a.txt");
        assertThat(abs).startsWith(tmp.toAbsolutePath().toString()).endsWith("1-a.txt");
    }

    @Test
    void deleteQuietly는_없는_파일에도_예외를_던지지_않는다() {
        storage().deleteQuietly("task-9/9-none.txt");
    }
}
