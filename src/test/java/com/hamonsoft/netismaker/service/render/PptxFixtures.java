package com.hamonsoft.netismaker.service.render;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

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

    /**
     * 예시 요청과 같은 모양의 2슬라이드 pptx — 슬라이드마다 캡처 그림 1장(잡음 PNG, 10KB 넘음 → MediaExtractor가 남긴다)과
     * 첫 슬라이드 그림 위에 채우기 없는 빨간 테두리 사각형(그림 밖 도형이라 원본 그림에는 안 보인다).
     */
    static Path withPicturesAndRedBox(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve(name);
            try (XMLSlideShow ppt = new XMLSlideShow(); OutputStream os = Files.newOutputStream(out)) {
                ppt.setPageSize(new java.awt.Dimension(960, 540));

                XSLFSlide first = ppt.createSlide();
                addPicture(ppt, first, noisyPng(1), new Rectangle(80, 80, 400, 300));
                XSLFAutoShape box = first.createAutoShape();
                box.setShapeType(ShapeType.RECT);
                box.setAnchor(new Rectangle(120, 120, 200, 120));
                box.setFillColor(null);
                box.setLineColor(Color.RED);
                box.setLineWidth(4.0);

                XSLFSlide second = ppt.createSlide();
                addPicture(ppt, second, noisyPng(2), new Rectangle(280, 120, 400, 300));
                ppt.write(os);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addPicture(XMLSlideShow ppt, XSLFSlide slide, byte[] png, Rectangle anchor) {
        XSLFPictureData data = ppt.addPicture(png, PictureData.PictureType.PNG);
        XSLFPictureShape pic = slide.createPicture(data);
        pic.setAnchor(anchor);
    }

    /** 압축이 잘 안 되는 잡음 그림 — seed가 다르면 내용이 달라 POI가 같은 그림으로 합치지 않는다. */
    private static byte[] noisyPng(long seed) throws IOException {
        BufferedImage img = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void addTextSlide(XMLSlideShow ppt, String text) {
        XSLFSlide slide = ppt.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(50, 50, 400, 100));
        box.setText(text);
    }
}
