package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 *  in-flight 작업 1건의 회수 전이 (api 프로파일).
 *
 *  StaleTaskRecoveryJob(워커 사망·행업)과 워커 재기동 정합(WorkerStartupService)이 같은 전이를 쓰도록 분리했다.
 *  호출자가 트랜잭션과 행 잠금을 쥔다 — 여기서는 엔티티를 바꾸고 이력만 남긴다.
 *
 *  회수 전이: 분석중→PENDING(재시도)/FAILED, 디자인중→DESIGN_PENDING(idempotent 재큐잉), 구현중→IMPLEMENTATION_FAILED
 *            (워커 재기동이면 상한 안에서 APPROVED로 자동 재큐),
 *            배포중→DEPLOY_FAILED, 배포중지중→UNDEPLOY_PENDING(idempotent 재큐잉),
 *            종료중→CLOSE_PENDING(재큐잉 — close_progress가 남아 있어 재claim이 남은 단계부터 이어간다).
 */
@Component
@Profile("api")
@Slf4j
public class TaskRecovery {

    /** 워커 재기동으로 중단된 구현을 자동 재큐할 수 있는 상한(작업당). 3번째 중단은 실패로 둔다. */
    static final int MAX_RESTART_REQUEUES = 2;

    /**
     * 회수 원인. actorId는 이력 actor_id, label은 이력·실패 사유에 들어가는 문구.
     * requeueImplementation은 구현중 작업을 실패 대신 재큐할지(워커 재기동 경로 — 이때 이력 사유도 label 기반).
     * exhaustedLabel은 분석 재시도 한도 소진 시 실패 사유.
     */
    public record RecoveryCause(String actorId, String label, boolean requeueImplementation, String exhaustedLabel) {

        public static RecoveryCause stale(String why) {
            return new RecoveryCause("stale-recovery", "Stale 회수: " + why, false, "Stale 회수 한도 초과: " + why);
        }

        public static RecoveryCause workerRestart(String workerId) {
            return new RecoveryCause("worker-restart", "워커 " + workerId + " 재기동으로 중단", true,
                    "워커 " + workerId + " 재기동으로 중단(재시도 한도 초과)");
        }
    }

    private final TaskStatusHistoryRepository historyRepo;
    private final DeployLogStreamService deployLogStream;

    public TaskRecovery(TaskStatusHistoryRepository historyRepo, DeployLogStreamService deployLogStream) {
        this.historyRepo = historyRepo;
        this.deployLogStream = deployLogStream;
    }

    /** 회수 전이를 적용하고 전이 후 상태를 돌려준다. 호출자가 트랜잭션과 행 잠금을 쥔다. */
    public TaskStatus recover(Task t, RecoveryCause cause) {
        TaskStatus from = t.getStatus();
        t.setWorkerId(null);
        t.setClaimedAt(null);
        String label = cause.label();

        switch (from) {
            case IMPLEMENTING -> {
                if (!cause.requeueImplementation()) {
                    t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                    t.setFailureReason(label + " (구현 중단)");
                    logTransition(t, from, TaskStatus.IMPLEMENTATION_FAILED, cause, "구현중 stale → 구현실패");
                    log.error("{}: task={} → IMPLEMENTATION_FAILED", label, t.getId());
                } else if (t.getRestartRequeueCount() < MAX_RESTART_REQUEUES) {
                    // 워커 재기동: 처음부터 다시 구현(시도 번호 +1). 상한 안에서만 자동으로.
                    t.prepareImplementationRequeue();
                    t.setRestartRequeueCount(t.getRestartRequeueCount() + 1);
                    logTransition(t, from, TaskStatus.APPROVED, cause,
                            label + " → 자동 재큐 (" + t.getImplementationAttempt() + "회째 시도, 재기동 재큐 "
                                    + t.getRestartRequeueCount() + "/" + MAX_RESTART_REQUEUES + ")");
                    log.warn("{}: task={} → APPROVED(자동 재큐 {}/{})", label, t.getId(),
                            t.getRestartRequeueCount(), MAX_RESTART_REQUEUES);
                } else {
                    String reason = label + "(자동 재큐 " + MAX_RESTART_REQUEUES + "회 소진)";
                    t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                    t.setFailureReason(reason);
                    logTransition(t, from, TaskStatus.IMPLEMENTATION_FAILED, cause, reason);
                    log.error("{}: task={} → IMPLEMENTATION_FAILED(재큐 상한 소진)", label, t.getId());
                }
            }
            case DEPLOYING -> {
                t.setStatus(TaskStatus.DEPLOY_FAILED);
                t.setFailureReason(label + " (배포 중단)");
                deployLogStream.consolidate(t.getId()).ifPresent(t::setDeployLog); // 부분 로그 보존
                deployLogStream.finish(t.getId());
                logTransition(t, from, TaskStatus.DEPLOY_FAILED, cause,
                        reason(cause, TaskStatus.DEPLOY_FAILED, "배포중 stale → 배포실패"));
                log.error("{}: task={} → DEPLOY_FAILED", label, t.getId());
            }
            case UNDEPLOYING -> {
                t.setStatus(TaskStatus.UNDEPLOY_PENDING);
                deployLogStream.finish(t.getId()); // 진행중 청크 정리(재큐잉되면 새로 스트리밍)
                logTransition(t, from, TaskStatus.UNDEPLOY_PENDING, cause,
                        reason(cause, TaskStatus.UNDEPLOY_PENDING, "배포중지중 stale → 재큐잉(idempotent)"));
                log.warn("{}: task={} → UNDEPLOY_PENDING(재큐잉)", label, t.getId());
            }
            case CLOSING -> {
                // 단계가 멱등이고 진행(close_progress)이 남아 있어 실패로 돌리지 않고 재큐잉한다.
                t.setStatus(TaskStatus.CLOSE_PENDING);
                logTransition(t, from, TaskStatus.CLOSE_PENDING, cause,
                        reason(cause, TaskStatus.CLOSE_PENDING, "종료중 stale → 재큐잉(idempotent, 남은 단계부터)"));
                log.warn("{}: task={} → CLOSE_PENDING(재큐잉)", label, t.getId());
            }
            case DESIGNING -> {
                t.setStatus(TaskStatus.DESIGN_PENDING);
                logTransition(t, from, TaskStatus.DESIGN_PENDING, cause,
                        reason(cause, TaskStatus.DESIGN_PENDING, "디자인중 stale → 재큐잉(idempotent)"));
                log.warn("{}: task={} → DESIGN_PENDING(재큐잉)", label, t.getId());
            }
            default -> { // IN_PROGRESS
                if (t.getRetryCount() < t.getMaxRetry()) {
                    t.setStatus(TaskStatus.PENDING);
                    t.setRetryCount(t.getRetryCount() + 1);
                    logTransition(t, from, TaskStatus.PENDING, cause, reason(cause, TaskStatus.PENDING,
                            label + " → 재시도 (" + t.getRetryCount() + "/" + t.getMaxRetry() + ")"));
                    log.warn("{}: task={} → PENDING (retry {}/{})", label, t.getId(), t.getRetryCount(), t.getMaxRetry());
                } else {
                    t.setStatus(TaskStatus.FAILED);
                    t.setFailureReason(cause.exhaustedLabel());
                    logTransition(t, from, TaskStatus.FAILED, cause,
                            reason(cause, TaskStatus.FAILED, "재시도 한도 도달, 실패 처리"));
                    log.error("{}: task={} retry={} max={} → FAILED", label, t.getId(), t.getRetryCount(), t.getMaxRetry());
                }
            }
        }
        return t.getStatus();
    }

    /** 이력 사유 — stale 원인은 기존 고정 문구, 워커 재기동 원인은 "{label} → {전이 후 상태}". */
    private static String reason(RecoveryCause cause, TaskStatus to, String staleReason) {
        return cause.requeueImplementation() ? cause.label() + " → " + to.dbValue() : staleReason;
    }

    private void logTransition(Task t, TaskStatus from, TaskStatus to, RecoveryCause cause, String reason) {
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", cause.actorId(), reason));
    }
}
