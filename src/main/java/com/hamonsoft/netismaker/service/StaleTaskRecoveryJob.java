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

    /** 분석은 보통 1~5분 — 5분 기본 적정 */
    @Value("${app.task.stale-threshold-minutes:5}")
    private int analysisStaleThresholdMinutes;

    /** 구현은 수십분~수시간 — 60분 기본. 5분으로 회수하면 정상 작업도 강제 종료됨 */
    @Value("${app.task.implementation-stale-threshold-minutes:60}")
    private int implementationStaleThresholdMinutes;

    public StaleTaskRecoveryJob(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
    }

    @Scheduled(fixedRateString = "${app.task.stale-check-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        // 단계별 threshold가 다름 (분석 5분 / 구현 60분).
        // 후보를 가장 짧은 쪽으로 뽑은 다음 단계별 threshold로 후필터.
        int tightestMinutes = Math.min(analysisStaleThresholdMinutes, implementationStaleThresholdMinutes);
        OffsetDateTime candidateThreshold = OffsetDateTime.now().minusMinutes(tightestMinutes);
        List<Task> stale = taskRepo.findStaleInProgress(candidateThreshold);
        if (stale.isEmpty()) return;

        OffsetDateTime analysisThreshold = OffsetDateTime.now().minusMinutes(analysisStaleThresholdMinutes);
        OffsetDateTime implementationThreshold = OffsetDateTime.now().minusMinutes(implementationStaleThresholdMinutes);

        for (Task t : stale) {
            TaskStatus from = t.getStatus();
            // 단계별 threshold로 후필터: 후보엔 들었지만 자기 단계 threshold 안 넘었으면 skip
            OffsetDateTime myThreshold = (from == TaskStatus.IMPLEMENTING)
                    ? implementationThreshold : analysisThreshold;
            if (t.getClaimedAt() != null && !t.getClaimedAt().isBefore(myThreshold)) {
                continue;
            }
            String workerId = t.getWorkerId();
            t.setWorkerId(null);
            t.setClaimedAt(null);

            // 구현중 stale은 partial git 변경/PR 위험 있으므로 retry 없이 IMPLEMENTATION_FAILED.
            // 분석중 stale은 기존 정책대로 PENDING 복귀(재시도 한도 내) 또는 FAILED.
            if (from == TaskStatus.IMPLEMENTING) {
                t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                t.setFailureReason("Stale 회수: 워커 " + workerId + " 응답 없음 (구현 중단)");
                t.setUpdatedAt(OffsetDateTime.now());
                historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.IMPLEMENTATION_FAILED,
                        "system", "stale-recovery",
                        "구현중 stale → 구현실패 (partial commit 회피)"));
                log.error("Stale 회수: task={} worker={} → IMPLEMENTATION_FAILED", t.getId(), workerId);
            } else if (t.getRetryCount() < t.getMaxRetry()) {
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
