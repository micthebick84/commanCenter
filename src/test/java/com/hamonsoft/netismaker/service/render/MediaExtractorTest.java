package com.hamonsoft.netismaker.service.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MediaExtractorTest {

    @TempDir
    Path tmp;

    private static byte[] noise(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private Path pptx() throws IOException {
        Path out = tmp.resolve("deck.pptx");
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "ppt/slides/slide1.xml", "<x/>".getBytes());
            put(zip, "ppt/media/image1.png", noise(20 * 1024, 1));
            put(zip, "ppt/media/../../evil.png", noise(20 * 1024, 2));
            put(zip, "ppt/media/icon.png", noise(2 * 1024, 3));
            put(zip, "ppt/media/v.emf", noise(20 * 1024, 4));
        }
        return out;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void extractMedia_경로_무시_및_크기_상한() throws IOException {
        Path deck = pptx();
        Path mediaDir = tmp.resolve("out").resolve("media");

        int n = MediaExtractor.extract(deck, mediaDir, 20);

        assertThat(n).isEqualTo(2);
        assertThat(names(mediaDir)).containsExactly("evil.png", "image1.png");
        // 상위 폴더로 새어 나간 파일이 없다
        assertThat(names(tmp.resolve("out"))).containsExactly("media");
        assertThat(names(tmp)).containsExactly("deck.pptx", "out");
    }

    @Test
    void maxMedia_상한() throws IOException {
        Path deck = pptx();
        Path mediaDir = tmp.resolve("m");

        assertThat(MediaExtractor.extract(deck, mediaDir, 1)).isEqualTo(1);
        // 이름순 — evil.png가 먼저
        assertThat(names(mediaDir)).containsExactly("evil.png");
    }
}
