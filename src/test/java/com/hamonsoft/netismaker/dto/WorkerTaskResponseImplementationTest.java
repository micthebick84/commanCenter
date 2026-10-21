package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
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
}
