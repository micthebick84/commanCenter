package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.WorkerCapabilities;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * closeKeepBranch를 알리지 않은 워커(구버전)는 병합하지 않고 종료(close_keep_branch)하는 종료대기를 claim하지 못한다
 * (스펙 2026-11-23 §4.4) — 구버전 jar는 모드를 몰라 그냥 병합해 버리기 때문. 네이티브 claim 쿼리의 close_keep_branch 조건.
 * RUN_TESTCONTAINERS=true 전용 (이 PC에는 Docker가 없어 CI가 돌린다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskClaimCloseKeepBranchSupportIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final WorkerCapabilities OLD = new WorkerCapabilities(true, true, true, true, true, false);
    private static final WorkerCapabilities NEW = WorkerCapabilities.ALL;

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskService taskService;
    @Autowired private WorkerService workerService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        fixRepo.deleteAll();
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 seed. 생성 시각을 minutesAgo분 전으로 당겨 claim 순서(FIFO)를 정한다. */
    private Task closePending(boolean keepBranch, int minutesAgo) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "종료 claim", "설명"), "user1");
        t.setStatus(TaskStatus.CLOSE_PENDING);
        t.setHeadBranch("netismaker/task-" + t.getId() + "-x");
        t.setHeadSha(SHA);
        t.setCloseKeepBranch(keepBranch);
        t = taskRepo.saveAndFlush(t);
        jdbc.update("UPDATE com.task SET created_at = now() - make_interval(mins => CAST(? AS integer)) WHERE id = ?",
                minutesAgo, t.getId());
        return t;
    }

    @Test
    void closeKeepBranch를_모르는_워커는_유지_종료대기를_건너뛰고_일반_종료대기를_받는다() {
        Task keep = closePending(true, 60);     // A: 먼저 생성
        Task plain = closePending(false, 30);   // B

        List<Task> oldClaim = taskRepo.findClaimableAmongForUpdateSkipLocked(
                List.of(TaskStatus.CLOSE_PENDING), true, true, true, true, false);
        assertThat(oldClaim).extracting(Task::getId).containsExactly(plain.getId());

        List<Task> newClaim = taskRepo.findClaimableAmongForUpdateSkipLocked(
                List.of(TaskStatus.CLOSE_PENDING), true, true, true, true, true);
        assertThat(newClaim).extracting(Task::getId).containsExactly(keep.getId());
    }

    @Test
    void 서비스_claim도_같은_가드를_탄다() {
        Task keep = closePending(true, 60);

        assertThat(workerService.claimNextTask("old-worker", OLD, null)).isEmpty();
        assertThat(taskRepo.findById(keep.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);

        WorkerTaskResponse r = workerService.claimNextTask("new-worker", NEW, null).orElseThrow();
        assertThat(r.id()).isEqualTo(keep.getId());
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.CLOSE);
        assertThat(r.closeKeepBranch()).isTrue();
    }
}
