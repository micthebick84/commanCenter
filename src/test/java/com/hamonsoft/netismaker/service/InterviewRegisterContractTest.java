package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewRegisterContractTest {

    @Autowired private InterviewService interviewService;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;

    private Long sid;

    @BeforeEach void seed() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        InterviewSession s = InterviewSession.create("hamonsoft/netis-backend", "feat/rbac",
                "RBAC 추가", "역할 기반 권한", "user1",
                List.of(new TaskMcpSpec("ctx7", "https://ctx7", "http")), "claude-opus-4-8", "high");
        sid = sessionRepo.save(s).getId();
    }

    private void toPlanReady() {
        interviewService.claim("iw-1");                              // QUEUED → RUNNING (work_dir 할당)
        interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                "# 설계 문서", "# 구현 플랜", "[{\"title\":\"A\"},{\"title\":\"B\"}]",
                new BigDecimal("0.42"), 30000L));                    // RUNNING → PLAN_READY
    }

    @Test
    void register_creates_completed_task_with_design_only_analysis() {
        toPlanReady();
        Long taskId = interviewService.register(sid, "user1", false);

        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(t.getGithubRepo()).isEqualTo("hamonsoft/netis-backend");
        assertThat(t.getGithubBranch()).isEqualTo("feat/rbac");
        assertThat(t.getRequesterId()).isEqualTo("user1");
        assertThat(t.getMcpsExtra()).extracting(TaskMcpSpec::name).containsExactly("ctx7");

        TaskAnalysis a = analysisRepo.findById(taskId).orElseThrow();
        // LOCKED CONTRACT v2: markdownResult = design_markdown ONLY (합본 아님)
        assertThat(a.getMarkdownResult()).isEqualTo("# 설계 문서");
        assertThat(a.getSubtasksJson()).isEqualTo("[{\"title\":\"A\"},{\"title\":\"B\"}]");
        assertThat(a.getClaudeLog()).isNull();      // claudeLog = null
        assertThat(a.isApproved()).isFalse();       // 관리자 승인 게이트 유지

        InterviewSession s = sessionRepo.findById(sid).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.REGISTERED);
        assertThat(s.getTaskId()).isEqualTo(taskId);
    }

    @Test
    void register_before_plan_ready_is_rejected() {
        assertThatThrownBy(() -> interviewService.register(sid, "user1", false))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("플랜완료");
    }

    @Test
    void register_is_idempotent_after_first_call() {
        toPlanReady();
        Long first = interviewService.register(sid, "user1", false);
        assertThatThrownBy(() -> interviewService.register(sid, "user1", false))
                .isInstanceOf(TaskException.class);
        assertThat(taskRepo.count()).isEqualTo(1);
        assertThat(taskRepo.findById(first)).isPresent();
    }

    @Test
    void register_non_owner_is_forbidden() {
        toPlanReady();
        assertThatThrownBy(() -> interviewService.register(sid, "user2", false))
                .isInstanceOf(TaskException.class);
    }
}
