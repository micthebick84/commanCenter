package com.hamonsoft.netismaker.service.render;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 테스트용 pptx 생성기 (Apache POI — Tika가 끌어온다). 렌더링 관련 테스트가 공용으로 쓴다. */
public final class PptxFixtures {

    private PptxFixtures() {}

    /** 글 상자가 하나씩 든 2슬라이드 pptx — 본문 "첫째 슬라이드"·"둘째 슬라이드". */
    public static Path twoSlides(Path dir) {
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("deck.pptx");
            try (XMLSlideShow ppt = new XMLSlideShow(); OutputStream os = Files.newOutputStream(out)) {
                addTextSlide(ppt, "첫째 슬라이드");
                addTextSlide(ppt, "둘째 슬라이드");
                ppt.write(os);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addTextSlide(XMLSlideShow ppt, String text) {
        XSLFSlide slide = ppt.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(50, 50, 400, 100));
        box.setText(text);
    }
}
