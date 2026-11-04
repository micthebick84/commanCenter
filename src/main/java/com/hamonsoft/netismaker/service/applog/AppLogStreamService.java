package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.config.AppLogProperties;
import com.hamonsoft.netismaker.entity.Task;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 배포된 앱 컨테이너 로그 실시간 보기 (스펙 2026-11-03 §4). 연결 하나에 `docker logs --follow` 프로세스 하나
 * ({@link AppLogStream})를 두고, 판정·동시 상한·시간 상한을 여기서 맡는다. api 단일 인스턴스 전제 — 목록은 in-memory.
 * <p>
 * 거절과 종료는 HTTP 상태가 아니라 200 응답 안의 `end{reason, message}`로 보낸다(EventSource는 상태 코드를 못 읽는다).
 * 동시 상한 초과도 429가 아니라 `end(too-many)`다.
 * <p>
 * 시간 상한은 전용 타이머 스레드(`app-log-timer`)에 스트림마다 한 번 예약한다. `@Scheduled`를 쓰지 않는 이유: API
 * 스케줄러는 스레드가 하나라 새벽 정리 잡이 몇 분씩 붙잡을 수 있다. 타이머는 sink를 부르지 않으므로 막힌 스트림이
 * 다른 스트림의 시간 상한을 세우지 않는다.
 * <p>
 * `@Transactional`을 쓰지 않는다 — OSIV가 꺼져 있고 작업의 envVars는 JSONB 칸이라 이미 로드돼 있다.
 * API가 끝나면 {@link #shutdown}이 모든 프로세스 트리를 끝낸다. 단 라이브 정지(`taskkill /T /F`)는 @PreDestroy를 돌리지
 * 않고, 대신 `/T`가 docker·ssh 자손까지 끝낸다.
 */
@Slf4j
@Service
@Profile("api")
public class AppLogStreamService {

    static final String TIMER_THREAD = "app-log-timer";

    private final AppLogProperties props;
    private final AppLogAvailability availability;
    private final DockerLogsLauncher launcher;
    private final Map<String, String> processEnv;
    private final Duration timeLimit;
    private final ScheduledExecutorService timer;

    /** 열린 스트림 — 이 목록의 크기가 곧 동시 상한 자리 수다. 자기 자신을 잠금으로 쓴다. */
    private final List<AppLogStream> active = new ArrayList<>();

    /** 생성자가 둘이라 @Autowired가 없으면 Spring이 기본 생성자를 찾다 기동에 실패한다(AppLogAvailability 선례). */
    @Autowired
    public AppLogStreamService(AppLogProperties props, AppLogAvailability availability, DockerLogsLauncher launcher) {
        this(props, availability, launcher, System.getenv(), Duration.ofMinutes(props.maxDurationMinutes()));
    }

    /** 테스트용 — 비밀 가리기에 쓸 API 프로세스 env와 시간 상한을 직접 넘긴다. */
    AppLogStreamService(AppLogProperties props, AppLogAvailability availability, DockerLogsLauncher launcher,
                        Map<String, String> processEnv, Duration timeLimit) {
        this.props = props;
        this.availability = availability;
        this.launcher = launcher;
        this.processEnv = processEnv;
        this.timeLimit = timeLimit;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, TIMER_THREAD);
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true); // 정상 종료로 취소된 30분 예약이 닫힌 스트림을 붙잡고 남지 않게
        this.timer = executor;
    }

    /**
     * SSE 구독. emitter 타임아웃은 시간 상한 + 1분이다 — 상한(`end(time-limit)`)이 먼저 오고, 0(무제한)이 남는 함정을 피한다.
     * 완료·타임아웃·오류 콜백은 모두 정리로 이어진다(이미 닫혔으면 아무 일 없음).
     */
    public SseEmitter subscribe(Task task) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(props.maxDurationMinutes() + 1L).toMillis());
        AppLogStream stream = open(task, new SseEmitterSink(emitter));
        emitter.onCompletion(stream::clientGone);
        emitter.onTimeout(stream::clientGone);
        emitter.onError(e -> stream.clientGone());
        return emitter;
    }

    /**
     * 스트림을 연다. 거절이면 프로세스 없이 `end` + complete로 끝낸, 이미 닫힌 핸들을 돌려준다.
     * 순서: 기능 켜짐 → 허용 상태 → 동시 상한 자리 → 접속 핑 → docker 실행 → 읽기·전송 스레드와 시간 상한.
     */
    AppLogStream open(Task task, AppLogSink sink) {
        long taskId = task.getId();
        AppLogStream stream = new AppLogStream(taskId, sink, props, this::release);

        Optional<Path> docker = availability.docker();
        if (!availability.active() || docker.isEmpty()) {
            stream.close(AppLogEndReason.DISABLED);
            return stream;
        }
        if (!AppLogPolicy.isViewable(task)) {
            stream.close(AppLogEndReason.NOT_ALLOWED_STATUS);
            return stream;
        }
        if (!reserve(stream)) {
            stream.close(AppLogEndReason.TOO_MANY);
            return stream;
        }
        try {
            sink.comment(AppLogStream.PING); // 요청 스레드 — 아직 다른 스레드가 sink를 쓰지 않는다
        } catch (IOException | RuntimeException e) {
            stream.close(null);
            return stream;
        }
        Process process;
        try {
            process = launcher.start(command(docker.get(), props.tailLines(), taskId));
        } catch (IOException | RuntimeException e) {
            log.warn("앱 로그: docker logs 실행 실패 task={}: {}", taskId, e.toString());
            stream.close(AppLogEndReason.DOCKER_ERROR);
            return stream;
        }
        stream.start(process, SecretMasker.of(task.getEnvVars(), processEnv), timer, timeLimit);
        log.info("앱 로그 스트림 시작 task={} (열린 스트림 {}개)", taskId, activeCount());
        return stream;
    }

    /** 열린 스트림 수(전체). */
    int activeCount() {
        synchronized (active) {
            return active.size();
        }
    }

    /** 이 작업의 열린 스트림 수. */
    int activeCount(long taskId) {
        synchronized (active) {
            return countFor(taskId);
        }
    }

    /** API 종료 — 모든 스트림의 프로세스 트리를 끝내고(`end` 없이) 타이머를 내린다. */
    @PreDestroy
    public void shutdown() {
        List<AppLogStream> all;
        synchronized (active) {
            all = List.copyOf(active);
        }
        all.forEach(s -> s.close(null));
        timer.shutdownNow();
    }

    /** `[docker, logs, --tail, N, --timestamps, --follow, netis-task-{id}]`. */
    static List<String> command(Path docker, int tailLines, long taskId) {
        return List.of(docker.toString(), "logs", "--tail", Integer.toString(tailLines), "--timestamps", "--follow",
                AppLogPolicy.containerName(taskId));
    }

    /** 한 잠금 안에서 전체 < maxStreams이고 작업당 < maxStreamsPerTask일 때만 자리를 잡는다. */
    private boolean reserve(AppLogStream stream) {
        synchronized (active) {
            if (active.size() >= props.maxStreams() || countFor(stream.taskId()) >= props.maxStreamsPerTask()) {
                return false;
            }
            active.add(stream);
            return true;
        }
    }

    /** 스트림이 닫힐 때 한 번 부른다 — 자리를 잡은 적 없는 스트림(거절)이면 아무 일 없다. */
    private void release(AppLogStream stream) {
        synchronized (active) {
            active.remove(stream);
        }
    }

    private int countFor(long taskId) {
        int n = 0;
        for (AppLogStream s : active) {
            if (s.taskId() == taskId) n++;
        }
        return n;
    }
}
