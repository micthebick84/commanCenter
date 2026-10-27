package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 참조 지원을 알리지 않은 워커(구버전)는 참조 있는 구현대기를 claim하지 못한다 (스펙 2026-10-24 §4.7) — 네이티브 claim 쿼리.
 * 디자인·배포·종료 claim은 막지 않고, 재시도에서 참조를 모두 빼면 구버전 워커도 가져간다(§4.10). RUN_TESTCONTAINERS=true 전용.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskClaimRefSupportIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;
    @Autowired private WorkerService workerService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 seed (alias 'Netis7.0'). 생성 시각을 minutesAgo분 전으로 당겨 claim 순서(FIFO)를 정한다. */
    private Task task(TaskStatus status, boolean withRef, int minutesAgo) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "참조 claim", "설명"), "user1");
        t.setStatus(status);
        if (withRef) {
            t.setRefRepos(new ArrayList<>(List.of(new RefRepo(11L, "옛앱", "g/old",
                    "https://gitlab.hamon.vip/g/old.git", "release/6.5", SHA, "2026-10-24T10:00+09:00", null))));
        }
        t = taskRepo.saveAndFlush(t);
        jdbc.update("UPDATE com.task SET created_at = now() - make_interval(mins => CAST(? AS integer)) WHERE id = ?",
                minutesAgo, t.getId());
        return t;
    }

    @Test
    void 참조_지원을_알리지_않은_워커는_참조_있는_구현대기를_건너뛴다() {
        Task approved = task(TaskStatus.APPROVED, true, 60);
        Task pending = task(TaskStatus.PENDING, false, 10);

        WorkerTaskResponse old = workerService.claimNextTask("old-worker", false).orElseThrow();
        assertThat(old.id()).isEqualTo(pending.getId());
        assertThat(old.kind()).isEqualTo(WorkerTaskResponse.Kind.ANALYSIS);
        assertThat(workerService.claimNextTask("old-worker", false)).isEmpty();   // 참조 있는 구현대기는 대기로 남는다
        assertThat(taskRepo.findById(approved.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);

        WorkerTaskResponse fresh = workerService.claimNextTask("new-worker", true).orElseThrow();
        assertThat(fresh.id()).isEqualTo(approved.getId());
        assertThat(fresh.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(fresh.refRepos()).hasSize(1);
        assertThat(fresh.refRepos().get(0).sha()).isEqualTo(SHA);
        assertThat(taskRepo.findById(approved.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
    }

    @Test
    void 참조_없는_구현대기는_구버전_워커도_가져간다() {
        Task approved = task(TaskStatus.APPROVED, false, 30);

        WorkerTaskResponse r = workerService.claimNextTask("old-worker", false).orElseThrow();

        assertThat(r.id()).isEqualTo(approved.getId());
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.refRepos()).isEmpty();
    }

    @Test
    void 참조가_있어도_디자인_배포_종료_claim은_막지_않는다() {
        Task design = task(TaskStatus.DESIGN_PENDING, true, 30);
        Task deploy = task(TaskStatus.DEPLOY_PENDING, true, 20);
        Task close = task(TaskStatus.CLOSE_PENDING, true, 10);

        List<WorkerTaskResponse> claimed = List.of(
                workerService.claimNextTask("old-worker", false).orElseThrow(),
                workerService.claimNextTask("old-worker", false).orElseThrow(),
                workerService.claimNextTask("old-worker", false).orElseThrow());

        assertThat(claimed).extracting(WorkerTaskResponse::id)
                .containsExactly(design.getId(), deploy.getId(), close.getId());
        assertThat(claimed).extracting(WorkerTaskResponse::kind).containsExactly(
                WorkerTaskResponse.Kind.DESIGN, WorkerTaskResponse.Kind.DEPLOY, WorkerTaskResponse.Kind.CLOSE);
        assertThat(claimed).allSatisfy(r -> assertThat(r.refRepos()).isEmpty());
    }

    @Test
    void removing_every_ref_makes_the_task_claimable_by_old_workers() {
        Task kept = task(TaskStatus.IMPLEMENTATION_FAILED, true, 60);      // 더 오래됐지만 참조를 그대로 두고 재시도
        Task removed = task(TaskStatus.IMPLEMENTATION_FAILED, true, 30);   // 참조를 모두 빼고 재시도

        taskService.retryImplementation(kept.getId(), "admin", null, null);
        taskService.retryImplementation(removed.getId(), "admin", null, List.of(11L));

        WorkerTaskResponse r = workerService.claimNextTask("old-worker", false).orElseThrow();
        assertThat(r.id()).isEqualTo(removed.getId());
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.refRepos()).isEmpty();
        assertThat(r.implementation().attempt()).isEqualTo(2);
        assertThat(taskRepo.findById(removed.getId()).orElseThrow().getRefRepos()).isEmpty();
        // 참조가 남은 쪽은 구버전 워커에게 가지 않고, 참조를 아는 워커가 같은 고정 커밋으로 가져간다
        assertThat(workerService.claimNextTask("old-worker", false)).isEmpty();
        WorkerTaskResponse fresh = workerService.claimNextTask("new-worker", true).orElseThrow();
        assertThat(fresh.id()).isEqualTo(kept.getId());
        assertThat(fresh.refRepos()).extracting(WorkerTaskResponse.TaskRefRepo::sha).containsExactly(SHA);
    }
}
