package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.TaskRecovery.RecoveryCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 수정 회차의 회수 전이 (스펙 2026-11-03 §7) — 재기동 재큐가 회차 브랜치·head_sha·PR·시도 번호를 지우지 않고(prepareFixRoundRequeue)
 * 회차 안 시도 번호만 올리며, 자동 재배포 중 회수는 배포실패와 함께 회차를 닫는다.
 */
class TaskRecoveryFixRoundTest {

    private static final String BRANCH = "netismaker/task-42-x";
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String PR_URL = "https://github.com/a/b/pull/3";

    private TaskStatusHistoryRepository historyRepo;
    private DeployLogStreamService deployLogStream;
    private TaskFixRequestRepository fixRepo;
    private TaskRecovery recovery;

    @BeforeEach
    void setUp() {
        historyRepo = mock(TaskStatusHistoryRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        when(deployLogStream.consolidate(any())).thenReturn(Optional.empty());
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fixRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        recovery = new TaskRecovery(historyRepo, deployLogStream, fixRepo);
    }

    private Task roundTask(TaskStatus status) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t.setImplementationAttempt(1);
        t.setImplementationTimeoutMinutes(80);
        t.setImplementationLog("partial");
        t.setHeadBranch(BRANCH);
        t.setHeadSha(HEAD);
        t.setPrUrl(PR_URL);
        t.setPrNumber(3);
        t.setActiveFixRequestId(502L);
        return t;
    }

    private TaskFixRequest request(int round, int attempt) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "수정", TaskStatus.DEPLOYED, "u");
        ReflectionTestUtils.setField(r, "id", 502L);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(ImplementationMode.REVIEW);
        r.setAttempt(attempt);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(r));
        return r;
    }

    private TaskStatusHistory savedHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void 재기동_재큐는_회차면_prepareFixRoundRequeue() {
        Task t = roundTask(TaskStatus.IMPLEMENTING);
        TaskFixRequest r = request(2, 1);

        TaskStatus to = recovery.recover(t, RecoveryCause.workerRestart("w1"));

        assertThat(to).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getHeadSha()).isEqualTo(HEAD);
        assertThat(t.getPrUrl()).isEqualTo(PR_URL);
        assertThat(t.getPrNumber()).isEqualTo(3);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);   // -rN 브랜치를 새로 따지 않게
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(t.getRestartRequeueCount()).isEqualTo(1);
        assertThat(r.getAttempt()).isEqualTo(2);
        TaskStatusHistory h = savedHistory();
        assertThat(h.getActorType()).isEqualTo("system");
        assertThat(h.getActorId()).isEqualTo("worker-restart");
        assertThat(h.getReason()).isEqualTo("워커 w1 재기동으로 중단 → 자동 재큐 (수정 2회차 2회째 시도, 재기동 재큐 1/2)");
    }

    @Test
    void 회차_재큐도_재기동_상한을_넘으면_구현실패로_회차는_열어_둔다() {
        Task t = roundTask(TaskStatus.IMPLEMENTING);
        t.setRestartRequeueCount(TaskRecovery.MAX_RESTART_REQUEUES);
        TaskFixRequest r = request(2, 3);

        recovery.recover(t, RecoveryCause.workerRestart("w1"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);   // 관리자가 재시도·수정 중단을 고른다
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(r.getAttempt()).isEqualTo(3);
    }

    @Test
    void 회차의_stale_구현중은_구현실패로_회차는_열어_둔다() {
        Task t = roundTask(TaskStatus.IMPLEMENTING);
        TaskFixRequest r = request(2, 1);

        recovery.recover(t, RecoveryCause.stale("처리 시간 초과(95분)"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
        assertThat(t.getHeadSha()).isEqualTo(HEAD);
        assertThat(r.getAttempt()).isEqualTo(1);
        assertThat(r.getOutcome()).isNull();
    }

    @Test
    void 회차_기록이_없으면_재큐하지_않고_구현실패() {
        // 재큐하면 claim이 다시 구현실패로 치운다 — 바로 구현실패로 두고 사유를 남긴다(관리자는 수정 중단으로 빠져나온다)
        Task t = roundTask(TaskStatus.IMPLEMENTING);
        when(fixRepo.findById(502L)).thenReturn(Optional.empty());

        recovery.recover(t, RecoveryCause.workerRestart("w1"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("수정 회차 기록").contains("#502");
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getRestartRequeueCount()).isZero();
    }

    @Test
    void 회차_배포중_회수는_배포실패와_회차닫기() {
        Task t = roundTask(TaskStatus.DEPLOYING);
        TaskFixRequest r = request(2, 1);

        TaskStatus to = recovery.recover(t, RecoveryCause.stale("워커 w1 응답 없음(heartbeat)"));

        assertThat(to).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOY_FAILED);
        assertThat(r.getClosedAt()).isNotNull();
        assertThat(savedHistory().getReason()).isEqualTo("배포중 stale → 배포실패 (수정 2회차 닫힘)");
        verify(deployLogStream).finish(42L);
    }

    @Test
    void 재기동으로_회차_배포가_끊겨도_회차를_닫는다() {
        Task t = roundTask(TaskStatus.DEPLOYING);
        TaskFixRequest r = request(1, 1);

        recovery.recover(t, RecoveryCause.workerRestart("w1"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOY_FAILED);
        assertThat(savedHistory().getReason()).isEqualTo("워커 w1 재기동으로 중단 → 배포실패 (수정 1회차 닫힘)");
    }

    @Test
    void 회차가_없는_작업의_회수는_예전과_같다() {
        Task t = roundTask(TaskStatus.DEPLOYING);
        t.setActiveFixRequestId(null);

        recovery.recover(t, RecoveryCause.stale("워커 w1 응답 없음(heartbeat)"));

        assertThat(savedHistory().getReason()).isEqualTo("배포중 stale → 배포실패");
        verifyNoInteractions(fixRepo);
    }
}
