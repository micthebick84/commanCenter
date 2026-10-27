package com.hamonsoft.netismaker.service.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF → 페이지별 PNG (스펙 2026-10-27 §6 변환 2단계). 폭 widthPx에 맞춰 배율을 정하고, 페이지를 하나씩 그려
 * 쓰고 버린다(메모리에 한 장만). maxPages를 넘는 페이지는 그리지 않고 totalPages로만 알린다.
 */
public final class PdfPageRasterizer {

    static final String NAME_FORMAT = "slide-%03d.png";

    private PdfPageRasterizer() {}

    public static RenderResult rasterize(Path pdf, Path outDir, int widthPx, int maxPages) throws IOException {
        Files.createDirectories(outDir);
        // 파싱 버퍼는 임시 파일로 — 큰 PDF가 힙을 잡아먹지 않게
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int total = doc.getNumberOfPages();
            int count = Math.min(total, Math.max(maxPages, 0));
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < count; i++) {
                BufferedImage img = renderer.renderImage(i, scaleFor(doc.getPage(i), widthPx), ImageType.RGB);
                try {
                    Path target = outDir.resolve(String.format(NAME_FORMAT, i + 1));
                    if (!ImageIO.write(img, "png", target.toFile())) {
                        throw new IOException("PNG 인코더 없음");
                    }
                } finally {
                    img.flush();
                }
            }
            return new RenderResult(count, total, 0);
        }
    }

    /**
     * 그려진 폭이 widthPx가 되는 배율. PDFRenderer는 floor(폭×배율)로 픽셀 폭을 정하므로 0.5px를 더해 부동소수 오차로
     * 1px 모자라는 것을 막는다. 90·270도 회전 페이지는 세로 길이가 그려진 폭이다.
     */
    private static float scaleFor(PDPage page, int widthPx) {
        PDRectangle crop = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        float drawnWidth = (rotation == 90 || rotation == 270) ? crop.getHeight() : crop.getWidth();
        if (drawnWidth <= 0) drawnWidth = PDRectangle.LETTER.getWidth();
        return (widthPx + 0.5f) / drawnWidth;
    }
}
