package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SilentLossTrackerTest {

    private static final WorkerResultRequest REQ = new WorkerResultRequest(
            "mac-worker-1", TaskStatus.PR_CREATED, null, null, null, 1L, null,
            "https://x/pull/1", 1, "netismaker/task-9", "sha", "log",
            null, null, null, null, null,
            null, null, null, null);

    private SilentLossTracker tracker(Path dir) {
        return new SilentLossTracker(dir.toString(), "mac-worker-1");
    }

    @Test
    void on_lost_increments_count_and_writes_jsonl(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(9L, REQ, false, "재시도 소진(6회)");

        assertThat(t.currentCount()).isEqualTo(1);

        Path file = dir.resolve("mac-worker-1.jsonl");
        assertThat(Files.exists(file)).isTrue();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);

        JsonNode entry = new ObjectMapper().readTree(lines.get(0));
        assertThat(entry.get("taskId").asLong()).isEqualTo(9L);
        assertThat(entry.get("status").asText()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(entry.get("permanent").asBoolean()).isFalse();
        assertThat(entry.get("reason").asText()).contains("재시도 소진");
        assertThat(entry.has("ts")).isTrue();
    }

    @Test
    void multiple_losses_accumulate(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(1L, REQ, true, "4xx 409");
        t.onLost(2L, REQ, false, "소진");
        assertThat(t.currentCount()).isEqualTo(2);
        assertThat(java.nio.file.Files.readAllLines(dir.resolve("mac-worker-1.jsonl"))).hasSize(2);
    }

    @Test
    void creates_dead_letter_dir_if_absent(@TempDir Path dir) {
        Path nested = dir.resolve("sub/dead-letter");
        SilentLossTracker t = new SilentLossTracker(nested.toString(), "mac-worker-1");
        t.onLost(1L, REQ, true, "x");
        assertThat(Files.exists(nested.resolve("mac-worker-1.jsonl"))).isTrue();
    }

    // ───────────────────────── pending 저장소 (재전송 지원) ─────────────────────────

    /** attempts 필드가 없는 구형식 라인 — 운영 파일에 남아있을 수 있다. */
    private String legacyLine(long taskId) throws Exception {
        ObjectMapper m = new ObjectMapper();
        var node = m.createObjectNode();
        node.put("ts", "2026-08-01T00:00:00+09:00");
        node.put("taskId", taskId);
        node.put("status", TaskStatus.PR_CREATED.dbValue());
        node.put("permanent", true);
        node.put("reason", "4xx 409 CONFLICT");
        node.set("result", m.valueToTree(REQ));
        return m.writeValueAsString(node);
    }

    @Test
    void legacy_line_without_attempts_parses_as_zero_and_initializes_count(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mac-worker-1.jsonl"), legacyLine(7L) + System.lineSeparator());

        SilentLossTracker t = tracker(dir);
        // 시작 시 파일 스캔으로 pending 수 초기화 (프로세스 누적이 아님)
        assertThat(t.currentCount()).isEqualTo(1);

        List<SilentLossTracker.PendingEntry> pending = t.snapshotPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).taskId()).isEqualTo(7L);
        assertThat(pending.get(0).attempts()).isZero();
        assertThat(pending.get(0).result().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(pending.get(0).result().prUrl()).isEqualTo("https://x/pull/1");
        assertThat(pending.get(0).result().headBranch()).isEqualTo("netismaker/task-9");
    }

    @Test
    void on_lost_snapshot_resolve_roundtrip_removes_line_and_decrements_count(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(9L, REQ, false, "재시도 소진(6회)");
        t.onLost(10L, REQ, true, "4xx 409");
        assertThat(t.currentCount()).isEqualTo(2);

        List<SilentLossTracker.PendingEntry> pending = t.snapshotPending();
        assertThat(pending).hasSize(2);

        t.resolve(pending.get(0));

        assertThat(t.currentCount()).isEqualTo(1);
        List<String> lines = Files.readAllLines(dir.resolve("mac-worker-1.jsonl"));
        assertThat(lines).hasSize(1);
        assertThat(new ObjectMapper().readTree(lines.get(0)).get("taskId").asLong()).isEqualTo(10L);
    }

    @Test
    void reject_below_max_rewrites_attempts_and_keeps_line(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(9L, REQ, true, "4xx 409");

        t.reject(t.snapshotPending().get(0), 3);

        assertThat(t.currentCount()).isEqualTo(1);
        List<String> lines = Files.readAllLines(dir.resolve("mac-worker-1.jsonl"));
        assertThat(lines).hasSize(1);
        assertThat(new ObjectMapper().readTree(lines.get(0)).get("attempts").asInt()).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("mac-worker-1.dead.jsonl"))).isFalse();
        // 재스냅샷 시 attempts가 올라간 엔트리로 읽힌다
        assertThat(t.snapshotPending().get(0).attempts()).isEqualTo(1);
    }

    @Test
    void reject_exhaustion_moves_line_to_dead_file(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(9L, REQ, true, "4xx 409");
        SilentLossTracker.PendingEntry e = t.snapshotPending().get(0);

        t.reject(e, 1); // attempts+1(=1) >= maxAttempts(1) → 소진

        assertThat(t.currentCount()).isZero();
        assertThat(Files.readAllLines(dir.resolve("mac-worker-1.jsonl"))).isEmpty();

        Path dead = dir.resolve("mac-worker-1.dead.jsonl");
        assertThat(Files.exists(dead)).isTrue();
        List<String> deadLines = Files.readAllLines(dead);
        assertThat(deadLines).hasSize(1);
        JsonNode moved = new ObjectMapper().readTree(deadLines.get(0));
        assertThat(moved.get("taskId").asLong()).isEqualTo(9L);
        assertThat(moved.get("attempts").asInt()).isEqualTo(1); // 포렌식: 시도 횟수 보존
    }

    @Test
    void unparseable_line_is_moved_to_dead_file_and_rest_survive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mac-worker-1.jsonl"),
                "{{{ 깨진 JSON" + System.lineSeparator() + legacyLine(7L) + System.lineSeparator());

        SilentLossTracker t = tracker(dir);
        List<SilentLossTracker.PendingEntry> pending = t.snapshotPending();

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).taskId()).isEqualTo(7L);
        assertThat(t.currentCount()).isEqualTo(1);
        // 본 파일에는 정상 라인만 남고, 깨진 라인은 dead 파일로 이동(유실 금지)
        assertThat(Files.readAllLines(dir.resolve("mac-worker-1.jsonl"))).hasSize(1);
        List<String> deadLines = Files.readAllLines(dir.resolve("mac-worker-1.dead.jsonl"));
        assertThat(deadLines).hasSize(1);
        assertThat(deadLines.get(0)).contains("깨진 JSON");
    }

    @Test
    void on_lost_never_throws_even_when_directory_creation_fails(@TempDir Path dir) throws Exception {
        // 부모 경로 자리에 일반 파일을 놓아 createDirectories가 실패하게 만든다
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "file, not dir");
        SilentLossTracker t = new SilentLossTracker(blocked.resolve("sub").toString(), "mac-worker-1");

        t.onLost(1L, REQ, true, "x"); // throw 금지 불변식

        assertThat(t.currentCount()).isEqualTo(1); // 기록 실패여도 카운트는 이상 신호로 증가
    }
}
