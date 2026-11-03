package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 수정 회차의 구현중 행업 판정은 작업이 아니라 회차의 구현 방식을 따른다 (스펙 2026-11-03 §6.2 — 회차의 방식은 요청 기록에 있다).
 * 판정값은 기본 정책(직접 60분 / 리뷰 95분 / SDD 260분). 워커는 살아 있어 행업 백스톱만 본다.
 */
class StaleTaskRecoveryJobFixRoundTest {

    private TaskRepository taskRepo;
    private WorkerHeartbeatRepository heartbeatRepo;
    private TaskFixRequestRepository fixRepo;
    private StaleTaskRecoveryJob job;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        when(deployLogStream.consolidate(any())).thenReturn(Optional.empty());
        job = new StaleTaskRecoveryJob(taskRepo, heartbeatRepo,
                new TaskRecovery(historyRepo, deployLogStream, fixRepo), ImplementationModePolicy.defaults(), fixRepo);
        ReflectionTestUtils.setField(job, "implementationWorkerDeadThresholdSeconds", 300);
        ReflectionTestUtils.setField(job, "startedAt", OffsetDateTime.now().minusHours(3));
    }

    private Task implementing(ImplementationMode taskMode, int claimedMinAgo, Long activeFixRequestId) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        t.setImplementationMode(taskMode);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(claimedMinAgo));
        t.setActiveFixRequestId(activeFixRequestId);
        when(taskRepo.findInFlightClaimedForUpdateSkipLocked()).thenReturn(List.of(t));
        WorkerHeartbeat hb = new WorkerHeartbeat();
        hb.setWorkerId("w1");
        hb.setLastSeenAt(OffsetDateTime.now().minusSeconds(5));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb));
        return t;
    }

    private void round(ImplementationMode mode) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "수정", TaskStatus.DEPLOYED, "u");
        ReflectionTestUtils.setField(r, "id", 502L);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(1);
        r.setImplementationMode(mode);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(r));
    }

    @Test
    void 회차_구현중_판정은_회차_방식을_따른다() {
        Task t = implementing(ImplementationMode.DIRECT, 70, 502L);   // 작업 방식이면 60분 → 회수됐을 것
        round(ImplementationMode.REVIEW);                              // 회차는 리뷰 — 95분

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
    }

    @Test
    void 직접_방식_회차는_작업이_리뷰여도_60분에_회수한다() {
        Task t = implementing(ImplementationMode.REVIEW, 70, 502L);
        round(ImplementationMode.DIRECT);

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("처리 시간 초과(60분)");
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
    }

    @Test
    void 회차_방식이_비어_있으면_리뷰로_본다() {
        Task t = implementing(ImplementationMode.DIRECT, 70, 502L);
        round(null);

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
    }

    @Test
    void 회차가_없는_구현중은_작업_방식_그대로() {
        Task t = implementing(ImplementationMode.DIRECT, 70, null);

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        verifyNoInteractions(fixRepo);
    }
}
