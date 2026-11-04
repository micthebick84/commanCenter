package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 수정 회차의 구현실패 → 같은 회차·같은 브랜치에서 다시 (스펙 2026-11-03 §7). TaskServiceImplementationRetryTest와 같은 하네스.
 * 작업의 시도 번호·브랜치·head_sha·PR은 그대로 두고 회차 안 시도 번호만 올린다 — 첫 구현의 재시도처럼 -rN 브랜치를 새로 따지 않는다.
 */
class TaskServiceFixRoundRetryTest {

    private static final String BRANCH = "netismaker/task-42-t";
    private static final String HEAD = "wip7777abcdef0123456789abcdef0123456789";

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskFixRequestRepository fixRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), mock(RepoCatalogService.class), new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class),
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(QuestionAttachmentRepository.class), 200);
        service.setFixRequestRepository(fixRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fixRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** 수정 2회차의 구현이 실패한 작업 — 작업 자체는 DIRECT, 1회째 시도. */
    private Task failedRound() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setImplementationAttempt(1);
        t.setFailureReason("claude exit=1");
        t.setHeadBranch(BRANCH);
        t.setHeadSha(HEAD);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setPrNumber(3);
        t.setImplementationLog("log");
        t.setImplementationTimeoutMinutes(80);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now());
        t.setRestartRequeueCount(2);
        t.setActiveFixRequestId(502L);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    private TaskFixRequest round(int round, ImplementationMode mode, int attempt) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "대상장비 탭 갱신 안 됨", TaskStatus.DEPLOYED, "user1");
        ReflectionTestUtils.setField(r, "id", 502L);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(mode);
        r.setAttempt(attempt);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(r));
        return r;
    }

    private List<TaskStatusHistory> histories() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void 회차_구현재시도는_브랜치와_attempt를_지키고_수정시도만_올린다() {
        Task t = failedRound();
        TaskFixRequest r = round(2, ImplementationMode.REVIEW, 1);

        service.retryImplementation(42L, "admin", null);

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getHeadSha()).isEqualTo(HEAD);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/owner/repo/pull/3");
        assertThat(t.getPrNumber()).isEqualTo(3);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);   // 작업의 방식은 그대로
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(t.getRestartRequeueCount()).isZero();
        assertThat(r.getAttempt()).isEqualTo(2);
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        TaskStatusHistory row = histories().get(0);
        assertThat(row.getFromStatus()).isEqualTo("구현실패");
        assertThat(row.getToStatus()).isEqualTo("구현대기");
        assertThat(row.getReason()).isEqualTo("수정 2회차 구현 재시도 (2회째, 구현 방식: 리뷰)");
    }

    @Test
    void 회차_구현재시도는_회차의_방식을_바꾸고_작업의_방식은_두다() {
        Task t = failedRound();
        TaskFixRequest r = round(1, ImplementationMode.REVIEW, 2);

        service.retryImplementation(42L, "admin", "DIRECT");

        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(r.getAttempt()).isEqualTo(3);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(histories().get(0).getReason()).isEqualTo("수정 1회차 구현 재시도 (3회째, 구현 방식: 리뷰 → 직접)");
    }

    @Test
    void 회차_구현재시도에_SDD는_400() {
        Task t = failedRound();
        TaskFixRequest r = round(2, ImplementationMode.REVIEW, 1);

        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", "SDD"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.getAttempt()).isEqualTo(1);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 회차_기록을_못_찾으면_409와_수정_중단_안내() {
        Task t = failedRound();
        when(fixRepo.findById(502L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> {
                    assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("수정 회차 기록").contains("#502").contains("수정 중단");
                });
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
    }

    @Test
    void 승인되지_않은_요청을_가리켜도_409() {
        failedRound();
        TaskFixRequest r = round(2, ImplementationMode.REVIEW, 1);
        r.setStatus(FixRequestStatus.REJECTED);

        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(r.getAttempt()).isEqualTo(1);
    }

    @Test
    void 회차_구현재시도도_참조를_뺄_수_있다() {
        Task t = failedRound();
        t.setRefRepos(new ArrayList<>(List.of(
                new RefRepo(7L, "옛앱", "acme/old", null, "main", "abc1234", null, null))));
        round(2, ImplementationMode.REVIEW, 1);

        service.retryImplementation(42L, "admin", null, List.of(7L));

        assertThat(t.getRefRepos()).isEmpty();
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        List<TaskStatusHistory> rows = histories();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getReason()).startsWith("참조 제외(구현 재시도): 옛앱");
    }

    @Test
    void 회차가_없는_구현실패는_예전처럼_처음부터() {
        Task t = failedRound();
        t.setActiveFixRequestId(null);

        service.retryImplementation(42L, "admin", null);

        assertThat(t.getImplementationAttempt()).isEqualTo(2);
        assertThat(t.getHeadBranch()).isNull();
        verifyNoInteractions(fixRepo);
    }
}
