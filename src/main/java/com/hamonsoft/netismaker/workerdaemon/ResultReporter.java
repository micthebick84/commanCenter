package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 *  워커 결과 보고기 — 재시도 + 성공보고 비전파 + 유실 신호.
 *
 *  - 5xx/연결오류(transient): 지수 백오프로 재시도.
 *  - 4xx(HttpClientErrorException): 영구 오류 → 재시도 무의미, 즉시 포기.
 *  - 최종 실패해도 예외를 던지지 않는다(false 반환). 성공 보고의 일시적 백엔드 장애가
 *    상위 catch에서 실패 보고로 뒤집히는 것을 방지(task16 false-failure 재발방지).
 *  - 최종 실패 시 lossListener.onLost(...) 1회 호출(유실 카운트/dead-letter용). 리스너 예외도 삼킴.
 */
@Component
@Profile("worker")
@Slf4j
public class ResultReporter {

    /** 단일 시도 보고. 실패 시 RestClientException 전파. */
    @FunctionalInterface
    public interface Poster { void post(Long taskId, WorkerResultRequest req); }

    /** 테스트 주입용 sleep 추상화. */
    @FunctionalInterface
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    /** 결과 보고 최종 실패(유실) 리스너. permanent=true면 4xx/직렬화 등 영구, false면 transient 소진. */
    @FunctionalInterface
    public interface LostReportListener {
        void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason);
    }

    private static final LostReportListener NOOP = (t, r, p, why) -> { };

    private final Poster poster;
    private final int maxRetries;
    private final long backoffMs;
    private final Sleeper sleeper;
    private final LostReportListener lossListener;

    // 생성자가 여러 개(테스트용 포함)이므로 Spring이 주입에 쓸 생성자를 명시. 없으면 no-arg 폴백→기동 실패.
    @Autowired
    public ResultReporter(WorkerHttpClient http, WorkerProperties props, SilentLossTracker tracker) {
        this(http::postResult, props.resultReportMaxRetries(), props.resultReportBackoffMs(), Thread::sleep, tracker);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper) {
        this(poster, maxRetries, backoffMs, sleeper, NOOP);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper, LostReportListener lossListener) {
        this.poster = poster;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
        this.sleeper = sleeper;
        this.lossListener = lossListener == null ? NOOP : lossListener;
    }

    /** 결과 보고. 성공 true, 최종 실패 false. 절대 throw 안 함. */
    public boolean reportTerminal(Long taskId, WorkerResultRequest req) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                poster.post(taskId, req);
                return true;
            } catch (HttpClientErrorException e) {
                return fail(taskId, req, true, "4xx " + e.getStatusCode());
            } catch (RestClientException e) {
                last = e;
                log.warn("결과 보고 실패 (시도 {}/{}) task={} status={}: {}",
                        attempt + 1, maxRetries + 1, taskId, req.status().dbValue(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        sleeper.sleep(backoffMs * (1L << attempt)); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return fail(taskId, req, false, "보고 대기 중 인터럽트");
                    }
                }
            } catch (Exception e) {
                // RestClientException 외 예기치 못한 예외(NPE/직렬화 등)는 transient가 아님 → 재시도 무의미.
                return fail(taskId, req, true, "예기치 못한 예외 " + e.getClass().getSimpleName());
            }
        }
        return fail(taskId, req, false, "재시도 소진(" + (maxRetries + 1) + "회)"
                + (last == null ? "" : " " + last.getMessage()));
    }

    /** 최종 실패 처리: ERROR 로그 + 유실 리스너 1회 호출. 절대 throw 안 함. false 반환. */
    private boolean fail(Long taskId, WorkerResultRequest req, boolean permanent, String reason) {
        log.error("결과 보고 최종 실패(SILENT_LOSS) — task={} status={} permanent={} reason={}",
                taskId, req.status().dbValue(), permanent, reason);
        try {
            lossListener.onLost(taskId, req, permanent, reason);
        } catch (Exception e) {
            log.error("silent-loss 리스너 처리 실패 task={} (무시)", taskId, e);
        }
        return false;
    }
}
