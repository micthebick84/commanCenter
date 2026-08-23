package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * dead-letter 재전송 잡: SilentLossTracker의 pending 엔트리를 주기적으로 재전송한다.
 *
 * ResultReporter의 재시도가 소진되거나 4xx(예: stale 회수 후 409)로 즉사한 보고는 지금까지
 * dead-letter 파일에 기록만 되고 아무도 다시 보내지 않았다(silent-loss 잔존). 이 잡이 그 재전송 경로.
 *
 *  - 엔트리별 http.postResult를 **1회만 직접 호출** — ResultReporter를 경유하면 실패 시
 *    onLost가 재호출돼 같은 엔트리가 중복 적재되는 재귀가 생기므로 경유하지 않는다.
 *  - 공유 4-스레드 스케줄러(heartbeat/poll과 공유)에서 실행 — 내부 재시도 루프/sleep 금지
 *    (heartbeat 기아 → StaleTaskRecoveryJob 오발동 위험).
 *
 * 서버측 지각 보고 정합화(WorkerService.recordResult)와의 상호작용:
 *  - task가 FAILED/DEPLOY_FAILED/IMPLEMENTATION_FAILED에 머물러 있으면 재전송이
 *    정합화 분기로 수용돼 유실이 복구된다 (지각 COMPLETED/DEPLOYED/PR_CREATED).
 *  - task가 이미 진행(재분석/재배포/삭제)했으면 409 → maxAttempts 소진 후 dead 파일 — 무한 재시도 없음.
 *  - 원 요청이 서버에 적용됐는데 응답만 유실된 경우: 재전송은 409(이미 터미널 상태) → dead로 수렴,
 *    이중 적용 없음.
 */
@Component
@Profile("worker")
@Slf4j
public class DeadLetterReplayJob {

    private final WorkerHttpClient http;
    private final SilentLossTracker tracker;
    private final int maxAttempts;

    // 생성자 여러 개(테스트용 포함) → Spring 주입 생성자 명시(없으면 no-arg 폴백→기동 실패 전례).
    @Autowired
    public DeadLetterReplayJob(WorkerHttpClient http, SilentLossTracker tracker, WorkerProperties props) {
        this(http, tracker, props.replayMaxAttempts());
    }

    DeadLetterReplayJob(WorkerHttpClient http, SilentLossTracker tracker, int maxAttempts) {
        this.http = http;
        this.tracker = tracker;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(initialDelayString = "#{${netis-maker.worker.replay-interval-seconds:60} * 1000}",
               fixedDelayString = "#{${netis-maker.worker.replay-interval-seconds:60} * 1000}")
    public void replay() {
        List<SilentLossTracker.PendingEntry> pending = tracker.snapshotPending();
        if (pending.isEmpty()) return;

        int ok = 0;
        int rejected = 0;
        int deferred = 0;
        for (int i = 0; i < pending.size(); i++) {
            SilentLossTracker.PendingEntry e = pending.get(i);
            try {
                http.postResult(e.taskId(), e.result());
                tracker.resolve(e);
                ok++;
                log.info("dead-letter 재전송 성공 task={} status={}",
                        e.taskId(), e.result().status().dbValue());
            } catch (HttpClientErrorException ex) {
                // 4xx: task가 이미 다른 상태로 진행 — 시도 횟수 소진 시 tracker가 dead 파일로 이동
                tracker.reject(e, maxAttempts);
                rejected++;
            } catch (RestClientException ex) {
                // 연결 실패/5xx: 백엔드 다운 — 사이클 즉시 중단, 남은 엔트리는 다음 사이클
                // (다운된 백엔드에 연타 방지)
                deferred = pending.size() - i;
                log.warn("dead-letter 재전송 사이클 중단 (백엔드 접근 불가, 다음 사이클 재시도): {}",
                        ex.getMessage());
                break;
            }
        }
        log.info("dead-letter 재전송 사이클 완료: 성공 {} / 거부 {} / 보류 {}", ok, rejected, deferred);
    }
}
