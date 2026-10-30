package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 받을 작업 종류를 고른 워커(next-task kinds)의 claim — 다른 PC 워커가 종료·배포를 잡지 않게. */
class WorkerServiceKindsTest {

    private TaskRepository taskRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void every_kind_maps_to_one_claimable_status() {
        List<TaskStatus> mapped = Arrays.stream(WorkerTaskResponse.Kind.values())
                .map(WorkerService::claimableStatusFor).toList();

        assertThat(mapped).doesNotHaveDuplicates();
        assertThat(mapped).containsExactlyInAnyOrderElementsOf(TaskRepository.CLAIMABLE_STATUSES);
    }

    @Test
    void chosen_kinds_claim_only_their_pending_statuses() {
        service.claimNextTask("remote-1", true,
                EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN));

        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(
                List.of(TaskStatus.APPROVED, TaskStatus.DESIGN_PENDING), true);
        verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any(Pageable.class));
        verify(taskRepo, never()).findClaimableExcludingRefImplementationsForUpdateSkipLocked();
    }

    @Test
    void ref_support_is_passed_through() {
        service.claimNextTask("remote-1", false, EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION));

        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(List.of(TaskStatus.APPROVED), false);
    }

    @Test
    void the_filtered_claim_moves_the_task_like_the_full_queue() {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 9L);
        t.setStatus(TaskStatus.DESIGN_PENDING);
        when(taskRepo.findClaimableAmongForUpdateSkipLocked(anyCollection(), anyBoolean())).thenReturn(List.of(t));

        WorkerTaskResponse r = service.claimNextTask("remote-1", true, EnumSet.of(WorkerTaskResponse.Kind.DESIGN))
                .orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGNING);
        assertThat(t.getWorkerId()).isEqualTo("remote-1");
    }

    @Test
    void nothing_waiting_returns_empty() {
        when(taskRepo.findClaimableAmongForUpdateSkipLocked(anyCollection(), anyBoolean())).thenReturn(List.of());

        assertThat(service.claimNextTask("remote-1", true, EnumSet.of(WorkerTaskResponse.Kind.CLOSE))).isEmpty();
    }
}
