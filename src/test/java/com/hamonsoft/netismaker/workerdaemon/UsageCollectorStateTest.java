package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.workerdaemon.UsageCollectorState.FileMark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static com.hamonsoft.netismaker.workerdaemon.UsageCollectorState.startOffset;
import static com.hamonsoft.netismaker.workerdaemon.UsageCollectorState.unchanged;
import static org.assertj.core.api.Assertions.assertThat;

class UsageCollectorStateTest {

    @TempDir Path tmp;

    private static final String A = "C:/Users/mic/.claude/projects/p/a.jsonl";
    private static final String B = "C:/Users/mic/.claude/projects/p/세션/subagents/b.jsonl";

    @Test
    void 저장과_읽기() throws IOException {
        Path file = tmp.resolve("netis-maker").resolve("state.json");
        UsageCollectorState state = UsageCollectorState.load(file);
        assertThat(state.fresh()).isTrue();

        state.put(A, new FileMark(400, 600, 1_700_000_000_123L));
        state.put(B, new FileMark(0, 0, 5L));
        state.saveAtomically(file);

        UsageCollectorState loaded = UsageCollectorState.load(file);
        assertThat(loaded.fresh()).isFalse();
        assertThat(loaded.mark(A)).isEqualTo(new FileMark(400, 600, 1_700_000_000_123L));
        assertThat(loaded.mark(B)).isEqualTo(new FileMark(0, 0, 5L));
        assertThat(loaded.mark("C:/없는/경로.jsonl")).isNull();

        assertThat(Files.readString(file)).startsWith("{\"version\":1,\"files\":{");
        JsonNode json = new ObjectMapper().readTree(file.toFile());
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("files").get(A).get("offset").asLong()).isEqualTo(400);
        assertThat(json.get("files").get(A).get("size").asLong()).isEqualTo(600);
        assertThat(json.get("files").get(A).get("mtimeMillis").asLong()).isEqualTo(1_700_000_000_123L);
    }

    @Test
    void 깨진_파일이면_빈_상태() throws IOException {
        Path file = Files.writeString(tmp.resolve("state.json"), "{not json");

        UsageCollectorState state = UsageCollectorState.load(file);

        assertThat(state.fresh()).isTrue();
        assertThat(state.mark(A)).isNull();
    }

    @Test
    void 모양이_다르면_빈_상태() throws IOException {
        Path wrongVersion = Files.writeString(tmp.resolve("v.json"),
                "{\"version\":2,\"files\":{\"a\":{\"offset\":1,\"size\":1,\"mtimeMillis\":1}}}");
        Path badMark = Files.writeString(tmp.resolve("m.json"),
                "{\"version\":1,\"files\":{\"a\":{\"offset\":\"x\",\"size\":1,\"mtimeMillis\":1}}}");
        Path negative = Files.writeString(tmp.resolve("n.json"),
                "{\"version\":1,\"files\":{\"a\":{\"offset\":-1,\"size\":1,\"mtimeMillis\":1}}}");

        assertThat(UsageCollectorState.load(wrongVersion).fresh()).isTrue();
        assertThat(UsageCollectorState.load(badMark).fresh()).isTrue();
        assertThat(UsageCollectorState.load(negative).fresh()).isTrue();
    }

    @Test
    void 원자적_저장() throws IOException {
        Path file = Files.writeString(tmp.resolve("state.json"), "{\"old\":true}");
        UsageCollectorState state = UsageCollectorState.load(file);
        state.put(A, new FileMark(10, 10, 1L));

        state.saveAtomically(file);

        assertThat(UsageCollectorState.load(file).mark(A)).isEqualTo(new FileMark(10, 10, 1L));
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly("state.json");
        }
    }

    @Test
    void retainOnly는_걸은_경로만_남긴다() {
        UsageCollectorState state = UsageCollectorState.load(tmp.resolve("none.json"));
        state.put(A, new FileMark(1, 1, 1L));
        state.put(B, new FileMark(2, 2, 2L));

        state.retainOnly(Set.of(B));

        assertThat(state.mark(A)).isNull();
        assertThat(state.mark(B)).isEqualTo(new FileMark(2, 2, 2L));
    }

    @Test
    void 시작_위치() {
        long m = 1_000L;
        long m2 = 2_000L;
        FileMark prev = new FileMark(400, 600, m);

        assertThat(startOffset(null, 600, m)).isZero();
        assertThat(startOffset(prev, 500, m2)).isZero();          // 줄었음
        assertThat(startOffset(prev, 600, m2)).isZero();          // 크기 같고 수정 시각 다름(바뀌었음)
        assertThat(startOffset(prev, 900, m2)).isEqualTo(400);    // 덧붙음

        // 상한에 걸려 중간에서 멈춘 파일 — 바뀌지 않았어도 남은 곳부터 이어 읽는다.
        assertThat(unchanged(prev, 600, m)).isFalse();
        assertThat(startOffset(prev, 600, m)).isEqualTo(400);

        // 끝까지 읽었고 그 뒤로 바뀌지 않은 파일만 건너뛴다.
        FileMark done = new FileMark(600, 600, m);
        assertThat(unchanged(done, 600, m)).isTrue();
        assertThat(unchanged(done, 600, m2)).isFalse();
        assertThat(unchanged(done, 700, m2)).isFalse();
        assertThat(unchanged(null, 600, m)).isFalse();
    }

    @Test
    void 읽은_위치보다_작아졌으면_처음부터() {
        // 크기를 본 뒤 읽는 사이에 덧붙어 offset > size로 남은 표시(700, 600) — 지금 650이면 잘렸다가 다시 쓰인 것이다.
        FileMark grewWhileReading = new FileMark(700, 600, 1_000L);

        assertThat(startOffset(grewWhileReading, 650, 2_000L)).isZero();
        assertThat(startOffset(grewWhileReading, 700, 2_000L)).isEqualTo(700);
    }
}
