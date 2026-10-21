package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 작업 응답의 구현 방식·시도 번호·시작 시각·상한 (스펙 2026-10-15 §5.2). */
class TaskResponseImplementationModeTest {

    private static final OffsetDateTime CLAIMED = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");

    private static Task implementing() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        t.setImplementationMode(ImplementationMode.SDD);
        t.setImplementationAttempt(2);
        t.setImplementationTimeoutMinutes(240);
        t.setClaimedAt(CLAIMED);
        return t;
    }

    @Test
    void exposes_mode_attempt_claim_time_and_the_recorded_timeout() {
        TaskResponse r = TaskResponse.of(implementing(), null);

        assertThat(r.implementationMode()).isEqualTo("SDD");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.claimedAt()).isEqualTo(CLAIMED);
        assertThat(r.implementationTimeoutMinutes()).isEqualTo(240);
    }

    @Test
    void list_cost_and_viewer_redaction_keep_the_new_fields() {
        TaskResponse r = TaskResponse.of(implementing(), null);
        for (TaskResponse copy : List.of(TaskResponse.withTotalCost(r, BigDecimal.ONE), r.redactSecretValues())) {
            assertThat(copy.implementationMode()).isEqualTo("SDD");
            assertThat(copy.implementationAttempt()).isEqualTo(2);
            assertThat(copy.claimedAt()).isEqualTo(CLAIMED);
            assertThat(copy.implementationTimeoutMinutes()).isEqualTo(240);
        }
    }

    @Test
    void a_task_never_claimed_for_implementation_is_direct_without_a_timeout() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 8L);

        TaskResponse r = TaskResponse.of(t, null);

        assertThat(r.implementationMode()).isEqualTo("DIRECT");
        assertThat(r.implementationAttempt()).isEqualTo(1);
        assertThat(r.implementationTimeoutMinutes()).isNull();
    }
}
