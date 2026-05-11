package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 *  Stale 작업 회수 잡 (DESIGN P18).
 *
 *  매 1분마다:
 *    분석중 + claimed_at < (now - stale-threshold-minutes) 작업을 →
 *      retry_count < max_retry → PENDING 복귀 + retry_count++
 *      retry_count >= max_retry → FAILED 직접 전이
 *
 *  api 프로파일에서만 활성화 (worker 프로파일은 backend HTTP만 하므로 DB 접근 없음).
 */
@Component
@Profile("api")
@Slf4j
public class StaleTaskRecoveryJob {

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;

    @Value("${app.task.stale-threshold-minutes:5}")
    private int staleThresholdMinutes;

    public StaleTaskRecoveryJob(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
    }

    @Scheduled(fixedRateString = "${app.task.stale-check-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(staleThresholdMinutes);
        List<Task> stale = taskRepo.findStaleInProgress(threshold);
        if (stale.isEmpty()) return;

        for (Task t : stale) {
            TaskStatus from = t.getStatus();
            String workerId = t.getWorkerId();
            t.setWorkerId(null);
            t.setClaimedAt(null);

            if (t.getRetryCount() < t.getMaxRetry()) {
                t.setStatus(TaskStatus.PENDING);
                t.setRetryCount(t.getRetryCount() + 1);
                t.setUpdatedAt(OffsetDateTime.now());
                historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.PENDING,
                        "system", "stale-recovery",
                        "Stale 회수: 워커 " + workerId + " 응답 없음 → 재시도 ("
                                + t.getRetryCount() + "/" + t.getMaxRetry() + ")"));
                log.warn("Stale 회수: task={} worker={} → PENDING (retry {}/{})",
                        t.getId(), workerId, t.getRetryCount(), t.getMaxRetry());
            } else {
                t.setStatus(TaskStatus.FAILED);
                t.setFailureReason("Stale 회수 한도 초과: 워커 " + workerId + " 응답 없음");
                t.setUpdatedAt(OffsetDateTime.now());
                historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.FAILED,
                        "system", "stale-recovery",
                        "재시도 한도 도달, 실패 처리"));
                log.error("Stale 회수 실패: task={} retry_count={} max={} → FAILED",
                        t.getId(), t.getRetryCount(), t.getMaxRetry());
            }
        }
    }
}
