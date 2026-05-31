package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
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
 *  매 stale-check-interval-ms마다 in-flight(분석중/구현중/배포중/배포중지중) 작업을 검사한다.
 *  회수 조건(둘 중 하나):
 *    1. 워커 사망: claimed_at이 worker-dead-threshold-seconds보다 오래됐고(=워커가 등록할 시간 충분),
 *       소유 워커의 heartbeat.last_seen_at이 임계 이전이거나 heartbeat가 아예 없음.
 *    2. 행업 백스톱: claimed_at이 단계별 절대 임계(분석/구현/배포)를 넘김 — 워커가 살아있어도 회수.
 *
 *  회수 전이: 분석중→PENDING(재시도)/FAILED, 구현중→IMPLEMENTATION_FAILED,
 *            배포중→DEPLOY_FAILED, 배포중지중→UNDEPLOY_PENDING(idempotent 재큐잉).
 */
@Component
@Profile("api")
@Slf4j
public class StaleTaskRecoveryJob {

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;

    @Value("${app.task.stale-threshold-minutes:5}")
    private int analysisStaleThresholdMinutes;

    @Value("${app.task.implementation-stale-threshold-minutes:60}")
    private int implementationStaleThresholdMinutes;

    @Value("${app.task.deploy-stale-threshold-minutes:15}")
    private int deployStaleThresholdMinutes;

    @Value("${app.task.worker-dead-threshold-seconds:60}")
    private int workerDeadThresholdSeconds;

    public StaleTaskRecoveryJob(TaskRepository taskRepo,
                                TaskStatusHistoryRepository historyRepo,
                                WorkerHeartbeatRepository heartbeatRepo,
                                DeployLogStreamService deployLogStream) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
    }

    @Scheduled(fixedRateString = "${app.task.stale-check-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        List<Task> inflight = taskRepo.findInFlightClaimed();
        if (inflight.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime workerDeadBefore = now.minusSeconds(workerDeadThresholdSeconds);

        Set<String> workerIds = inflight.stream()
                .map(Task::getWorkerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, OffsetDateTime> lastSeen = heartbeatRepo.findAllById(workerIds).stream()
                .collect(Collectors.toMap(WorkerHeartbeat::getWorkerId, WorkerHeartbeat::getLastSeenAt));

        for (Task t : inflight) {
            TaskStatus from = t.getStatus();
            String workerId = t.getWorkerId();
            OffsetDateTime claimedAt = t.getClaimedAt();

            // 갓 claim한 작업은 워커가 첫 heartbeat 보낼 시간을 준다 (오탐 방지).
            boolean claimedLongEnough = claimedAt != null && claimedAt.isBefore(workerDeadBefore);
            OffsetDateTime hb = workerId == null ? null : lastSeen.get(workerId);
            boolean workerDead = claimedLongEnough && (hb == null || hb.isBefore(workerDeadBefore));

            int ceilingMin = switch (from) {
                case IMPLEMENTING -> implementationStaleThresholdMinutes;
                case DEPLOYING, UNDEPLOYING -> deployStaleThresholdMinutes;
                default -> analysisStaleThresholdMinutes; // IN_PROGRESS
            };
            boolean hungBackstop = claimedAt != null && claimedAt.isBefore(now.minusMinutes(ceilingMin));

            if (!workerDead && !hungBackstop) continue;

            t.setWorkerId(null);
            t.setClaimedAt(null);
            String why = workerDead ? "워커 " + workerId + " 응답 없음(heartbeat)" : "처리 시간 초과(" + ceilingMin + "분)";

            switch (from) {
                case IMPLEMENTING -> {
                    t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                    t.setFailureReason("Stale 회수: " + why + " (구현 중단)");
                    logTransition(t, from, TaskStatus.IMPLEMENTATION_FAILED, "구현중 stale → 구현실패");
                    log.error("Stale 회수: task={} {} → IMPLEMENTATION_FAILED", t.getId(), why);
                }
                case DEPLOYING -> {
                    t.setStatus(TaskStatus.DEPLOY_FAILED);
                    t.setFailureReason("Stale 회수: " + why + " (배포 중단)");
                    deployLogStream.consolidate(t.getId()).ifPresent(t::setDeployLog); // 부분 로그 보존
                    deployLogStream.finish(t.getId());
                    logTransition(t, from, TaskStatus.DEPLOY_FAILED, "배포중 stale → 배포실패");
                    log.error("Stale 회수: task={} {} → DEPLOY_FAILED", t.getId(), why);
                }
                case UNDEPLOYING -> {
                    t.setStatus(TaskStatus.UNDEPLOY_PENDING);
                    deployLogStream.finish(t.getId()); // 진행중 청크 정리(재큐잉되면 새로 스트리밍)
                    logTransition(t, from, TaskStatus.UNDEPLOY_PENDING, "배포중지중 stale → 재큐잉(idempotent)");
                    log.warn("Stale 회수: task={} {} → UNDEPLOY_PENDING(재큐잉)", t.getId(), why);
                }
                default -> { // IN_PROGRESS
                    if (t.getRetryCount() < t.getMaxRetry()) {
                        t.setStatus(TaskStatus.PENDING);
                        t.setRetryCount(t.getRetryCount() + 1);
                        logTransition(t, from, TaskStatus.PENDING,
                                "Stale 회수: " + why + " → 재시도 (" + t.getRetryCount() + "/" + t.getMaxRetry() + ")");
                        log.warn("Stale 회수: task={} → PENDING (retry {}/{})", t.getId(), t.getRetryCount(), t.getMaxRetry());
                    } else {
                        t.setStatus(TaskStatus.FAILED);
                        t.setFailureReason("Stale 회수 한도 초과: " + why);
                        logTransition(t, from, TaskStatus.FAILED, "재시도 한도 도달, 실패 처리");
                        log.error("Stale 회수 실패: task={} retry={} max={} → FAILED", t.getId(), t.getRetryCount(), t.getMaxRetry());
                    }
                }
            }
        }
    }

    private void logTransition(Task t, TaskStatus from, TaskStatus to, String reason) {
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", "stale-recovery", reason));
    }
}
