package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * 구현 claim에 수정 회차를 싣는다 + fixRounds를 모르는 워커에게는 회차 구현을 내주지 않는다
 * (스펙 2026-11-03 §7 — 구버전 jar는 회차 필드를 버리고 base에서 -rN 브랜치를 새로 따 버린다).
 */
class WorkerServiceFixRoundClaimTest {

    private static final WorkerCapabilities ALL = new WorkerCapabilities(true, true);
    private static final String HEAD_BRANCH = "netismaker/task-42-x";
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";

    private TaskRepository taskRepo;
    private TaskFixRequestRepository fixRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerService svc;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        svc = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        svc.setFixRequestRepository(fixRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** 배포된 작업에 수정 회차가 열려 구현대기가 된 상태 — 작업 자체의 구현 방식은 DIRECT. */
    private Task fixRoundTask(Long activeFixRequestId) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.APPROVED);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setImplementationAttempt(1);
        t.setHeadBranch(HEAD_BRANCH);
        t.setHeadSha(HEAD_SHA);
        t.setActiveFixRequestId(activeFixRequestId);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        return t;
    }

    private TaskFixRequest approved(long id, int round, ImplementationMode mode, int attempt, String body) {
        TaskFixRequest r = TaskFixRequest.pending(42L, body, TaskStatus.DEPLOYED, "user1");
        ReflectionTestUtils.setField(r, "id", id);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(mode);
        r.setAttempt(attempt);
        return r;
    }

    @Test
    void 회차_작업_claim은_fix를_싣는다() {
        Task t = fixRoundTask(502L);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(
                approved(502L, 2, ImplementationMode.REVIEW, 1, "대상장비 탭 갱신 안 됨")));
        when(fixRepo.findByTaskIdAndStatusAndRoundLessThanOrderByRoundAsc(42L, FixRequestStatus.APPROVED, 2))
                .thenReturn(List.of(approved(501L, 1, ImplementationMode.DIRECT, 1, "장기분석 로딩")));

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.implementation().mode()).isEqualTo("REVIEW");
        WorkerTaskResponse.FixRoundSpec fix = r.implementation().fix();
        assertThat(fix).isNotNull();
        assertThat(fix.requestId()).isEqualTo(502L);
        assertThat(fix.round()).isEqualTo(2);
        assertThat(fix.attempt()).isEqualTo(1);
        assertThat(fix.body()).isEqualTo("대상장비 탭 갱신 안 됨");
        assertThat(fix.previousBodies()).containsExactly("장기분석 로딩");
        assertThat(fix.headBranch()).isEqualTo("netismaker/task-42-x");
        assertThat(fix.headSha()).isEqualTo(HEAD_SHA);
        assertThat(r.implementation().timeoutMinutes()).isEqualTo(80);   // REVIEW 상한(작업 방식 DIRECT여도)
        assertThat(t.getImplementationTimeoutMinutes()).isEqualTo(80);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
        assertThat(t.getWorkerId()).isEqualTo("w1");
    }

    @Test
    void 작업의_구현_방식과_시도_번호는_건드리지_않는다() {
        Task t = fixRoundTask(502L);
        t.setImplementationAttempt(3);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(
                approved(502L, 1, ImplementationMode.REVIEW, 2, "수정")));

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(t.getImplementationAttempt()).isEqualTo(3);
        // 결과 보고가 되돌려 보내는 시도 번호는 작업의 것(그대로), 회차 안 시도 번호는 fix.attempt
        assertThat(r.implementation().attempt()).isEqualTo(3);
        assertThat(r.implementation().fix().attempt()).isEqualTo(2);
    }

    @Test
    void 직접_방식_회차는_직접_상한이고_작업이_리뷰여도_회차_방식을_따른다() {
        Task t = fixRoundTask(502L);
        t.setImplementationMode(ImplementationMode.REVIEW);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(
                approved(502L, 1, ImplementationMode.DIRECT, 1, "수정")));

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.implementation().mode()).isEqualTo("DIRECT");
        assertThat(r.implementation().timeoutMinutes()).isEqualTo(45);
        assertThat(t.getImplementationTimeoutMinutes()).isEqualTo(45);
    }

    @Test
    void 회차_방식이_비어_있으면_작업_방식이_아니라_리뷰로_본다() {
        // 수정 회차는 직접·리뷰만 고른다 — 작업 방식(여기 SDD)으로 떨어지면 원래 플랜 전체를 다시 구현하게 된다(스펙 §6.2)
        Task t = fixRoundTask(502L);
        t.setImplementationMode(ImplementationMode.SDD);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(approved(502L, 1, null, 1, "수정")));

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.implementation().mode()).isEqualTo("REVIEW");
        assertThat(r.implementation().timeoutMinutes()).isEqualTo(80);
        assertThat(t.getImplementationTimeoutMinutes()).isEqualTo(80);
    }

    @Test
    void 첫_회차는_이전_본문이_빈_목록이다() {
        fixRoundTask(501L);
        when(fixRepo.findById(501L)).thenReturn(Optional.of(
                approved(501L, 1, ImplementationMode.REVIEW, 1, "장기분석 로딩")));
        when(fixRepo.findByTaskIdAndStatusAndRoundLessThanOrderByRoundAsc(42L, FixRequestStatus.APPROVED, 1))
                .thenReturn(List.of());

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.implementation().fix().previousBodies()).isEmpty();
    }

    @Test
    void 회차_없는_구현_claim은_fix가_null() {
        Task t = fixRoundTask(null);
        t.setImplementationMode(ImplementationMode.SDD);

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.implementation().fix()).isNull();
        assertThat(r.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("SDD", 1, 240, null, null,
                new WorkerTaskResponse.DbWriteSpec("db/symphony/task-42.sql", false, false)));
        verifyNoInteractions(fixRepo);
    }

    @Test
    void 회차_작업이_아닌_다른_종류_claim은_수정_요청을_조회하지_않는다() {
        Task t = fixRoundTask(502L);
        t.setStatus(TaskStatus.DEPLOY_PENDING);   // 회차 안의 자동 재배포 — 회차 id는 남아 있다

        WorkerTaskResponse r = svc.claimNextTask("w1", ALL, null).orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
        verifyNoInteractions(fixRepo);
    }

    @Test
    void 회차_기록을_못_찾으면_새_브랜치를_따지_않고_구현실패로_둔다() {
        Task t = fixRoundTask(502L);
        when(fixRepo.findById(502L)).thenReturn(Optional.empty());

        assertThat(svc.claimNextTask("w1", ALL, null)).isEmpty();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("수정 회차");
        assertThat(t.getWorkerId()).isNull();
        ArgumentCaptor<TaskStatusHistory> row = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(row.capture());
        assertThat(row.getValue().getFromStatus()).isEqualTo(TaskStatus.APPROVED.dbValue());
        assertThat(row.getValue().getToStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED.dbValue());
    }

    @Test
    void 승인되지_않은_요청을_가리키면_구현실패로_둔다() {
        Task t = fixRoundTask(502L);
        TaskFixRequest pending = approved(502L, 1, ImplementationMode.REVIEW, 1, "수정");
        pending.setStatus(FixRequestStatus.PENDING);
        pending.setRound(null);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(pending));

        assertThat(svc.claimNextTask("w1", ALL, null)).isEmpty();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }

    @Test
    void 이미_닫힌_요청을_가리키면_구현실패로_둔다() {
        Task t = fixRoundTask(502L);
        TaskFixRequest closed = approved(502L, 1, ImplementationMode.REVIEW, 1, "수정");
        closed.setOutcome(com.hamonsoft.netismaker.entity.FixOutcome.DEPLOYED);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(closed));

        assertThat(svc.claimNextTask("w1", ALL, null)).isEmpty();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }

    // ── 후보 쿼리 고르기 ─────────────────────────────────

    @Test
    void 모든_기능을_아는_워커는_기존_전체_큐를_쓴다() {
        svc.claimNextTask("w1", ALL, null);
        svc.claimNextTask("w1");

        verify(taskRepo, times(2)).findClaimableForUpdateSkipLocked(any(Pageable.class));
        verify(taskRepo, never()).findClaimableAmongForUpdateSkipLocked(anyCollection(), anyBoolean(), anyBoolean());
    }

    @Test
    void fixRounds를_모르는_워커는_회차를_거르는_큐를_쓴다() {
        svc.claimNextTask("w1", new WorkerCapabilities(true, false), null);

        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES, true, false);
        verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any(Pageable.class));
    }

    @Test
    void refRepos를_모르는_워커도_거르는_큐를_쓴다() {
        svc.claimNextTask("w1", new WorkerCapabilities(false, true), null);
        svc.claimNextTask("w1", new WorkerCapabilities(false, false), null);

        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES, false, true);
        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES, false, false);
        verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any(Pageable.class));
    }

    @Test
    void 종류를_고른_워커에게도_두_기능_표시를_그대로_넘긴다() {
        var kinds = EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN);

        svc.claimNextTask("remote-1", new WorkerCapabilities(true, false), kinds);
        svc.claimNextTask("remote-1", new WorkerCapabilities(true, true), kinds);

        List<TaskStatus> statuses = List.of(TaskStatus.APPROVED, TaskStatus.DESIGN_PENDING);
        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(statuses, true, false);
        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(statuses, true, true);
        verify(taskRepo, never()).findClaimableForUpdateSkipLocked(any(Pageable.class));
    }

    @Test
    void 예전_오버로드의_뜻은_그대로_fixRounds만_false() {
        svc.claimNextTask("w1", true);
        svc.claimNextTask("w1", false);
        svc.claimNextTask("remote-1", true, EnumSet.of(WorkerTaskResponse.Kind.CLOSE));

        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES, true, false);
        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(TaskRepository.CLAIMABLE_STATUSES, false, false);
        verify(taskRepo).findClaimableAmongForUpdateSkipLocked(List.of(TaskStatus.CLOSE_PENDING), true, false);
    }
}
