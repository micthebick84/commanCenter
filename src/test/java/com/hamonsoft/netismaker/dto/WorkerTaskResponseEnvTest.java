package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseEnvTest {

    @Test
    void for_deploy_carries_env_vars() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abc1234");
        t.setEnvVars(new java.util.ArrayList<>(List.of(new EnvVar("JWT_SECRET", "x", true))));

        WorkerTaskResponse r = WorkerTaskResponse.forDeploy(t);

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(r.envVars()).hasSize(1);
        assertThat(r.envVars().get(0).key()).isEqualTo("JWT_SECRET");
    }

    @Test
    void for_analysis_has_empty_env_vars() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 8L);
        WorkerTaskResponse r = WorkerTaskResponse.forAnalysis(t);
        assertThat(r.envVars()).isEmpty();
    }
}
