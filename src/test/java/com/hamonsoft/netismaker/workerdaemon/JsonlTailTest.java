package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.JsonlTail.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonlTailTest {

    @TempDir Path tmp;

    private Path write(String content) throws IOException {
        return Files.write(tmp.resolve("s.jsonl"), content.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> text(Chunk c) {
        return c.lines().stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList();
    }

    @Test
    void 완성된_줄만_부분_줄은_남긴다() throws IOException {
        Chunk c = JsonlTail.read(write("a\nb\nc"), 0, Long.MAX_VALUE);

        assertThat(text(c)).containsExactly("a", "b");
        assertThat(c.nextOffset()).isEqualTo(4);
    }

    @Test
    void 위치부터_읽는다() throws IOException {
        Chunk c = JsonlTail.read(write("a\nb\nc"), 2, Long.MAX_VALUE);

        assertThat(text(c)).containsExactly("b");
        assertThat(c.nextOffset()).isEqualTo(4);
    }

    @Test
    void CRLF는_CR을_뗀다() throws IOException {
        Path file = write("a\r\nb\r\n");

        Chunk c = JsonlTail.read(file, 0, Long.MAX_VALUE);
        assertThat(text(c)).containsExactly("a", "b");
        assertThat(c.nextOffset()).isEqualTo(6);

        // 버퍼 2바이트: \r과 \n이 서로 다른 버퍼에 걸려도 같다.
        Chunk split = JsonlTail.read(file, 0, Long.MAX_VALUE, 2);
        assertThat(text(split)).containsExactly("a", "b");
        assertThat(split.nextOffset()).isEqualTo(6);
    }

    @Test
    void 상한에_닿으면_줄_경계에서_멈춘다() throws IOException {
        String line = "x".repeat(99) + "\n";
        Path file = write(line.repeat(10));

        Chunk c = JsonlTail.read(file, 0, 250);

        assertThat(c.lines()).hasSize(3);
        assertThat(c.nextOffset()).isEqualTo(300);
    }

    @Test
    void 상한보다_큰_한_줄도_넘어간다() throws IOException {
        String big = "y".repeat(5000);
        Path file = write(big + "\n" + "b\n");

        Chunk first = JsonlTail.read(file, 0, 1024);
        assertThat(text(first)).containsExactly(big);
        assertThat(first.nextOffset()).isEqualTo(5001);

        Chunk second = JsonlTail.read(file, first.nextOffset(), 1024);
        assertThat(text(second)).containsExactly("b");
        assertThat(second.nextOffset()).isEqualTo(5003);
    }

    @Test
    void 한글이_버퍼_경계에_걸려도_바이트_위치() throws IOException {
        // 한글 한 자 = UTF-8 3바이트 — 버퍼 7바이트면 '다'(6-8)·'마'(13-15)가 버퍼 경계에 걸린다.
        Path file = write("가나다\n라마\r\n바사아\n");

        Chunk c = JsonlTail.read(file, 0, Long.MAX_VALUE, 7);

        assertThat(text(c)).containsExactly("가나다", "라마", "바사아");
        assertThat(c.nextOffset()).isEqualTo(Files.size(file));
        assertThat(c.lines().get(0)).isEqualTo("가나다".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 끝_조각만_남았으면_빈_묶음이고_위치는_그대로() throws IOException {
        Chunk c = JsonlTail.read(write("a\nbcd"), 2, Long.MAX_VALUE);

        assertThat(c.lines()).isEmpty();
        assertThat(c.nextOffset()).isEqualTo(2);
    }

    @Test
    void 없는_파일은_NoSuchFileException() {
        assertThatThrownBy(() -> JsonlTail.read(tmp.resolve("gone.jsonl"), 0, 1024))
                .isInstanceOf(NoSuchFileException.class);
    }
}
