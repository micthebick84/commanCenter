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
}
