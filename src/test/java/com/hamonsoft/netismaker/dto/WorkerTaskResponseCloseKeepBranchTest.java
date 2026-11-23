package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 종료 claim의 closeKeepBranch가 JSON으로 워커까지 살아서 도착한다 (스펙 2026-11-23 §4.4). */
class WorkerTaskResponseCloseKeepBranchTest {

    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Task task(boolean keep) {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setCloseKeepBranch(keep);
        return t;
    }

    @Test
    void closeKeepBranch_true는_JSON을_거쳐도_true() throws Exception {
        String json = lenient.writeValueAsString(WorkerTaskResponse.forClose(task(true), null));

        assertThat(json).contains("\"closeKeepBranch\":true");
        assertThat(lenient.readValue(json, WorkerTaskResponse.class).closeKeepBranch()).isTrue();
    }

    @Test
    void 일반_종료는_false로_나가고_false로_읽힌다() throws Exception {
        String json = lenient.writeValueAsString(WorkerTaskResponse.forClose(task(false), null));

        assertThat(lenient.readValue(json, WorkerTaskResponse.class).closeKeepBranch()).isFalse();
    }

    @Test
    void 키가_없는_옛_페이로드는_null() throws Exception {
        WorkerTaskResponse old = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"CLOSE\"}",
                WorkerTaskResponse.class);

        assertThat(old.closeKeepBranch()).isNull();
    }
}
