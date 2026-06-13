package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 인터뷰 stale 회수 + idle 만료 잡 (api 프로파일). StaleTaskRecoveryJob 미러.
 *
 *  매 stale-check-interval-ms마다:
 *    1) RUNNING + claimed_at이 stale-running-minutes 초과 → InterviewService.fail (워커 사망/행업 백스톱)
 *    2) AWAITING_INPUT + last_activity_at이 idle-ttl-minutes 초과 → InterviewService.expire (사람 미복귀)
 *
 *  전이는 전부 Phase 1 InterviewService에 위임. 각 세션을 독립 try/catch로 처리해
 *  한 세션 오류가 전체 sweep을 중단하지 않게 한다.
 */
@Component
@Profile("api")
@Slf4j
public class InterviewStaleRecoveryJob {

    private final InterviewSessionRepository sessionRepo;
    private final InterviewService interviewService;
    private final InterviewStreamService stream;

    @Value("${app.interview.idle-ttl-minutes:1440}")
    private int idleTtlMinutes;

    @Value("${app.interview.stale-running-minutes:60}")
    private int staleRunningMinutes;

    @Value("${app.task.worker-dead-threshold-seconds:60}")
    private int workerDeadThresholdSeconds;

    public InterviewStaleRecoveryJob(InterviewSessionRepository sessionRepo,
                                     InterviewService interviewService,
                                     InterviewStreamService stream) {
        this.sessionRepo = sessionRepo;
        this.interviewService = interviewService;
        this.stream = stream;
    }

    @Scheduled(fixedRateString = "${app.interview.stale-check-interval-ms:60000}")
    public void recover() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1) RUNNING stale → FAILED. claimed_at이 staleRunningMinutes 초과(워커 첫 heartbeat 시간 보장은 cutoff에 내포).
        OffsetDateTime runningCutoff = now.minusMinutes(staleRunningMinutes);
        for (InterviewSession s : sessionRepo.findStaleRunning(runningCutoff)) {
            Long id = s.getId();
            try {
                interviewService.fail(id, "stale-recovery",
                        "처리 시간 초과(" + staleRunningMinutes + "분) — 워커 사망/행업 회수");
                stream.pushStatus(id, InterviewStatus.FAILED);
                stream.finish(id);
                log.warn("Stale 회수: interview={} RUNNING → FAILED", id);
            } catch (Exception e) {
                log.error("Stale 회수 실패: interview={}", id, e);
            }
        }

        // 2) AWAITING_INPUT idle → EXPIRED.
        OffsetDateTime idleCutoff = now.minusMinutes(idleTtlMinutes);
        for (InterviewSession s : sessionRepo.findIdleAwaitingInput(idleCutoff)) {
            Long id = s.getId();
            try {
                interviewService.expire(id, "idle TTL 초과(" + idleTtlMinutes + "분)");
                stream.pushStatus(id, InterviewStatus.EXPIRED);
                stream.finish(id);
                log.warn("Idle 만료: interview={} AWAITING_INPUT → EXPIRED", id);
            } catch (Exception e) {
                log.error("Idle 만료 실패: interview={}", id, e);
            }
        }
    }
}
