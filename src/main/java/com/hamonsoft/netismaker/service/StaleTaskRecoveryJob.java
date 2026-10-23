package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import com.hamonsoft.netismaker.service.TaskRecovery.RecoveryCause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *  Stale 작업 회수 잡 (api 프로파일).
 *
 *  매 stale-check-interval-ms마다 in-flight(분석중/디자인중/구현중/배포중/배포중지중/종료중) 작업을 검사한다.
 *  회수 조건(둘 중 하나):
 *    1. 워커 사망: claimed_at이 worker-dead-threshold-seconds보다 오래됐고(=워커가 등록할 시간 충분),
 *       소유 워커의 heartbeat.last_seen_at이 임계 이전이거나 heartbeat가 아예 없음.
 *    2. 행업 백스톱: claimed_at이 단계별 절대 임계(분석/구현/디자인/배포/종료)를 넘김 — 워커가 살아있어도 회수.
 *
 *  회수 전이(TaskRecovery): 분석중→PENDING(재시도)/FAILED, 디자인중→DESIGN_PENDING(idempotent 재큐잉), 구현중→IMPLEMENTATION_FAILED,
 *            배포중→DEPLOY_FAILED, 배포중지중→UNDEPLOY_PENDING(idempotent 재큐잉),
 *            종료중→CLOSE_PENDING(재큐잉 — close_progress가 남아 있어 재claim이 남은 단계부터 이어간다).
 */
@Component
@Profile("api")
@Slf4j
public class StaleTaskRecoveryJob {

    private final TaskRepository taskRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final TaskRecovery taskRecovery;

    @Value("${app.task.stale-threshold-minutes:5}")
    private int analysisStaleThresholdMinutes;

    @Value("${app.task.deploy-stale-threshold-minutes:15}")
    private int deployStaleThresholdMinutes;

    @Value("${app.task.worker-dead-threshold-seconds:60}")
    private int workerDeadThresholdSeconds;

    @Value("${app.task.implementation-worker-dead-threshold-seconds:300}")
    private int implementationWorkerDeadThresholdSeconds;

    @Value("${app.task.deploy-worker-dead-threshold-seconds:180}")
    private int deployWorkerDeadThresholdSeconds;

    @Value("${app.task.design-stale-threshold-minutes:30}")
    private int designStaleThresholdMinutes;

    @Value("${app.task.design-worker-dead-threshold-seconds:300}")
    private int designWorkerDeadThresholdSeconds;

    @Value("${app.task.close-stale-threshold-minutes:60}")
    private int closeStaleThresholdMinutes;

    private final ImplementationModePolicy implementationModePolicy;

    public StaleTaskRecoveryJob(TaskRepository taskRepo,
                                WorkerHeartbeatRepository heartbeatRepo,
                                TaskRecovery taskRecovery,
                                ImplementationModePolicy implementationModePolicy) {
        this.taskRepo = taskRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.taskRecovery = taskRecovery;
        this.implementationModePolicy = implementationModePolicy;
    }

    @Scheduled(fixedRateString = "${app.task.stale-check-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        List<Task> inflight = taskRepo.findInFlightClaimedForUpdateSkipLocked();
        if (inflight.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();

        Set<String> workerIds = inflight.stream()
                .map(Task::getWorkerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, OffsetDateTime> lastSeen = heartbeatRepo.findAllById(workerIds).stream()
                .collect(Collectors.toMap(WorkerHeartbeat::getWorkerId, WorkerHeartbeat::getLastSeenAt));

        for (Task t : inflight) {
            TaskStatus from = t.getStatus();
            String workerId = t.getWorkerId();
            OffsetDateTime claimedAt = t.getClaimedAt();

            // 단계별 worker-dead 임계 — 긴 구현/배포 중 짧은 heartbeat 블립으로 인한 오탐 회수 방지.
            int deadSec = switch (from) {
                case IMPLEMENTING -> implementationWorkerDeadThresholdSeconds;
                case DESIGNING -> designWorkerDeadThresholdSeconds;
                case DEPLOYING, UNDEPLOYING, CLOSING -> deployWorkerDeadThresholdSeconds;
                default -> workerDeadThresholdSeconds; // IN_PROGRESS (분석)
            };
            OffsetDateTime workerDeadBefore = now.minusSeconds(deadSec);
            // 갓 claim한 작업은 워커가 첫 heartbeat 보낼 시간을 준다 (오탐 방지).
            boolean claimedLongEnough = claimedAt != null && claimedAt.isBefore(workerDeadBefore);
            OffsetDateTime hb = workerId == null ? null : lastSeen.get(workerId);
            boolean workerDead = claimedLongEnough && (hb == null || hb.isBefore(workerDeadBefore));

            int ceilingMin = switch (from) {
                // 방식별 판정 — SDD는 몇 시간 돈다 (스펙 2026-10-15 §5.4)
                case IMPLEMENTING -> implementationModePolicy.staleThresholdMinutes(t.getImplementationMode());
                case DESIGNING -> designStaleThresholdMinutes;
                case CLOSING -> closeStaleThresholdMinutes;
                case DEPLOYING, UNDEPLOYING -> deployStaleThresholdMinutes;
                default -> analysisStaleThresholdMinutes; // IN_PROGRESS
            };
            boolean hungBackstop = claimedAt != null && claimedAt.isBefore(now.minusMinutes(ceilingMin));

            if (!workerDead && !hungBackstop) continue;

            String why = workerDead ? "워커 " + workerId + " 응답 없음(heartbeat)" : "처리 시간 초과(" + ceilingMin + "분)";
            taskRecovery.recover(t, RecoveryCause.stale(why));
        }
    }
}
