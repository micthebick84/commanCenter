package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 인터뷰 서비스 재기동 정합 전이(releaseForRestart) — 스펙 2026-10-23 §3.3, D13(횟수는 턴 단위).
 */
class InterviewServiceRestartTest {

    InterviewSessionRepository sessionRepo;
    InterviewTurnRepository turnRepo;
    TaskRepository taskRepo;
    TaskStatusHistoryRepository historyRepo;
    InterviewService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, turnRepo, mock(InterviewPlanRepository.class),
                taskRepo, mock(TaskAnalysisRepository.class), historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class),
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(turnRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private InterviewSession running(long id, String workerId, int restartCount) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId(workerId);
        s.setClaimedAt(OffsetDateTime.now().minusMinutes(5));
        s.setLastActivityAt(OffsetDateTime.now().minusMinutes(5));
        s.setRestartRequeueCount(restartCount);
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        return s;
    }

    private Task mirroredTask(InterviewSession s, long taskId, TaskStatus status) {
        s.setTaskId(taskId);
        Task t = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", taskId);
        t.setStatus(status);
        when(taskRepo.findActiveByIdForUpdate(taskId)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void release_requeues_running_session() {
        InterviewSession s = running(1L, "iw-1", 0);
        OffsetDateTime before = s.getLastActivityAt();
        // 실제 전이가 일어나도록 INTERVIEW_INPUT인 task로 둔다 (INTERVIEWING으로 미러되는지 본다)
        Task t = mirroredTask(s, 77L, TaskStatus.INTERVIEW_INPUT);

        Optional<InterviewService.RestartOutcome> out = service.releaseForRestart(1L, "iw-1");

        assertThat(out).isPresent();
        assertThat(out.get().released()).isTrue();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getClaimedAt()).isNull();
        assertThat(s.getRestartRequeueCount()).isEqualTo(1);
        assertThat(s.getLastActivityAt()).isAfter(before);
        assertThat(out.get().note().getRole()).isEqualTo("system");
        assertThat(out.get().note().getKind()).isEqualTo("note");
        assertThat(out.get().note().getContent()).isEqualTo("인터뷰 서비스가 다시 시작돼 이 단계를 다시 진행합니다.");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        verify(historyRepo).save(any());
    }

    @Test
    void release_fails_after_two() {
        InterviewSession s = running(2L, "iw-1", 2);
        Task t = mirroredTask(s, 78L, TaskStatus.INTERVIEWING);

        Optional<InterviewService.RestartOutcome> out = service.releaseForRestart(2L, "iw-1");

        assertThat(out).isPresent();
        assertThat(out.get().released()).isFalse();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);
        assertThat(s.getWorkerId()).isNull();
        assertThat(out.get().note().getContent())
                .isEqualTo("인터뷰 실패: 인터뷰 서비스 재기동으로 중단(자동 재개 2회 소진)");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void release_ignores_other_worker_or_not_running() {
        InterviewSession other = running(3L, "iw-2", 0);
        assertThat(service.releaseForRestart(3L, "iw-1")).isEmpty();
        assertThat(other.getStatus()).isEqualTo(InterviewStatus.RUNNING);
        assertThat(other.getWorkerId()).isEqualTo("iw-2");
        assertThat(other.getRestartRequeueCount()).isZero();

        InterviewSession awaiting = running(4L, "iw-1", 0);
        awaiting.setStatus(InterviewStatus.AWAITING_INPUT);
        awaiting.setWorkerId(null);
        assertThat(service.releaseForRestart(4L, "iw-1")).isEmpty();
        assertThat(awaiting.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);

        verify(turnRepo, never()).save(any());
    }

    @Test
    void question_session_released_without_task_mirror() {
        InterviewSession s = InterviewSession.createQuestion("owner/repo", "main", "T", "q?", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", 5L);
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId("iw-1");
        when(sessionRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(s));

        Optional<InterviewService.RestartOutcome> out = service.releaseForRestart(5L, "iw-1");

        assertThat(out).isPresent();
        assertThat(out.get().released()).isTrue();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        verifyNoInteractions(taskRepo);
    }

    @Test
    void record_question_resets_restart_count() {
        InterviewSession s = running(6L, "iw-1", 2);

        service.recordQuestion(6L, "iw-1", new WorkerQuestionRequest("답", "sess", "question",
                new BigDecimal("0.01"), null, null, null, null, null, null));

        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getRestartRequeueCount()).isZero();
    }

    @Test
    void running_session_ids_of_delegates_to_repository() {
        when(sessionRepo.findRunningIdsByWorker("iw-1")).thenReturn(List.of(1L, 2L));
        assertThat(service.runningSessionIdsOf("iw-1")).containsExactly(1L, 2L);
    }
}
