package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 플랜 확정이 고른 구현 방식을 작업에 저장하고 이력 사유에 남긴다 (스펙 2026-10-15 §5.1). */
class InterviewServiceConfirmModeTest {

    private TaskStatusHistoryRepository historyRepo;
    private InterviewService service;
    private Task task;

    @BeforeEach
    void setUp() {
        InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
        InterviewPlanRepository planRepo = mock(InterviewPlanRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, mock(InterviewTurnRepository.class), planRepo,
                taskRepo, analysisRepo, historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class),
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(s, "id", 25L);
        s.setStatus(InterviewStatus.PLAN_READY);
        s.setTaskId(13L);
        when(sessionRepo.findByIdForUpdate(25L)).thenReturn(Optional.of(s));

        task = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 13L);
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(task));
        when(planRepo.findById(25L)).thenReturn(Optional.of(
                InterviewPlan.create(25L, "# 설계", "# 플랜", "[]", 1000L, null)));
    }

    private String lastHistoryReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void confirm_stores_the_chosen_mode_and_names_it_in_history() {
        service.confirm(25L, "admin", false, ImplementationMode.REVIEW);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(task.getImplementationAttempt()).isEqualTo(1);
        assertThat(lastHistoryReason()).isEqualTo("플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 리뷰)");
    }

    @Test
    void the_design_queue_keeps_the_mode_for_the_later_implementation() {
        service.confirm(25L, "admin", true, ImplementationMode.SDD);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.SDD);
    }

    @Test
    void the_three_argument_confirm_stays_direct() {
        // 새 작업의 기본값이 이미 DIRECT라 그대로 두면 서비스가 쓰지 않아도 통과한다 — 다른 방식에서 시작한다.
        task.setImplementationMode(ImplementationMode.SDD);

        service.confirm(25L, "admin", false);

        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(lastHistoryReason()).endsWith("구현 방식: 직접)");
    }

    @Test
    void a_null_mode_is_stored_as_direct() {
        // 위와 같은 이유로 SDD에서 시작해, null이 정말 DIRECT로 덮어써지는지 본다.
        task.setImplementationMode(ImplementationMode.SDD);

        service.confirm(25L, "admin", false, null);

        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(lastHistoryReason()).endsWith("구현 방식: 직접)");
    }
}
