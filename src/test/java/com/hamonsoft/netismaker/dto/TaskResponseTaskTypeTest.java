package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답의 taskType 칸 — 만드는 곳과 사본 네 곳이 모두 유지한다 (스펙 2026-11-03 §4.2). */
class TaskResponseTaskTypeTest {

    private Task task(TaskType type) {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(TaskStatus.DEPLOYED);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_PASSWORD", "pw", true))));
        t.setTaskType(type);
        return t;
    }

    @Test
    void 새_작업은_타입이_없다() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), null, null);
        assertThat(t.getTaskType()).isNull();
    }

    @Test
    void 응답은_타입_이름을_그대로_싣는다() {
        assertThat(TaskResponse.of(task(TaskType.BUG_FIX), null).taskType()).isEqualTo("BUG_FIX");
        assertThat(TaskResponse.of(task(TaskType.DB_WORK), null).taskType()).isEqualTo("DB_WORK");
        assertThat(TaskResponse.ofWithUsage(task(TaskType.NEW_FEATURE), null, null, null, List.of(), List.of())
                .taskType()).isEqualTo("NEW_FEATURE");
    }

    @Test
    void 타입이_없으면_null_그대로() {
        assertThat(TaskResponse.of(task(null), null).taskType()).isNull();
    }

    @Test
    void 응답과_사본_네곳이_taskType_유지() {
        TaskResponse r = TaskResponse.of(task(TaskType.BUG_FIX), null);
        assertThat(TaskResponse.withTotalCost(r, BigDecimal.ONE).taskType()).isEqualTo("BUG_FIX");
        assertThat(r.withRefRepos(List.of()).taskType()).isEqualTo("BUG_FIX");
        assertThat(r.withFix(new TaskResponse.FixView(1, "DIRECT", 1, 3)).taskType()).isEqualTo("BUG_FIX");
        assertThat(r.redactSecretValues().taskType()).isEqualTo("BUG_FIX");

        // 타입 없음도 사본에서 그대로 null
        TaskResponse none = TaskResponse.of(task(null), null);
        assertThat(TaskResponse.withTotalCost(none, BigDecimal.ONE).taskType()).isNull();
        assertThat(none.withRefRepos(List.of()).taskType()).isNull();
        assertThat(none.withFix(new TaskResponse.FixView(1, "DIRECT", 1, 3)).taskType()).isNull();
        assertThat(none.redactSecretValues().taskType()).isNull();
    }

    @Test
    void 사본은_taskType_말고_다른_칸을_건드리지_않는다() {
        TaskResponse r = TaskResponse.of(task(TaskType.FEATURE_CHANGE), null);
        assertThat(r.withRefRepos(List.of())).usingRecursiveComparison().isEqualTo(r);
        assertThat(r.withFix(null)).usingRecursiveComparison().isEqualTo(r);
    }
}
