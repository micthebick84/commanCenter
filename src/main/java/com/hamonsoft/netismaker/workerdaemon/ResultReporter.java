package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 *  워커 결과 보고기 — 재시도 + 성공보고 비전파.
 *
 *  - 5xx/연결오류(transient): 지수 백오프로 재시도.
 *  - 4xx(HttpClientErrorException): 영구 오류 → 재시도 무의미, 즉시 포기.
 *  - 최종 실패해도 예외를 던지지 않는다(false 반환). 성공 보고의 일시적 백엔드 장애가
 *    상위 catch에서 실패 보고로 뒤집히는 것을 방지(task16 false-failure 재발방지).
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

    private final Poster poster;
    private final int maxRetries;
    private final long backoffMs;
    private final Sleeper sleeper;

    public ResultReporter(WorkerHttpClient http, WorkerProperties props) {
        this(http::postResult, props.resultReportMaxRetries(), props.resultReportBackoffMs(), Thread::sleep);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper) {
        this.poster = poster;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
        this.sleeper = sleeper;
    }

    /** 결과 보고. 성공 true, 최종 실패 false. 절대 throw 안 함. */
    public boolean reportTerminal(Long taskId, WorkerResultRequest req) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                poster.post(taskId, req);
                return true;
            } catch (RestClientException e) {
                last = e;
                if (e instanceof HttpClientErrorException) {
                    log.error("결과 보고 영구 실패(4xx) — task={} status={} {}", taskId, req.status(), e.getMessage());
                    return false; // 4xx 재시도 무의미
                }
                log.warn("결과 보고 실패 (시도 {}/{}) task={} status={}: {}",
                        attempt + 1, maxRetries + 1, taskId, req.status(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        sleeper.sleep(backoffMs * (1L << attempt)); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                // RestClientException 외 예기치 못한 예외(NPE/직렬화 등)는 transient가 아님 → 재시도 무의미.
                // 절대 throw 안 함 보장 유지: 로그만 남기고 false 반환.
                log.error("결과 보고 예기치 못한 예외 — task={} status={} (포기, rethrow 안 함)",
                        taskId, req.status(), e);
                return false;
            }
        }
        log.error("결과 보고 최종 실패 — task={} status={} (포기, rethrow 안 함)", taskId, req.status(), last);
        return false;
    }
}
