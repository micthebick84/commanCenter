package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStorageTest {

    @TempDir Path tmp;

    /** DEL(0x7F) — 제어문자지만 String.trim()(<= 0x20만 제거) 으로는 안 지워진다. */
    private static final String DEL = String.valueOf((char) 0x7F);

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

    /*
     * 검증은 원본이 아니라 sanitize 결과(=디스크에 실제로 남는 이름)에서 확장자를 뽑아야 한다.
     * 원본 기준이면 아래 세 이름은 "무해한 확장자"로 검증을 통과한 뒤 .sh/.exe 로 저장되는
     * 우회가 된다. 케이스마다 별도 테스트 — 한 케이스만 살아나는 회귀도 잡히도록.
     */

    private void 차단됨(String filename) {
        assertThatThrownBy(() -> storage().validate(List.of(file(filename, 1))))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("허용되지 않는 파일 형식");
    }

    /** 끝 공백: 원본 확장자는 "sh "(미차단)이지만 trim 후 "sh"로 저장된다. */
    @Test
    void 끝_공백으로_확장자를_감춰도_400() {
        차단됨("evil.sh ");
    }

    /** 끝 제어문자(DEL 0x7F): trim으론 안 지워지고 \p{Cntrl} 치환에서만 지워진다. */
    @Test
    void 끝_제어문자로_확장자를_감춰도_400() {
        차단됨("evil.exe" + DEL);
    }

    /** 끝 마침표: 원본 확장자는 ""(미차단)이지만 저장 시 "evil.sh"가 된다. */
    @Test
    void 끝_마침표로_확장자를_감춰도_400() {
        차단됨("evil.sh.");
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
        // 240바이트 초과 → 뒤쪽(확장자 포함)을 보존. ASCII는 1바이트=1자.
        String longName = "a".repeat(300) + ".pdf";
        String out = AttachmentStorage.sanitize(longName);
        assertThat(out).hasSize(240).endsWith(".pdf");
    }

    @Test
    void sanitize는_글자수가_아니라_UTF8_바이트로_자른다() {
        // 한글 200자 = UTF-8 600바이트. 글자수(200) 기준이면 그대로 통과해 Linux(ext4)
        // 255바이트 한도를 넘고, 서버에서만 저장이 실패한다.
        String korean = "가".repeat(200) + ".pdf";
        String out = AttachmentStorage.sanitize(korean);
        assertThat(out.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(AttachmentStorage.MAX_FILENAME_BYTES);
        assertThat(out).endsWith(".pdf");
        assertThat(out.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(200);

        // 서로게이트 페어(이모지 4바이트)는 코드포인트 경계에서만 잘려야 한다 —
        // char 단위로 자르면 깨진 상위/하위 서로게이트가 파일명에 남는다.
        String emoji = "😀".repeat(100) + ".pdf";
        String cut = AttachmentStorage.sanitize(emoji);
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(AttachmentStorage.MAX_FILENAME_BYTES);
        assertThat(cut).endsWith(".pdf");
        // codePoints()는 정상 페어를 하나의 코드포인트로 합치므로, 남은 서로게이트는
        // 곧 "짝이 깨진" 것뿐이다.
        assertThat(cut.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isTrue();
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
    void deleteQuietly는_실제로_파일을_지운다() throws Exception {
        AttachmentStorage s = storage();
        s.write("task-9/9-gone.txt", new MockMultipartFile(
                "files", "gone.txt", "text/plain", "내용".getBytes(StandardCharsets.UTF_8)));
        Path p = tmp.resolve("task-9/9-gone.txt");
        assertThat(Files.exists(p)).isTrue();

        s.deleteQuietly("task-9/9-gone.txt");

        assertThat(Files.exists(p)).isFalse();
    }

    @Test
    void deleteQuietly는_없는_파일에도_예외를_던지지_않는다() {
        assertThatCode(() -> storage().deleteQuietly("task-9/9-none.txt"))
                .doesNotThrowAnyException();
    }
}
