package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.config.RenderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlideRendererTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Path FAKE_SOFFICE = Path.of("fake", "soffice");

    @TempDir
    Path tmp;

    private static RenderProperties props(int timeoutSeconds) {
        return new RenderProperties(true, "", List.of("pptx", "ppt"), timeoutSeconds, 30, 1600, 20, 180);
    }

    private SlideRenderer renderer(int timeoutSeconds, ProcessStarter starter) {
        RenderTargetPolicy policy = new RenderTargetPolicy(props(timeoutSeconds), Optional.of(FAKE_SOFFICE));
        return new SlideRenderer(props(timeoutSeconds), policy, starter, tmp.resolve("work-root"));
    }

    /** 명령은 무시하고 주어진 OS 명령을 작업 폴더에서 돌린다(출력은 파일로). */
    private static Process run(List<String> fake, Path workDir) throws IOException {
        return new ProcessBuilder(fake).directory(workDir.toFile())
                .redirectErrorStream(true).redirectOutput(workDir.resolve("fake.log").toFile()).start();
    }

    private static List<String> exitWith(int code) {
        return WINDOWS ? List.of("cmd", "/c", "exit " + code) : List.of("sh", "-c", "exit " + code);
    }

    private static List<String> entries(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Path source() throws IOException {
        Path src = tmp.resolve("원본 \"덱\".pptx".replace("\"", "'"));
        Files.writeString(src, "pptx-bytes");
        return src;
    }

    @Test
    void command_격리_프로필과_사본_경로() {
        SlideRenderer r = renderer(1, (c, d) -> { throw new IOException("unused"); });
        Path work = tmp.resolve("w");

        List<String> cmd = r.command(FAKE_SOFFICE, work, "pptx");

        assertThat(cmd).containsExactly(
                FAKE_SOFFICE.toString(), "--headless", "--norestore", "--nologo",
                "-env:UserInstallation=" + work.resolve("profile").toUri(),
                "--convert-to", "pdf", "--outdir", work.toString(), work.resolve("input.pptx").toString());
        assertThat(cmd.get(4)).startsWith("-env:UserInstallation=file:///");
    }

    @Test
    void timeout_트리_종료() throws Exception {
        List<ProcessHandle> children = new ArrayList<>();
        ProcessStarter starter = (cmd, workDir) -> {
            List<String> fake = WINDOWS
                    ? List.of("cmd", "/c", "ping -n 30 127.0.0.1")
                    : List.of("sh", "-c", "sleep 30 & sleep 30");
            Process p = run(fake, workDir);
            // 자식이 뜰 때까지 기다렸다가 잡아 둔다 — 시간 초과 뒤 살아 있지 않은지 본다
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                List<ProcessHandle> d = p.descendants().toList();
                if (!d.isEmpty()) { children.addAll(d); break; }
                try { Thread.sleep(20); } catch (InterruptedException e) { throw new IOException(e); }
            }
            return p;
        };
        SlideRenderer r = renderer(1, starter);
        Path out = tmp.resolve("out");

        assertThatThrownBy(() -> r.render(source(), "pptx", out))
                .isInstanceOf(RenderException.class)
                .hasMessage("렌더링 시간 초과(1초)");

        assertThat(children).isNotEmpty();
        for (ProcessHandle c : children) {
            c.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(c.isAlive()).as("자식 %d", c.pid()).isFalse();
        }
        assertThat(entries(tmp.resolve("work-root"))).isEmpty();
    }

    @Test
    void 종료_코드가_0이_아니면_PDF_변환_실패() throws IOException {
        SlideRenderer r = renderer(10, (cmd, workDir) -> run(exitWith(3), workDir));

        assertThatThrownBy(() -> r.render(source(), "pptx", tmp.resolve("out")))
                .isInstanceOf(RenderException.class).hasMessage("PDF 변환 실패");
        assertThat(entries(tmp.resolve("work-root"))).isEmpty();
    }

    @Test
    void PDF가_없으면_PDF_변환_실패() {
        SlideRenderer r = renderer(10, (cmd, workDir) -> run(exitWith(0), workDir));

        assertThatThrownBy(() -> r.render(source(), "pptx", tmp.resolve("out")))
                .isInstanceOf(RenderException.class).hasMessage("PDF 변환 실패");
    }

    @Test
    void 깨진_PDF면_그림_변환_실패() throws IOException {
        SlideRenderer r = renderer(10, (cmd, workDir) -> {
            Files.writeString(workDir.resolve("input.pdf"), "broken");
            return run(exitWith(0), workDir);
        });

        assertThatThrownBy(() -> r.render(source(), "pptx", tmp.resolve("out")))
                .isInstanceOf(RenderException.class).hasMessage("그림 변환 실패");
        assertThat(entries(tmp.resolve("work-root"))).isEmpty();
    }

    @Test
    void 성공하면_슬라이드와_원본_그림을_쓰고_예전_출력은_비운다() throws Exception {
        Path deck = PptxFixtures.withPicturesAndRedBox(tmp.resolve("fx"), "deck.pptx");
        List<List<String>> seen = new ArrayList<>();
        SlideRenderer r = renderer(10, (cmd, workDir) -> {
            seen.add(cmd);
            // soffice 대신 2페이지 PDF를 만든다
            PdfPageRasterizerTest.pdf(workDir, 2);
            return run(exitWith(0), workDir);
        });
        Path out = tmp.resolve("out");
        Files.createDirectories(out.resolve("media"));
        Files.writeString(out.resolve("slide-009.png"), "stale");
        Files.writeString(out.resolve("media").resolve("old.png"), "stale");

        RenderResult result = r.render(deck, "PPTX", out);

        assertThat(result).isEqualTo(new RenderResult(2, 2, 2));
        assertThat(entries(out)).containsExactly("media", "slide-001.png", "slide-002.png");
        assertThat(entries(out.resolve("media"))).hasSize(2);
        assertThat(seen.get(0).get(seen.get(0).size() - 1)).endsWith("input.pptx");
        assertThat(entries(tmp.resolve("work-root"))).isEmpty();
    }

    @Test
    void ppt는_원본_그림을_꺼내지_않는다() throws Exception {
        SlideRenderer r = renderer(10, (cmd, workDir) -> {
            PdfPageRasterizerTest.pdf(workDir, 1);
            return run(exitWith(0), workDir);
        });
        Path src = tmp.resolve("old.ppt");
        Files.writeString(src, "ppt-bytes");

        RenderResult result = r.render(src, "ppt", tmp.resolve("out"));

        assertThat(result).isEqualTo(new RenderResult(1, 1, 0));
        assertThat(entries(tmp.resolve("out"))).containsExactly("slide-001.png");
    }
}
