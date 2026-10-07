package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 비관리자 응답의 비밀 env 값 비우기 (스펙 2026-10-07 §7). */
class TaskResponseRedactTest {

    @Test
    void secret_values_are_blanked_and_everything_else_kept() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 5L);
        t.setEnvVars(new ArrayList<>(List.of(
                new EnvVar("DB_URL", "jdbc:x", false),
                new EnvVar("DB_PASSWORD", "pw", true))));
        TaskResponse full = TaskResponse.of(t, null);

        TaskResponse redacted = full.redactSecretValues();

        assertThat(redacted.envVars()).containsExactly(
                new EnvVar("DB_URL", "jdbc:x", false),
                new EnvVar("DB_PASSWORD", "", true));
        assertThat(redacted).usingRecursiveComparison().ignoringFields("envVars").isEqualTo(full);
        assertThat(full.envVars().get(1).value()).isEqualTo("pw");   // 원본 불변
    }
}
