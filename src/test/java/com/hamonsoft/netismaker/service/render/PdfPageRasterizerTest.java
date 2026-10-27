package com.hamonsoft.netismaker.service.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPageRasterizerTest {

    @TempDir
    Path tmp;

    /** 슬라이드 비율(16:9, 960×540pt) 페이지 n장짜리 PDF. */
    static Path pdf(Path dir, int pages) throws IOException {
        Path out = dir.resolve("input.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(new PDRectangle(960, 540)));
            doc.save(out.toFile());
        }
        return out;
    }

    @Test
    void 장수_상한과_폭() throws IOException {
        Path out = tmp.resolve("out");

        RenderResult r = PdfPageRasterizer.rasterize(pdf(tmp, 3), out, 1600, 2);

        assertThat(r).isEqualTo(new RenderResult(2, 3, 0));
        BufferedImage img = ImageIO.read(out.resolve("slide-001.png").toFile());
        assertThat(img.getWidth()).isEqualTo(1600);
        assertThat(img.getHeight()).isEqualTo(900);
        assertThat(out.resolve("slide-002.png")).exists();
        assertThat(out.resolve("slide-003.png")).doesNotExist();
    }

    @Test
    void 아주_긴_페이지는_높이를_폭의_4배로_묶고_좁게_그린다() throws IOException {
        Path in = tmp.resolve("tall.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(100, 5600)));
            doc.save(in.toFile());
        }
        Path out = tmp.resolve("tall");

        assertThat(PdfPageRasterizer.rasterize(in, out, 1600, 30)).isEqualTo(new RenderResult(1, 1, 0));

        BufferedImage img = ImageIO.read(out.resolve("slide-001.png").toFile());
        assertThat(img.getHeight()).isLessThanOrEqualTo(4 * 1600);
        assertThat(img.getWidth()).isLessThan(1600);
    }

    @Test
    void 깨진_PDF는_IOException() throws IOException {
        Path bad = tmp.resolve("bad.pdf");
        Files.writeString(bad, "not a pdf");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PdfPageRasterizer.rasterize(bad, tmp.resolve("o"), 1600, 30))
                .isInstanceOf(IOException.class);
    }
}
