package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 구현의 참조 repo — 참조 지원을 알리지 않은 워커의 claim 큐(스펙 2026-10-24 §4.7)와
 * PR생성 보고의 참조 변경 경고 이력 행(§4.8).
 */
class WorkerServiceRefReposTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(TaskStatus status) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        return t;
    }

    private static WorkerResultRequest prCreated(String... warnings) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/a/b/pull/7", 7, "netismaker/task-7-t", "abc1234", "log",
                null, null, null, null, null,
                null, null, null, null, null)
                .withRefWarnings(warnings == null ? null : Arrays.asList(warnings));
    }

    private List<TaskStatusHistory> rows() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    private List<String> reasons() {
        return rows().stream().map(TaskStatusHistory::getReason).toList();
    }

    @Test
    void a_worker_without_ref_support_claims_through_the_filtered_queue() {
        service.claimNextTask("w1", false);

        verify(taskRepo).findClaimableExcludingRefImplementationsForUpdateSkipLocked();
        verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any());
    }

    @Test
    void a_ref_aware_worker_and_the_one_arg_call_use_the_full_queue() {
        service.claimNextTask("w1", true);
        service.claimNextTask("w1");

        verify(taskRepo, times(2)).findClaimableForUpdateSkipLocked(any(Pageable.class));
        verify(taskRepo, never()).findClaimableExcludingRefImplementationsForUpdateSkipLocked();
    }

    @Test
    void the_filtered_queue_claims_what_it_returns() {
        Task t = task(TaskStatus.PENDING);
        when(taskRepo.findClaimableExcludingRefImplementationsForUpdateSkipLocked()).thenReturn(List.of(t));

        assertThat(service.claimNextTask("w2", false)).isPresent();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(t.getWorkerId()).isEqualTo("w2");
    }

    @Test
    void pr_created_with_ref_warnings_logs_a_second_same_status_row() {
        Task t = task(TaskStatus.IMPLEMENTING);

        service.recordResult(7L, prCreated("옛앱: 파일 3개", "공통 UI: HEAD가 고정 커밋과 다름"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(reasons()).containsExactly("PR 생성: https://github.com/a/b/pull/7",
                "참조 repo 변경 감지(PR에는 포함 안 됨): 옛앱: 파일 3개, 공통 UI: HEAD가 고정 커밋과 다름");
        TaskStatusHistory warning = rows().get(1);
        assertThat(warning.getFromStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(warning.getToStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(warning.getActorType()).isEqualTo("worker");
        assertThat(warning.getActorId()).isEqualTo("w1");
    }

    @Test
    void late_pr_reconciliation_also_logs_the_warning_row() {
        Task t = task(TaskStatus.IMPLEMENTATION_FAILED);

        service.recordResult(7L, prCreated("옛앱: 파일 3개"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(reasons()).containsExactly("지각 보고 정합화: stale 회수 → PR_CREATED",
                "참조 repo 변경 감지(PR에는 포함 안 됨): 옛앱: 파일 3개");
        assertThat(rows().get(1).getFromStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
    }

    @Test
    void null_empty_or_blank_warnings_add_no_row() {
        task(TaskStatus.IMPLEMENTING);
        service.recordResult(7L, prCreated((String[]) null));
        task(TaskStatus.IMPLEMENTING);
        service.recordResult(7L, prCreated());
        task(TaskStatus.IMPLEMENTING);
        service.recordResult(7L, prCreated(" ", "", null));

        assertThat(reasons()).containsExactly("PR 생성: https://github.com/a/b/pull/7",
                "PR 생성: https://github.com/a/b/pull/7", "PR 생성: https://github.com/a/b/pull/7");
    }

    @Test
    void blank_warnings_are_dropped_and_the_rest_stripped() {
        task(TaskStatus.IMPLEMENTING);

        service.recordResult(7L, prCreated("  옛앱: 파일 1개 ", " ", null));

        assertThat(reasons()).containsExactly("PR 생성: https://github.com/a/b/pull/7",
                "참조 repo 변경 감지(PR에는 포함 안 됨): 옛앱: 파일 1개");
    }

    @Test
    void the_warning_row_is_masked_then_cut_to_500() {
        task(TaskStatus.IMPLEMENTING);
        // API는 워커의 masked()를 믿지 않는다 — 가리지 않은 토큰이 와도 이력에는 가려서, 500자로 잘라 남긴다.
        // 접두(28자) 뒤 450자 다음에 토큰 — 원문에서는 '@'가 500자 밖이라 먼저 자르면 토큰이 남는다
        String warning = "x".repeat(450) + " https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git" + "z".repeat(100);

        service.recordResult(7L, prCreated(warning));

        String row = reasons().get(1);
        assertThat(row).hasSize(500).doesNotContain("SECRET").doesNotContain("glpat")
                .startsWith("참조 repo 변경 감지(PR에는 포함 안 됨): " + "x".repeat(450) + " https://***@gitlab.");
    }

    @Test
    void implementation_failed_reports_add_no_warning_row() {
        task(TaskStatus.IMPLEMENTING);
        WorkerResultRequest failed = new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1 — 참조 repo 변경 감지: 옛앱: 파일 3개",
                null, null, "netismaker/task-7-t", null, null,
                null, null, null, null, null,
                null, null, null, null, null)
                .withRefWarnings(List.of("옛앱: 파일 3개"));

        service.recordResult(7L, failed);

        assertThat(reasons()).containsExactly("claude exit=1 — 참조 repo 변경 감지: 옛앱: 파일 3개");
    }

    @Test
    void an_undeploy_return_to_pr_created_adds_no_warning_row() {
        task(TaskStatus.UNDEPLOYING);

        service.recordResult(7L, WorkerResultRequest.undeployed("w1", "log").withRefWarnings(List.of("옛앱: 파일 3개")));

        assertThat(reasons()).containsExactly("배포 중지 → PR생성 복귀");
    }
}
