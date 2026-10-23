package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 구현실패 → 구현대기, 방식 다시 고르기 (스펙 2026-10-15 §5.5). */
class TaskServiceImplementationRetryTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), mock(RepoCatalogService.class), new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task failed(ImplementationMode mode) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setImplementationMode(mode);
        t.setFailureReason("claude exec 실패: claude timeout after PT4H");
        t.setHeadBranch("netismaker/task-42-t");
        t.setHeadSha("abc1234");
        t.setImplementationLog("log");
        t.setImplementationTimeoutMinutes(240);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now());
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    private String historyReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void retry_bumps_the_attempt_requeues_and_clears_the_failed_attempt() {
        Task t = failed(ImplementationMode.REVIEW);

        service.retryImplementation(42L, "admin", null);

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getImplementationAttempt()).isEqualTo(2);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getHeadBranch()).isNull();
        assertThat(t.getHeadSha()).isNull();
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(historyReason())
                .isEqualTo("구현 재시도 (2회째, 구현 방식: 리뷰, 이전 브랜치: netismaker/task-42-t)");
    }

    @Test
    void retry_resets_restart_requeue_count() {
        Task t = failed(ImplementationMode.REVIEW);
        t.setRestartRequeueCount(2);

        service.retryImplementation(42L, "admin", null);

        assertThat(t.getRestartRequeueCount()).isZero();
        assertThat(t.getImplementationAttempt()).isEqualTo(2);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    void retry_can_switch_the_mode() {
        Task t = failed(ImplementationMode.SDD);
        t.setHeadBranch(null);

        service.retryImplementation(42L, "admin", "REVIEW");

        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(historyReason()).isEqualTo("구현 재시도 (2회째, 구현 방식: SDD → 리뷰)");
    }

    @Test
    void the_previous_branch_is_noted_only_when_the_failed_attempt_pushed_it() {
        // 구현실패 보고는 push 여부와 무관하게 headBranch를 보내고 headSha는 push한 값만 보낸다 —
        // headSha가 없으면 그 브랜치는 원격에 없으니 이력이 가리키면 안 된다.
        Task t = failed(ImplementationMode.REVIEW);
        t.setHeadSha(null);

        service.retryImplementation(42L, "admin", null);

        assertThat(historyReason()).isEqualTo("구현 재시도 (2회째, 구현 방식: 리뷰)");
        assertThat(t.getHeadBranch()).isNull();
    }

    @Test
    void only_failed_implementations_can_be_retried() {
        Task t = failed(ImplementationMode.DIRECT);
        t.setStatus(TaskStatus.PR_CREATED);

        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
    }

    @Test
    void an_unknown_mode_is_400_before_touching_the_task() {
        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", "FAST"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void a_missing_or_deleted_task_is_404() {
        when(taskRepo.findActiveByIdForUpdate(43L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryImplementation(43L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
