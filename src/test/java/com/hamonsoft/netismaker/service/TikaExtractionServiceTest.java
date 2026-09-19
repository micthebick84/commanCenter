package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.OfficeFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스펙 2026-09-13 §5.2 TikaExtractionService: 오피스 확장자만, 200,000자 절단, 예외 절대 미전파.
 */
class TikaExtractionServiceTest {

    @TempDir Path tmp;

    private final TikaExtractionService tika = new TikaExtractionService();

    private Path write(String name, byte[] bytes) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    @Test
    void docx에서_본문_텍스트를_추출한다() throws Exception {
        Path p = write("설계.docx", OfficeFixtures.minimalDocx("설계 문서 본문 — 인증 흐름"));
        Optional<String> text = tika.extractText(p, "설계.docx");
        assertThat(text).isPresent();
        assertThat(text.get()).contains("설계 문서 본문 — 인증 흐름");
    }

    @Test
    void 상한을_넘는_문서는_핸들러가_끊고_부분_텍스트에_표식을_붙인다() throws Exception {
        // 상한(200,000자)보다 큰 본문 — 핸들러 상한에서 WriteLimitReachedException으로 끊겨야 한다(메모리 상한 목적)
        String huge = "가".repeat(TikaExtractionService.MAX_CHARS + 5_000);
        Path p = write("긴문서.docx", OfficeFixtures.minimalDocx(huge));
        Optional<String> text = tika.extractText(p, "긴문서.docx");
        assertThat(text).isPresent();
        assertThat(text.get()).endsWith(TikaExtractionService.TRUNCATED_SUFFIX);
        assertThat(text.get().length())
                .isLessThanOrEqualTo(TikaExtractionService.MAX_CHARS + TikaExtractionService.TRUNCATED_SUFFIX.length());
    }

    @Test
    void xlsx에서_셀_텍스트를_추출한다() throws Exception {
        Path p = write("데이터.xlsx", OfficeFixtures.minimalXlsx("셀값 A1"));
        Optional<String> text = tika.extractText(p, "데이터.xlsx");
        assertThat(text).isPresent();
        assertThat(text.get()).contains("셀값 A1");
    }

    @Test
    void 오피스_확장자가_아니면_파일을_열지_않고_empty다() throws Exception {
        Path txt = write("메모.txt", "평문 내용".getBytes(StandardCharsets.UTF_8));
        assertThat(tika.extractText(txt, "메모.txt")).isEmpty();
        Path pdf = write("문서.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        assertThat(tika.extractText(pdf, "문서.pdf")).isEmpty();
        // 확장자 판정은 원본 파일명 기준, 대소문자 무시
        Path docxUpper = write("설계.DOCX", OfficeFixtures.minimalDocx("대문자 확장자"));
        assertThat(tika.extractText(docxUpper, "설계.DOCX")).isPresent();
    }

    @Test
    void 손상된_파일은_예외_없이_empty다() throws Exception {
        Path p = write("깨짐.docx", new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02, 0x03, 0x7F, 0x7F});
        assertThatCode(() -> assertThat(tika.extractText(p, "깨짐.docx")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void 없는_파일도_예외_없이_empty다() {
        assertThatCode(() -> assertThat(tika.extractText(tmp.resolve("없음.pptx"), "없음.pptx")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void 내용이_비어있으면_empty다() throws Exception {
        Path p = write("빈.docx", OfficeFixtures.minimalDocx("   "));
        assertThat(tika.extractText(p, "빈.docx")).isEmpty();
    }

    @Test
    void 결과가_상한을_넘으면_절단하고_표식을_붙인다() {
        String huge = "가".repeat(TikaExtractionService.MAX_CHARS + 10);
        String capped = TikaExtractionService.cap(huge);
        assertThat(capped).hasSize(TikaExtractionService.MAX_CHARS + TikaExtractionService.TRUNCATED_SUFFIX.length())
                .endsWith(TikaExtractionService.TRUNCATED_SUFFIX);
        assertThat(TikaExtractionService.cap("짧음")).isEqualTo("짧음");
    }

    @Test
    void 오피스_확장자_목록은_스펙과_같다() {
        assertThat(TikaExtractionService.OFFICE_EXTENSIONS)
                .containsExactlyInAnyOrder("docx", "doc", "xlsx", "xls", "pptx", "ppt", "hwp", "hwpx");
    }
}
