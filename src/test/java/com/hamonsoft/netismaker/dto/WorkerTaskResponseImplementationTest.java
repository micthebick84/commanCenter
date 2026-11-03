package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** claim 응답의 구현 지시 블록 (스펙 2026-10-15 §5.3). */
class WorkerTaskResponseImplementationTest {

    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Task task() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(ImplementationMode.REVIEW);
        t.setImplementationAttempt(3);
        t.setImplementationTimeoutMinutes(80);
        return t;
    }

    @Test
    void implementation_claims_carry_mode_attempt_and_timeout_through_db_grant_and_json() throws Exception {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(), null, null, "# 플랜");

        assertThat(r.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("REVIEW", 3, 80));
        assertThat(r.withDbMcp(new DbMcpGrant(null, List.of(), List.of())).implementation())
                .isEqualTo(r.implementation());
        WorkerTaskResponse back = lenient.readValue(lenient.writeValueAsString(r), WorkerTaskResponse.class);
        assertThat(back.implementation()).isEqualTo(r.implementation());
        assertThat(back.planMarkdown()).isEqualTo("# 플랜");
    }

    @Test
    void other_kinds_and_old_payloads_have_no_implementation_block() throws Exception {
        assertThat(WorkerTaskResponse.forDeploy(task()).implementation()).isNull();
        WorkerTaskResponse old = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\"}",
                WorkerTaskResponse.class);
        assertThat(old.implementation()).isNull();
    }

    // ---- 작업 타입 (스펙 2026-11-03 §5.2) — 구현 claim에서만 쓰므로 ImplementationSpec에 둔다 ----

    @Test
    void 구현_claim은_작업_타입_이름을_구현_블록에_싣는다() throws Exception {
        Task t = task();
        t.setTaskType(TaskType.BUG_FIX);

        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(t, null, null, "# 플랜");

        assertThat(r.implementation().taskType()).isEqualTo("BUG_FIX");
        assertThat(r.implementation())
                .isEqualTo(new WorkerTaskResponse.ImplementationSpec("REVIEW", 3, 80, null, "BUG_FIX"));
        assertThat(r.withDbMcp(new DbMcpGrant(null, List.of(), List.of())).implementation().taskType())
                .isEqualTo("BUG_FIX");
        WorkerTaskResponse back = lenient.readValue(lenient.writeValueAsString(r), WorkerTaskResponse.class);
        assertThat(back.implementation().taskType()).isEqualTo("BUG_FIX");
    }

    @Test
    void 타입없음은_taskType_null이고_키는_그대로_나간다() throws Exception {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(), null, null, "# 플랜");

        assertThat(r.implementation().taskType()).isNull();
        assertThat(lenient.readTree(lenient.writeValueAsString(r)).get("implementation").has("taskType")).isTrue();
        assertThat(lenient.readTree(lenient.writeValueAsString(r)).get("implementation").get("taskType").isNull())
                .isTrue();
    }

    @Test
    void 옛_페이로드는_taskType_null() throws Exception {
        WorkerTaskResponse noBlock = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\"}",
                WorkerTaskResponse.class);
        WorkerTaskResponse oldBlock = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\","
                        + "\"implementation\":{\"mode\":\"DIRECT\",\"attempt\":1,\"timeoutMinutes\":45}}",
                WorkerTaskResponse.class);

        assertThat(noBlock.implementation()).isNull();
        assertThat(oldBlock.implementation().taskType()).isNull();
        assertThat(oldBlock.implementation())
                .isEqualTo(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45));
    }

    @Test
    void 호환_생성자는_타입없음() {
        assertThat(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45).taskType()).isNull();
        assertThat(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null).taskType()).isNull();
        assertThat(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null))
                .isEqualTo(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, null));
    }
}
