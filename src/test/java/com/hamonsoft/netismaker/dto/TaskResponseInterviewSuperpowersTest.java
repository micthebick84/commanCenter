package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 응답의 interviewSuperpowersEnabled 칸 — 지금 인터뷰 세션의 방식(설계 2026-11-03-interview-superpowers-toggle §4).
 * 만드는 곳(of·ofWithUsage)은 null(세션을 모른다), 값은 withInterviewSuperpowersEnabled로만 붙고 다른 사본 네 곳이 유지한다.
 */
class TaskResponseInterviewSuperpowersTest {

    private Task task() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(TaskStatus.INTERVIEW_REVIEW);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_PASSWORD", "pw", true))));
        return t;
    }

    @Test
    void 만드는_곳은_값이_없다_세션을_모른다() {
        assertThat(TaskResponse.of(task(), null).interviewSuperpowersEnabled()).isNull();
        assertThat(TaskResponse.of(task(), null, null, 9L).interviewSuperpowersEnabled()).isNull();
        assertThat(TaskResponse.ofWithUsage(task(), null, null, 9L, List.of(), List.of())
                .interviewSuperpowersEnabled()).isNull();
    }

    @Test
    void with는_그_칸만_바꾸고_null을_되돌릴_수_있다() {
        TaskResponse base = TaskResponse.of(task(), null);

        TaskResponse off = base.withInterviewSuperpowersEnabled(false);
        TaskResponse on = base.withInterviewSuperpowersEnabled(true);

        assertThat(off.interviewSuperpowersEnabled()).isFalse();
        assertThat(on.interviewSuperpowersEnabled()).isTrue();
        assertThat(off).usingRecursiveComparison().ignoringFields("interviewSuperpowersEnabled").isEqualTo(base);
        assertThat(off.withInterviewSuperpowersEnabled(null)).isEqualTo(base);
    }

    @Test
    void TaskResponse_사본이_값을_유지() {
        TaskResponse off = TaskResponse.of(task(), null).withInterviewSuperpowersEnabled(false);

        assertThat(TaskResponse.withTotalCost(off, BigDecimal.ONE).interviewSuperpowersEnabled()).isFalse();
        assertThat(off.withRefRepos(List.of()).interviewSuperpowersEnabled()).isFalse();
        assertThat(off.withFix(new TaskResponse.FixView(1, "DIRECT", 1, 3, "FIX")).interviewSuperpowersEnabled()).isFalse();
        assertThat(off.redactSecretValues().interviewSuperpowersEnabled()).isFalse();

        // 켬(true)과 세션 없음(null)도 사본에서 그대로다 — 값을 TRUE로 바꾸는 사본이 없다
        TaskResponse on = TaskResponse.of(task(), null).withInterviewSuperpowersEnabled(true);
        assertThat(TaskResponse.withTotalCost(on, BigDecimal.ONE).interviewSuperpowersEnabled()).isTrue();
        assertThat(on.withRefRepos(List.of()).interviewSuperpowersEnabled()).isTrue();
        assertThat(on.withFix(null).interviewSuperpowersEnabled()).isTrue();
        assertThat(on.redactSecretValues().interviewSuperpowersEnabled()).isTrue();
        TaskResponse none = TaskResponse.of(task(), null);
        assertThat(TaskResponse.withTotalCost(none, BigDecimal.ONE).interviewSuperpowersEnabled()).isNull();
        assertThat(none.withRefRepos(List.of()).interviewSuperpowersEnabled()).isNull();
        assertThat(none.withFix(null).interviewSuperpowersEnabled()).isNull();
        assertThat(none.redactSecretValues().interviewSuperpowersEnabled()).isNull();
    }

    @Test
    void 사본은_이_칸_말고_다른_칸을_건드리지_않는다() {
        TaskResponse off = TaskResponse.of(task(), null).withInterviewSuperpowersEnabled(false);

        assertThat(off.withRefRepos(List.of())).usingRecursiveComparison().isEqualTo(off);
        assertThat(off.withFix(null)).usingRecursiveComparison().isEqualTo(off);
        // withInterviewSuperpowersEnabled는 다른 사본이 올려 둔 칸(fix·참조·가림)을 지우지 않는다
        TaskResponse fixed = TaskResponse.of(task(), null).withFix(new TaskResponse.FixView(2, "DIRECT", 2, 3, "FIX"));
        assertThat(fixed.withInterviewSuperpowersEnabled(false).fix()).isEqualTo(fixed.fix());
        TaskResponse redacted = TaskResponse.of(task(), null).redactSecretValues();
        assertThat(redacted.withInterviewSuperpowersEnabled(false).envVars()).extracting(EnvVar::value).containsExactly("");
    }

    @Test
    void JSON에는_항상_칸이_나가고_세션이_없으면_null() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskResponse base = TaskResponse.of(task(), null);

        JsonNode none = mapper.valueToTree(base);
        assertThat(none.has("interviewSuperpowersEnabled")).isTrue();
        assertThat(none.get("interviewSuperpowersEnabled").isNull()).isTrue();

        JsonNode off = mapper.valueToTree(base.withInterviewSuperpowersEnabled(false).redactSecretValues());
        assertThat(off.get("interviewSuperpowersEnabled").isBoolean()).isTrue();
        assertThat(off.get("interviewSuperpowersEnabled").asBoolean()).isFalse();
    }
}
