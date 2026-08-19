package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewConfirmContractTest {

    @Autowired private TaskService taskService;
    @Autowired private InterviewService interviewService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    private Long taskId;
    private Long sid;

    @BeforeEach void seed() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        analysisRepo.deleteAll();
        historyRepo.deleteAll();
        taskRepo.deleteAll();
        Task t = taskService.create(new TaskCreateRequest(1L, "feat/rbac", "RBAC 추가", "역할 기반 권한"), "user1");
        taskId = t.getId();
        taskService.approve(taskId, "admin", null);
        sid = sessionRepo.findAll().get(0).getId();
    }

    private void toPlanReady() {
        interviewService.claim("iw-1");
        interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                "# 설계 문서", "# 구현 플랜", "[{\"title\":\"A\"},{\"title\":\"B\"}]",
                new BigDecimal("0.42"), 30000L));
    }

    @Test
    void confirm_updates_the_existing_task_and_prefills_analysis() throws Exception {
        toPlanReady();

        Long returned = interviewService.confirm(sid, "admin", false);

        assertThat(returned).isEqualTo(taskId);
        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);   // 구현대기
        assertThat(taskRepo.findAll()).hasSize(1);                   // task를 새로 만들지 않는다

        TaskAnalysis a = analysisRepo.findById(taskId).orElseThrow();
        // LOCKED CONTRACT v2: markdownResult = design_markdown ONLY (합본 아님)
        assertThat(a.getMarkdownResult()).isEqualTo("# 설계 문서");
        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(a.getSubtasksJson()))
                .isEqualTo(om.readTree("[{\"title\":\"A\"},{\"title\":\"B\"}]"));
        assertThat(a.getClaudeLog()).isNull();
        assertThat(a.getDurationMs()).isEqualTo(30000L);
        // 확정이 곧 승인 — 별도 승인 게이트가 뒤에 남지 않는다
        assertThat(a.isApproved()).isTrue();
        assertThat(a.getApprovedBy()).isEqualTo("admin");

        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REGISTERED);

        // TaskStatusHistory.log(...)가 확정 전이를 남긴다 (플랜승인대기 → 구현대기)
        TaskStatusHistory latest = historyRepo.findByTaskIdOrderByAtDesc(taskId).get(0);
        assertThat(latest.getFromStatus()).isEqualTo(TaskStatus.INTERVIEW_REVIEW.dbValue());
        assertThat(latest.getToStatus()).isEqualTo(TaskStatus.APPROVED.dbValue());
    }

    @Test
    void confirm_with_design_requested_goes_to_the_design_queue() {
        toPlanReady();

        interviewService.confirm(sid, "admin", true);

        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(t.isDesignRequested()).isTrue();

        // 디자인대기로 가는 경우도 history에 남는다 (플랜승인대기 → 디자인대기)
        TaskStatusHistory latest = historyRepo.findByTaskIdOrderByAtDesc(taskId).get(0);
        assertThat(latest.getFromStatus()).isEqualTo(TaskStatus.INTERVIEW_REVIEW.dbValue());
        assertThat(latest.getToStatus()).isEqualTo(TaskStatus.DESIGN_PENDING.dbValue());
    }

    @Test
    void confirm_before_plan_ready_is_rejected() {
        assertThatThrownBy(() -> interviewService.confirm(sid, "admin", false))
                .isInstanceOf(TaskException.class);

        assertThat(taskRepo.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.INTERVIEWING);
    }

    @Test
    void confirm_twice_is_rejected() {
        toPlanReady();
        interviewService.confirm(sid, "admin", false);

        assertThatThrownBy(() -> interviewService.confirm(sid, "admin", false))
                .isInstanceOf(TaskException.class);
    }
}
