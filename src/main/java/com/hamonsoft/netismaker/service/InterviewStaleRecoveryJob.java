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
 *    1a) RUNNING + claimed_at이 stale-running-minutes 초과 → fail (SDK 턴 wall-clock 절대 백스톱.
 *        claimed_at은 claim에서만 기록되고 heartbeat는 갱신하지 않으므로, 워커가 살아서
 *        heartbeat를 계속 보내는 행업도 여기서 회수된다)
 *    1b) RUNNING + last_activity_at이 running-dead-seconds 초과 → fail (heartbeat 두절 = 워커 사망)
 *    2)  AWAITING_INPUT + last_activity_at이 idle-ttl-minutes 초과 → expire (사람 미복귀)
 *    3)  QUEUED + last_activity_at이 queued-ttl-minutes 초과 → expire (인터뷰 서비스 미가동)
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

    /** RUNNING 세션의 heartbeat 두절(워커 사망) 판정 임계 — heartbeat 주기(15s)의 넉넉한 배수. */
    @Value("${app.interview.running-dead-seconds:180}")
    private int runningDeadSeconds;

    /** QUEUED 체류 한도 — 초과 시 인터뷰 서비스 미가동으로 보고 만료시킨다. */
    @Value("${app.interview.queued-ttl-minutes:60}")
    private long queuedTtlMinutes;

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

        // 1a) RUNNING wall-clock 백스톱 → FAILED. claimed_at(턴 시작)이 staleRunningMinutes 초과.
        //     heartbeat가 claimed_at을 갱신하지 않으므로 살아있는 워커의 SDK 행업도 회수된다.
        java.util.Set<Long> recovered = new java.util.HashSet<>();
        OffsetDateTime runningCutoff = now.minusMinutes(staleRunningMinutes);
        for (InterviewSession s : sessionRepo.findStaleRunning(runningCutoff)) {
            Long id = s.getId();
            recovered.add(id);
            try {
                interviewService.fail(id, "stale-recovery",
                        "처리 시간 초과(" + staleRunningMinutes + "분) — SDK 턴 wall-clock 백스톱 회수");
                stream.pushStatus(id, InterviewStatus.FAILED);
                stream.finish(id);
                log.warn("Stale 회수: interview={} RUNNING → FAILED (wall-clock)", id);
            } catch (Exception e) {
                log.error("Stale 회수 실패: interview={}", id, e);
            }
        }

        // 1b) RUNNING heartbeat 두절 → FAILED. last_activity_at(heartbeat가 갱신)이 runningDeadSeconds 초과.
        //     1a에서 이미 처리한 세션은 스킵(두 후보 조회가 겹칠 수 있다).
        OffsetDateTime deadCutoff = now.minusSeconds(runningDeadSeconds);
        for (InterviewSession s : sessionRepo.findDeadRunning(deadCutoff)) {
            Long id = s.getId();
            if (!recovered.add(id)) continue;
            try {
                interviewService.fail(id, "stale-recovery",
                        "heartbeat 두절(" + runningDeadSeconds + "초) — 워커 사망 회수");
                stream.pushStatus(id, InterviewStatus.FAILED);
                stream.finish(id);
                log.warn("Stale 회수: interview={} RUNNING → FAILED (heartbeat 두절)", id);
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

        // 3) QUEUED 체류 TTL → EXPIRED. 인터뷰 서비스가 죽어 있으면 세션이 QUEUED에
        //    무기한 체류하고 task가 인터뷰중에 고착 + 사용자 동시 한도 슬롯 점유 — 여기서 회수.
        OffsetDateTime queuedCutoff = now.minusMinutes(queuedTtlMinutes);
        for (InterviewSession s : sessionRepo.findStaleQueued(queuedCutoff)) {
            Long id = s.getId();
            try {
                interviewService.expire(id, "인터뷰대기 " + queuedTtlMinutes + "분 초과(인터뷰 서비스 미처리)");
                stream.pushStatus(id, InterviewStatus.EXPIRED);
                stream.finish(id);
                log.warn("QUEUED 만료: interview={} QUEUED → EXPIRED", id);
            } catch (Exception e) {
                // 스윕 조회와 전이 사이에 claim이 선점하면 expire가 conflict — 정상 경합, 다음 틱에 재판정.
                log.warn("QUEUED 만료 실패(경합 가능): interview={} — {}", id, e.getMessage());
            }
        }
    }
}
