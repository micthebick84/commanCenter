package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerServiceStageUsageTest {

    @Autowired private WorkerService workerService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskStageUsageRepository usageRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    private static final WorkerResultRequest.UsageReport USAGE_A =
            new WorkerResultRequest.UsageReport(new BigDecimal("0.10"), 1000L, 200L, 0L, 500L);
    private static final WorkerResultRequest.UsageReport USAGE_B =
            new WorkerResultRequest.UsageReport(new BigDecimal("0.25"), 400L, 100L, 10L, 0L);

    @BeforeEach void clean() {
        usageRepo.deleteAll();
        historyRepo.deleteAll();
        analysisRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task taskIn(TaskStatus status) {
        Task t = Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                List.of(), "claude-opus-4-8", "high");
        t.setStatus(status);
        t.setWorkerId("w1");
        return taskRepo.save(t);
    }

    @Test
    void 구현_실패_후_성공_보고는_IMPLEMENTATION에_누적된다() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "실패",
                null, null, "b", null, "log",
                null, null, null, null, null,
                null, null, null, null, USAGE_A));

        // 재시도 흉내: 상태/워커 되돌림
        Task again = taskRepo.findById(t.getId()).orElseThrow();
        again.setStatus(TaskStatus.IMPLEMENTING);
        again.setWorkerId("w1");
        taskRepo.save(again);

        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.PR_CREATED,
                null, null, null, 5L, null,
                "http://pr", 1, "b", "sha", "log",
                null, null, null, null, null,
                null, null, null, null, USAGE_B));

        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_IMPLEMENTATION);
        assertThat(rows.get(0).getCostUsd()).isEqualByComparingTo("0.35");
        assertThat(rows.get(0).getInputTokens()).isEqualTo(1400);
    }

    @Test
    void 분석_완료_보고는_ANALYSIS에_기록된다() {
        Task t = taskIn(TaskStatus.IN_PROGRESS);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.COMPLETED,
                "## 1. x\n## 2. y\n## 3. z", "[]", "log", 5L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, USAGE_A));
        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_ANALYSIS);
    }

    @Test
    void usage_없는_보고는_행을_만들지_않는다() {
        Task t = taskIn(TaskStatus.IN_PROGRESS);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.FAILED,
                null, null, null, null, "이유",
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null));
        assertThat(usageRepo.findByTaskIdOrderByStageAsc(t.getId())).isEmpty();
    }

    @Test
    void 지각_정합화_IMPLEMENTATION_FAILED_to_PR_CREATED는_usage를_누적한다() {
        // 지각 정합화 분기 진입: IMPLEMENTATION_FAILED 상태 유지 + 유효한 PR 메타 + usage
        Task t = taskIn(TaskStatus.IMPLEMENTATION_FAILED);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.PR_CREATED,
                null, null, null, 5L, null,
                "http://pr-reconcile", 42, "main", "abc123", "log",
                null, null, null, null, null,
                null, null, null, null, USAGE_B));

        // 상태 전이 확인 (분기 진입 증명)
        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(reloaded.getPrUrl()).isEqualTo("http://pr-reconcile");

        // 누적 확인
        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_IMPLEMENTATION);
        assertThat(rows.get(0).getCostUsd()).isEqualByComparingTo("0.25");
        assertThat(rows.get(0).getInputTokens()).isEqualTo(400);
    }

    @Test
    void 지각_정합화_FAILED_to_COMPLETED는_usage를_누적한다() {
        // 지각 정합화 분기 진입: FAILED 상태 유지 + 유효한 분석 결과 + usage
        Task t = taskIn(TaskStatus.FAILED);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.COMPLETED,
                "## 1. a\n## 2. b\n## 3. c", "[]", "log", 5L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, USAGE_A));

        // 상태 전이 확인 (분기 진입 증명)
        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reloaded.getFailureReason()).isNull();

        // 누적 확인
        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_ANALYSIS);
        assertThat(rows.get(0).getCostUsd()).isEqualByComparingTo("0.10");
        assertThat(rows.get(0).getInputTokens()).isEqualTo(1000);
    }
}
