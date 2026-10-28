package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 soffice로 픽스처 pptx(그림 2장 + 빨간 사각형)를 렌더링한다 — LibreOffice가 없는 PC·CI에서는 건너뛴다
 * (스펙 2026-10-27 §11).
 */
class SlideRendererSofficeIT {

    @TempDir
    Path tmp;

    /** 따옴표가 든 원본 이름 — Windows는 파일 이름에 `"`를 못 쓰므로 작은따옴표로 대신한다. */
    private static String fixtureName(Path dir) {
        String name = "대시보드 \"업그레이드\" 안.pptx";
        try {
            dir.resolve(name);
            return name;
        } catch (InvalidPathException e) {
            return "대시보드 '업그레이드' 안.pptx";
        }
    }

    @Test
    void soffice로_슬라이드_2장과_원본_그림_2장() throws Exception {
        RenderProperties props = new RenderProperties(true, System.getenv().getOrDefault("SOFFICE_PATH", ""),
                List.of("pptx", "ppt"), 120, 30, 1600, 20, 180);
        Optional<Path> soffice = SofficeLocator.resolveFromEnvironment(props.sofficePath());
        assumeTrue(soffice.isPresent(), "soffice 없음 — 건너뜀");

        Path deck = PptxFixtures.withPicturesAndRedBox(tmp.resolve("src"), fixtureName(tmp));
        SlideRenderer renderer = new SlideRenderer(props, new RenderTargetPolicy(props, soffice),
                new ProcessBuilderStarter(), tmp.resolve(".render-work"));
        Path out = tmp.resolve("deck.render");

        RenderResult r = renderer.render(deck, "pptx", out);

        assertThat(r.pageCount()).isEqualTo(2);
        assertThat(r.totalPages()).isEqualTo(2);
        assertThat(r.mediaCount()).isEqualTo(2);
        assertThat(out.resolve("slide-001.png")).isRegularFile();
        assertThat(out.resolve("slide-002.png")).isRegularFile();
        assertThat(out.resolve("media").toFile().list()).hasSize(2);
    }
}
