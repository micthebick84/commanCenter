package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskStageUsageRepositoryTest {

    @Autowired private TaskStageUsageRepository usageRepo;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach void clean() {
        usageRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task savedTask() {
        return taskRepo.save(Task.create("acme/widgets", "main", "제목", "설명",
                "user1", 3, new ArrayList<>(), null, null));
    }

    @Test
    @Transactional
    void 같은_단계에_두_번_accumulate하면_합산된다() {
        Task t = savedTask();
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION,
                new BigDecimal("0.100000"), 1000, 200, 50, 3000);
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION,
                new BigDecimal("0.250000"), 500, 100, 0, 1000);

        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        TaskStageUsage u = rows.get(0);
        assertThat(u.getCostUsd()).isEqualByComparingTo("0.350000");
        assertThat(u.getInputTokens()).isEqualTo(1500);
        assertThat(u.getOutputTokens()).isEqualTo(300);
        assertThat(u.getCacheCreationTokens()).isEqualTo(50);
        assertThat(u.getCacheReadTokens()).isEqualTo(4000);
    }

    @Test
    @Transactional
    void sumCostByTaskIds는_task별_총합을_반환한다() {
        Task t = savedTask();
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_ANALYSIS,
                new BigDecimal("0.100000"), 10, 5, 0, 0);
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_DESIGN,
                new BigDecimal("0.200000"), 20, 10, 0, 0);

        var totals = usageRepo.sumCostByTaskIds(List.of(t.getId()));
        assertThat(totals).hasSize(1);
        assertThat(totals.get(0).getTaskId()).isEqualTo(t.getId());
        assertThat(totals.get(0).getTotalCostUsd()).isEqualByComparingTo("0.300000");
    }
}
