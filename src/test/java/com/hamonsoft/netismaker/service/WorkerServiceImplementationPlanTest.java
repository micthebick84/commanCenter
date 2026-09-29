package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.InterviewPlan;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewPlanRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 구현 claim에 인터뷰 확정 플랜 본문(plan_markdown)을 싣는다.
 *
 * confirm은 task_analysis.markdown_result에 design만 넣으므로(LOCKED CONTRACT v2), 플랜 본문은
 * 구현 프롬프트에 가지 않았다 — 2026-09-29 작업 #3은 design 0자라 "사전 분석 결과"가 빈칸이었고
 * 구현 AI가 "인터뷰 결과가 전달되지 않았다"며 범위를 스스로 정했다.
 */
class WorkerServiceImplementationPlanTest {

    private TaskRepository taskRepo;
    private InterviewSessionRepository sessionRepo;
    private InterviewPlanRepository planRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        sessionRepo = mock(InterviewSessionRepository.class);
        planRepo = mock(InterviewPlanRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                sessionRepo, planRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task approvedTask() {
        Task t = Task.create("owner/repo", "main", "README에 사용 방법 섹션 추가", "desc", "user1", 3,
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 3L);
        t.setStatus(TaskStatus.APPROVED);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        return t;
    }

    private InterviewSession session(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "t", "d", "user1",
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        return s;
    }

    private static InterviewPlan plan(long sessionId, String planMarkdown) {
        return InterviewPlan.create(sessionId, "", planMarkdown,
                "[{\"task\":1,\"title\":\"섹션 삽입\"}]", 1000L, null);
    }

    @Test
    void implementation_claim_carries_the_registered_interview_plan_markdown() {
        approvedTask();
        when(sessionRepo.findByTaskIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(session(11L, InterviewStatus.REGISTERED)));
        when(planRepo.findById(11L)).thenReturn(Optional.of(plan(11L, "# README 구현 계획\n\n### 작업 1: 섹션 삽입")));

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.planMarkdown()).isEqualTo("# README 구현 계획\n\n### 작업 1: 섹션 삽입");
    }

    @Test
    void picks_the_registered_session_even_when_a_newer_session_was_cancelled() {
        approvedTask();
        when(sessionRepo.findByTaskIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(
                session(12L, InterviewStatus.CANCELLED), session(11L, InterviewStatus.REGISTERED)));
        when(planRepo.findById(11L)).thenReturn(Optional.of(plan(11L, "# 확정 플랜 구현 계획")));
        when(planRepo.findById(12L)).thenReturn(Optional.of(plan(12L, "# 취소된 세션 구현 계획")));

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.planMarkdown()).isEqualTo("# 확정 플랜 구현 계획");
    }

    @Test
    void task_without_a_registered_interview_has_no_plan() {
        approvedTask();
        when(sessionRepo.findByTaskIdOrderByCreatedAtDesc(3L)).thenReturn(List.of());

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.planMarkdown()).isNull();
    }
}
