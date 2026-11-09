package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mockups를 알리지 않은 워커(구버전)는 목업을 고른(또는 제외 번호가 있는) 구현대기·디자인대기를 claim하지 못한다
 * (스펙 2026-11-03 §7) — 네이티브 claim 쿼리. 배포 등 다른 claim은 막지 않는다. RUN_TESTCONTAINERS=true 전용.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskClaimMockupSupportIntegrationTest {

    private static final WorkerCapabilities OLD = new WorkerCapabilities(true, true, true, false);
    private static final WorkerCapabilities NEW = WorkerCapabilities.ALL;

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private InterviewMockupRepository mockupRepo;
    @Autowired private TaskService taskService;
    @Autowired private WorkerService workerService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();   // interview_mockup은 CASCADE
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 seed. 생성 시각을 minutesAgo분 전으로 당겨 claim 순서(FIFO)를 정한다. */
    private Task task(TaskStatus status, List<Long> mockupIds, List<Integer> excluded, int minutesAgo) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "목업 claim", "설명"), "user1");
        t.setStatus(status);
        t.setImplementationMockupIds(new ArrayList<>(mockupIds));
        t.setImplementationMockupExcluded(new ArrayList<>(excluded));
        t = taskRepo.saveAndFlush(t);
        jdbc.update("UPDATE com.task SET created_at = now() - make_interval(mins => CAST(? AS integer)) WHERE id = ?",
                minutesAgo, t.getId());
        return t;
    }

    @Test
    void 옛_워커는_목업있는_구현대기와_디자인대기를_못받고_배포는_받는다() {
        Task implWithMockup = task(TaskStatus.APPROVED, List.of(1L), List.of(), 90);
        Task designWithMockup = task(TaskStatus.DESIGN_PENDING, List.of(1L), List.of(), 80);
        Task implExcludedOnly = task(TaskStatus.APPROVED, List.of(), List.of(2), 70);   // 제외 번호만 있어도 막는다
        Task deploy = task(TaskStatus.DEPLOY_PENDING, List.of(1L), List.of(2), 60);
        Task plain = task(TaskStatus.APPROVED, List.of(), List.of(), 50);

        WorkerTaskResponse first = workerService.claimNextTask("old", OLD, null).orElseThrow();
        WorkerTaskResponse second = workerService.claimNextTask("old", OLD, null).orElseThrow();

        assertThat(List.of(first.id(), second.id())).containsExactly(deploy.getId(), plain.getId());
        assertThat(workerService.claimNextTask("old", OLD, null)).isEmpty();
        assertThat(taskRepo.findById(implWithMockup.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(taskRepo.findById(designWithMockup.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(taskRepo.findById(implExcludedOnly.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    void 새_워커는_받는다() {
        InterviewMockup m = savedMockup();
        Task impl = task(TaskStatus.APPROVED, List.of(m.getId()), List.of(2), 30);
        Task design = task(TaskStatus.DESIGN_PENDING, List.of(m.getId()), List.of(), 20);

        WorkerTaskResponse first = workerService.claimNextTask("new", NEW, null).orElseThrow();
        WorkerTaskResponse second = workerService.claimNextTask("new", NEW, null).orElseThrow();

        assertThat(first.id()).isEqualTo(impl.getId());
        assertThat(first.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n)
                .containsExactly(m.getN());
        assertThat(first.excludedMockupNumbers()).containsExactly(2);
        assertThat(second.id()).isEqualTo(design.getId());
        assertThat(second.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
    }

    @Test
    void 참조_거름과_AND로_걸린다() {
        Task t = task(TaskStatus.APPROVED, List.of(), List.of(), 10);
        // mockups는 알지만 fixRounds를 모르는 워커 — 회차 거름만 걸리고 목업 없는 작업은 그대로 받는다
        WorkerCapabilities noFix = new WorkerCapabilities(true, false, true, true);

        assertThat(workerService.claimNextTask("w", noFix, null).orElseThrow().id()).isEqualTo(t.getId());
    }

    private InterviewMockup savedMockup() {
        Long sid = sessionRepo.save(InterviewSession.create("hamonsoft/netis-backend", "main",
                "목업", "화면 작업", "user1", List.of(), "claude-opus-5-5", "high")).getId();
        return mockupRepo.saveAndFlush(InterviewMockup.create(sid, 1, "list-a", 1, "A안", null,
                "<!doctype html><html><body>A</body></html>"));
    }
}
