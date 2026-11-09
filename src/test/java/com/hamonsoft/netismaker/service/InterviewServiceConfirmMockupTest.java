package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 확정이 고른 목업을 작업에 저장하고 이력에 남긴다 (스펙 2026-11-03 §7). 하네스는 InterviewServiceConfirmModeTest와 같다. */
class InterviewServiceConfirmMockupTest {

    private TaskStatusHistoryRepository historyRepo;
    private TaskAnalysisRepository analysisRepo;
    private InterviewMockupService mockupService;
    private InterviewService service;
    private Task task;

    @BeforeEach
    void setUp() {
        InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
        InterviewPlanRepository planRepo = mock(InterviewPlanRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, mock(InterviewTurnRepository.class), planRepo,
                taskRepo, analysisRepo, historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class),
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
        mockupService = mock(InterviewMockupService.class);
        service.setInterviewMockupService(mockupService);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewSession session = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(session, "id", 25L);
        session.setStatus(InterviewStatus.PLAN_READY);
        session.setTaskId(13L);
        when(sessionRepo.findByIdForUpdate(25L)).thenReturn(Optional.of(session));

        task = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 13L);
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(task));
        when(planRepo.findById(25L)).thenReturn(Optional.of(
                InterviewPlan.create(25L, "# 설계", "목업 2 기준", "[]", 1000L, null)));
    }

    private List<String> reasons() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream().map(TaskStatusHistory::getReason).toList();
    }

    @Test
    void 고른_목업과_제외가_작업에_저장되고_이력은_참조_다음_행으로_남는다() {
        when(mockupService.resolveForConfirm(25L, List.of(10L), "목업 2 기준")).thenReturn(
                new InterviewMockupService.ConfirmSelection(List.of(10L), List.of(2),
                        "구현 목업: 목업 3(A안 v2) / 제외: 목업 2"));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT, List.of(10L));

        assertThat(task.getImplementationMockupIds()).containsExactly(10L);
        assertThat(task.getImplementationMockupExcluded()).containsExactly(2);
        assertThat(reasons()).containsExactly(
                "플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 직접)",
                "구현 목업: 목업 3(A안 v2) / 제외: 목업 2");
    }

    @Test
    void 전부_해제해도_제외는_저장하고_이력에_남긴다() {
        when(mockupService.resolveForConfirm(eq(25L), any(), any())).thenReturn(
                new InterviewMockupService.ConfirmSelection(List.of(), List.of(2), "구현 목업: 없음 / 제외: 목업 2"));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT, List.of());

        assertThat(task.getImplementationMockupIds()).isEmpty();
        assertThat(task.getImplementationMockupExcluded()).containsExactly(2);
        assertThat(reasons()).last().isEqualTo("구현 목업: 없음 / 제외: 목업 2");
    }

    @Test
    void 목업이_없으면_이력과_칸이_지금과_같다() {
        when(mockupService.resolveForConfirm(eq(25L), any(), any())).thenReturn(
                new InterviewMockupService.ConfirmSelection(List.of(), List.of(), null));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT);

        assertThat(task.getImplementationMockupIds()).isEmpty();
        assertThat(task.getImplementationMockupExcluded()).isEmpty();
        assertThat(reasons()).containsExactly("플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 직접)");
    }

    @Test
    void 서비스가_없는_구성은_목업_없음() {
        service.setInterviewMockupService(null);

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT, List.of(10L));

        assertThat(task.getImplementationMockupIds()).isEmpty();
        assertThat(task.getImplementationMockupExcluded()).isEmpty();
        assertThat(reasons()).hasSize(1);
    }

    @Test
    void 목업_검증이_400이면_작업_상태와_analysis는_그대로() {
        when(mockupService.resolveForConfirm(eq(25L), any(), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "이 인터뷰의 목업이 아닙니다: 99"));

        assertThatThrownBy(() -> service.confirm(25L, "admin", false, ImplementationMode.DIRECT, List.of(99L)))
                .isInstanceOf(TaskException.class);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.INTERVIEW_REVIEW);
        verify(analysisRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }
}
