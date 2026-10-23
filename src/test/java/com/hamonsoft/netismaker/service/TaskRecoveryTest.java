package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
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
 * 회수 전이 단위 테스트 — stale 원인의 문구·행위자는 StaleTaskRecoveryJob이 쓰던 것과 같아야 한다.
 */
class TaskRecoveryTest {

    private static final String WHY = "워커 w1 응답 없음(heartbeat)";

    private TaskStatusHistoryRepository historyRepo;
    private DeployLogStreamService deployLogStream;
    private TaskRecovery recovery;

    @BeforeEach
    void setUp() {
        historyRepo = mock(TaskStatusHistoryRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        when(deployLogStream.consolidate(any())).thenReturn(Optional.empty());
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        recovery = new TaskRecovery(historyRepo, deployLogStream);
    }

    private Task task(long id, TaskStatus status, int retry) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(10));
        t.setRetryCount(retry);
        return t;
    }

    private TaskStatusHistory savedHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void stale_cause_keeps_existing_messages() {
        RecoveryCause cause = RecoveryCause.stale(WHY);
        assertThat(cause.actorId()).isEqualTo("stale-recovery");
        assertThat(cause.label()).isEqualTo("Stale 회수: " + WHY);
        assertThat(cause.requeueImplementation()).isFalse();

        // 구현중 → 구현실패
        Task impl = task(1L, TaskStatus.IMPLEMENTING, 0);
        assertThat(recovery.recover(impl, cause)).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(impl.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(impl.getFailureReason()).isEqualTo("Stale 회수: " + WHY + " (구현 중단)");
        assertThat(impl.getWorkerId()).isNull();
        assertThat(impl.getClaimedAt()).isNull();
        TaskStatusHistory h1 = savedHistory();
        assertThat(h1.getActorType()).isEqualTo("system");
        assertThat(h1.getActorId()).isEqualTo("stale-recovery");
        assertThat(h1.getReason()).isEqualTo("구현중 stale → 구현실패");

        // 배포중 → 배포실패 (부분 로그 보존)
        reset(historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deployLogStream.consolidate(2L)).thenReturn(Optional.of("partial"));
        Task dep = task(2L, TaskStatus.DEPLOYING, 0);
        assertThat(recovery.recover(dep, cause)).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(dep.getFailureReason()).isEqualTo("Stale 회수: " + WHY + " (배포 중단)");
        assertThat(dep.getDeployLog()).isEqualTo("partial");
        verify(deployLogStream).finish(2L);
        TaskStatusHistory h2 = savedHistory();
        assertThat(h2.getActorId()).isEqualTo("stale-recovery");
        assertThat(h2.getReason()).isEqualTo("배포중 stale → 배포실패");

        // 분석중, 재시도 여유 → 대기(+1)
        reset(historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Task retry = task(3L, TaskStatus.IN_PROGRESS, 1);
        assertThat(recovery.recover(retry, cause)).isEqualTo(TaskStatus.PENDING);
        assertThat(retry.getRetryCount()).isEqualTo(2);
        TaskStatusHistory h3 = savedHistory();
        assertThat(h3.getActorId()).isEqualTo("stale-recovery");
        assertThat(h3.getReason()).isEqualTo("Stale 회수: " + WHY + " → 재시도 (2/3)");

        // 분석중, 재시도 소진 → 실패
        reset(historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Task exhausted = task(4L, TaskStatus.IN_PROGRESS, 3);
        assertThat(recovery.recover(exhausted, cause)).isEqualTo(TaskStatus.FAILED);
        assertThat(exhausted.getRetryCount()).isEqualTo(3);
        assertThat(exhausted.getFailureReason()).isEqualTo("Stale 회수 한도 초과: " + WHY);
        TaskStatusHistory h4 = savedHistory();
        assertThat(h4.getActorId()).isEqualTo("stale-recovery");
        assertThat(h4.getReason()).isEqualTo("재시도 한도 도달, 실패 처리");
    }

    @Test
    void worker_restart_cause_carries_its_own_label_and_actor() {
        RecoveryCause cause = RecoveryCause.workerRestart("w9");
        assertThat(cause.actorId()).isEqualTo("worker-restart");
        assertThat(cause.label()).isEqualTo("워커 w9 재기동으로 중단");
        assertThat(cause.requeueImplementation()).isTrue();
        assertThat(TaskRecovery.MAX_RESTART_REQUEUES).isEqualTo(2);
    }

    @Test
    void restart_requeues_implementation_and_counts() {
        Task t = task(5L, TaskStatus.IMPLEMENTING, 0);
        t.setHeadBranch("netis/task-5");
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getRestartRequeueCount()).isZero();

        assertThat(recovery.recover(t, RecoveryCause.workerRestart("w1"))).isEqualTo(TaskStatus.APPROVED);

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getImplementationAttempt()).isEqualTo(2);
        assertThat(t.getRestartRequeueCount()).isEqualTo(1);
        assertThat(t.getHeadBranch()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        TaskStatusHistory h = savedHistory();
        assertThat(h.getActorType()).isEqualTo("system");
        assertThat(h.getActorId()).isEqualTo("worker-restart");
        assertThat(h.getReason()).isEqualTo("워커 w1 재기동으로 중단 → 자동 재큐 (2회째 시도, 재기동 재큐 1/2)");
    }

    @Test
    void restart_fails_implementation_after_two_requeues() {
        Task t = task(6L, TaskStatus.IMPLEMENTING, 0);
        t.setRestartRequeueCount(2);

        assertThat(recovery.recover(t, RecoveryCause.workerRestart("w1"))).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).isEqualTo("워커 w1 재기동으로 중단(자동 재큐 2회 소진)");
        assertThat(t.getRestartRequeueCount()).isEqualTo(2);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getWorkerId()).isNull();
        assertThat(savedHistory().getActorId()).isEqualTo("worker-restart");
    }

    @Test
    void restart_uses_stale_transitions_for_other_states() {
        RecoveryCause cause = RecoveryCause.workerRestart("w1");

        Task design = task(7L, TaskStatus.DESIGNING, 0);
        assertThat(recovery.recover(design, cause)).isEqualTo(TaskStatus.DESIGN_PENDING);
        TaskStatusHistory h1 = savedHistory();
        assertThat(h1.getActorId()).isEqualTo("worker-restart");
        assertThat(h1.getActorType()).isEqualTo("system");
        assertThat(h1.getReason()).isEqualTo("워커 w1 재기동으로 중단 → 디자인대기");

        reset(historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Task close = task(8L, TaskStatus.CLOSING, 0);
        assertThat(recovery.recover(close, cause)).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(savedHistory().getReason()).isEqualTo("워커 w1 재기동으로 중단 → 종료대기");

        reset(historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Task deploy = task(9L, TaskStatus.DEPLOYING, 0);
        assertThat(recovery.recover(deploy, cause)).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(deploy.getFailureReason()).isEqualTo("워커 w1 재기동으로 중단 (배포 중단)");
        TaskStatusHistory h3 = savedHistory();
        assertThat(h3.getActorId()).isEqualTo("worker-restart");
        assertThat(h3.getReason()).isEqualTo("워커 w1 재기동으로 중단 → 배포실패");
    }

    @Test
    void exhausted_label_is_carried_by_the_cause() {
        assertThat(RecoveryCause.stale(WHY).exhaustedLabel()).isEqualTo("Stale 회수 한도 초과: " + WHY);
        assertThat(RecoveryCause.workerRestart("w1").exhaustedLabel())
                .isEqualTo("워커 w1 재기동으로 중단(재시도 한도 초과)");
    }
}
