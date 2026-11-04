package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.config.AppLogProperties;
import com.hamonsoft.netismaker.util.ProcessTrees;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 앱 로그 연결 하나 — `docker logs --follow` 프로세스 하나와 그 출력을 SSE로 보내는 스레드 셋 (스펙 2026-11-03 §4).
 * <ul>
 *   <li>읽기 스레드 둘(`app-log-{작업 id}-out|err`): 두 파이프를 함께 읽는다 — 한쪽을 안 읽으면 그 파이프가 차는 순간
 *       docker가 멈춰 다른 쪽도 멈춘다. 줄은 들어온 순서대로 버퍼 하나에 쌓고, 버퍼가 차면 자리가 날 때까지 기다려
 *       docker 파이프에 역압을 건다(읽기 메모리 상한).</li>
 *   <li>전송 스레드 하나(`app-log-{작업 id}-send`): open 뒤 sink를 부르는 유일한 스레드다. batchMillis마다(또는 버퍼가
 *       batchLines에 닿으면) 묶어 보내고 pingSeconds마다 주석 핑을 보낸다. sink가 막히면 이 스트림만 멈춘다 — 다른 스트림의
 *       핑·전송과 시간 상한(서비스의 타이머)은 각자 돈다.</li>
 * </ul>
 * 끝내기는 {@link #close}가 정확히 한 번만 한다. 프로세스 트리 종료와 자리 반납은 부른 스레드에서 바로 하므로 sink가
 * 막혀 있어도 늦어지지 않고, 남은 줄·`end`·complete는 전송 스레드가 마무리한다.
 */
@Slf4j
public final class AppLogStream {

    static final String PING = "ping";
    static final String EVENT_LINES = "lines";
    static final String EVENT_INFO = "info";
    static final String EVENT_END = "end";

    /** 프로세스 트리를 끝낼 때와 docker가 스스로 끝난 뒤 종료 코드를 기다리는 시간. */
    static final Duration PROCESS_WAIT = Duration.ofSeconds(5);

    /** docker 종료 사유를 가를 때 보는 최근 안내 줄 수. */
    private static final int RECENT_INFO = 5;

    /** 컨테이너가 없을 때 docker가 stderr에 내는 문장의 일부. */
    private static final String NO_SUCH_CONTAINER = "No such container";

    /**
     * 읽기 상한의 시각 접두 몫. 원시 한 줄은 2×maxLineChars + 이 값까지만 모은다 — 시각 접두(최대 35자)와 뒤 공백을 떼도
     * 본문이 2×maxLineChars자 남아, 처음 maxLineChars자에 걸친 비밀(EnvVar 값은 4000자 이하)이 잘리지 않고 통째로 가려진다.
     */
    private static final int TS_PREFIX_ALLOWANCE = 64;

    private final long taskId;
    private final AppLogSink sink;
    private final AppLogProperties props;
    private final Consumer<AppLogStream> onClose;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger openReaders = new AtomicInteger(2);

    /** start와 close가 주고받는 칸 — this로 지킨다. */
    private Process process;
    private Thread sender;
    private volatile ScheduledFuture<?> timeLimitTask;

    /** 아래 칸은 lock으로 지킨다. 버퍼 항목은 시각 있는 줄({@link AppLogLine}) 또는 안내 문장(String)이다. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition wake = lock.newCondition();
    private final ArrayDeque<Object> buffer = new ArrayDeque<>();
    private final ArrayDeque<String> recentInfo = new ArrayDeque<>();
    private boolean finishing;
    private AppLogEndReason endReason;

    /**
     * @param onClose 닫을 때 한 번 부른다 — 서비스가 동시 상한 자리를 반납하고 목록에서 뺀다(자리를 잡은 적 없으면 아무 일 없음)
     */
    AppLogStream(long taskId, AppLogSink sink, AppLogProperties props, Consumer<AppLogStream> onClose) {
        this.taskId = taskId;
        this.sink = sink;
        this.props = props;
        this.onClose = onClose;
    }

    long taskId() {
        return taskId;
    }

    /** 브라우저(또는 Nitro)가 끊었다 — 정리만 하고 `end`는 보내지 않는다. */
    public void clientGone() {
        close(null);
    }

    /** 시간 상한에 걸렸다 — 서비스의 타이머가 부른다. */
    void timeLimit() {
        close(AppLogEndReason.TIME_LIMIT);
    }

    boolean closed() {
        return closed.get();
    }

    /**
     * docker 프로세스를 넘겨받아 읽기·전송 스레드를 시작하고 시간 상한을 예약한다. 이미 닫혔으면(open 중 API 종료 등)
     * 넘겨받은 프로세스 트리를 끝내고 아무것도 시작하지 않는다.
     */
    void start(Process p, SecretMasker masker, ScheduledExecutorService timer, Duration timeLimit) {
        Thread send = daemon(this::sendLoop, "send");
        Thread out = daemon(() -> readLoop(p.getInputStream(), "out", masker), "out");
        Thread err = daemon(() -> readLoop(p.getErrorStream(), "err", masker), "err");
        boolean accepted;
        synchronized (this) {
            accepted = !closed.get();
            if (accepted) {
                process = p;
                sender = send;
            }
        }
        if (!accepted) {
            ProcessTrees.killTree(p, PROCESS_WAIT);
            return;
        }
        send.start();
        out.start();
        err.start();
        try {
            ScheduledFuture<?> f = timer.schedule(this::timeLimit, timeLimit.toNanos(), TimeUnit.NANOSECONDS);
            timeLimitTask = f;
            if (closed.get()) f.cancel(false); // close가 예약보다 먼저 지나갔으면 여기서 거둔다
        } catch (RejectedExecutionException e) {
            close(null); // 타이머가 이미 내려감 = API 종료 중
        }
    }

    /**
     * 스트림을 끝낸다. 몇 번, 몇 스레드에서 불러도 한 번만 돈다. 1~4는 락 없이 부른 스레드에서 바로 한다
     * (그래서 sink가 막혀 있어도 프로세스와 자리는 곧바로 정리된다).
     * <ol>
     *   <li>시간 상한 예약 취소</li>
     *   <li>프로세스 트리 종료(원격 데몬이면 ssh 자식까지)</li>
     *   <li>동시 상한 자리 반납</li>
     *   <li>서비스 목록에서 빼기 — 3·4는 서비스의 목록 하나가 자리 수를 겸해 {@code onClose} 한 번으로 한다</li>
     *   <li>버퍼 자리를 기다리는 읽기 스레드를 깨우고(닫힌 뒤 들어온 줄은 버린다) 끝 신호를 전송 스레드에 넘긴다. 전송
     *       스레드가 남은 줄과 `end`를 보내고 complete한다. 전송 스레드가 아직 없으면(open 중 거절·실행 실패) 여기서 한다.</li>
     * </ol>
     *
     * @param reason 화면에 보낼 끝 사유. null이면 받는 쪽이 이미 없다고 보고(끊김·전송 실패·API 종료) 남은 줄과 `end` 없이
     *               complete만 한다.
     */
    void close(AppLogEndReason reason) {
        if (!closed.compareAndSet(false, true)) return;
        Process p;
        Thread s;
        synchronized (this) {
            p = process;
            s = sender;
        }
        ScheduledFuture<?> f = timeLimitTask;
        if (f != null) f.cancel(false);
        if (p != null) ProcessTrees.killTree(p, PROCESS_WAIT);
        onClose.accept(this);
        log.info("앱 로그 스트림 닫음 task={} 사유={}", taskId, reason == null ? "끊김" : reason.wire());
        lock.lock();
        try {
            finishing = true;
            endReason = reason;
            notFull.signalAll();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        if (s == null) finish(List.of(), reason);
    }

    private Thread daemon(Runnable body, String role) {
        Thread t = new Thread(body, "app-log-" + taskId + "-" + role);
        t.setDaemon(true);
        return t;
    }

    // ---- 읽기 ----

    /**
     * 파이프 하나를 `\n` 단위로 읽는다. `BufferedReader.readLine`을 쓰지 않는 이유: 본문 중간의 `\r`에서도 줄을 끊어
     * 한 줄이 둘로 쪼개진다. 한 줄은 상한까지만 모으고 나머지는 `\n`까지 버린다 — 줄바꿈 없는 수 MB 출력도 메모리가 묶인다.
     */
    private void readLoop(InputStream in, String stream, SecretMasker masker) {
        int cap = 2 * props.maxLineChars() + TS_PREFIX_ALLOWANCE;
        StringBuilder line = new StringBuilder();
        char[] chunk = new char[8192];
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(chunk)) != -1) {
                for (int i = 0; i < n; i++) {
                    char c = chunk[i];
                    if (c == '\n') {
                        if (!offer(line.toString(), stream, masker)) return;
                        line.setLength(0);
                    } else if (line.length() < cap) {
                        line.append(c);
                    }
                }
            }
            if (!line.isEmpty()) offer(line.toString(), stream, masker);
        } catch (IOException e) {
            // 프로세스를 끝내면 파이프가 닫혀 여기로 온다 — EOF와 같다
        } catch (RuntimeException e) {
            log.warn("앱 로그 읽기 실패 task={} stream={}: {}", taskId, stream, e.toString());
            close(AppLogEndReason.DOCKER_ERROR);
        } finally {
            readerDone();
        }
    }

    /**
     * 원시 한 줄을 화면 줄로 바꿔 버퍼에 넣는다. 버퍼가 2×batchLines면 자리가 날 때까지 기다린다.
     * 닫혔으면 줄을 버리고 false — 읽기를 멈춘다.
     */
    private boolean offer(String raw, String stream, SecretMasker masker) {
        Object item = AppLogLines.toLine(raw, stream, masker, props.maxLineChars());
        if (item == null) {
            String info = AppLogLines.toInfo(raw, masker, props.maxLineChars());
            if (info.isBlank()) return !closed.get();
            item = info;
        }
        lock.lock();
        try {
            while (buffer.size() >= 2 * props.batchLines() && !closed.get()) {
                notFull.await();
            }
            if (closed.get()) return false;
            buffer.addLast(item);
            if (item instanceof String info) {
                recentInfo.addLast(info);
                if (recentInfo.size() > RECENT_INFO) recentInfo.removeFirst();
            }
            if (buffer.size() >= props.batchLines()) wake.signal();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 두 읽기가 모두 끝나면(docker가 스스로 끝남) 종료 코드를 보고 사유를 정해 닫는다. 이미 닫혔으면 아무것도 안 한다. */
    private void readerDone() {
        if (openReaders.decrementAndGet() > 0 || closed.get()) return;
        Process p;
        synchronized (this) {
            p = process;
        }
        close(exitReason(p));
    }

    /** exit 0 → 컨테이너가 멈춤, 그 밖에 최근 안내에 `No such container`가 있으면 컨테이너 없음, 나머지는 docker 오류. */
    private AppLogEndReason exitReason(Process p) {
        try {
            if (!p.waitFor(PROCESS_WAIT.toMillis(), TimeUnit.MILLISECONDS)) return AppLogEndReason.DOCKER_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AppLogEndReason.DOCKER_ERROR;
        }
        if (p.exitValue() == 0) return AppLogEndReason.CONTAINER_STOPPED;
        lock.lock();
        try {
            return recentInfo.stream().anyMatch(s -> s.contains(NO_SUCH_CONTAINER))
                    ? AppLogEndReason.NO_CONTAINER : AppLogEndReason.DOCKER_ERROR;
        } finally {
            lock.unlock();
        }
    }

    // ---- 전송 ----

    private void sendLoop() {
        long pingNanos = TimeUnit.SECONDS.toNanos(props.pingSeconds());
        long batchNanos = TimeUnit.MILLISECONDS.toNanos(props.batchMillis());
        long nextPing = System.nanoTime() + pingNanos; // 접속 핑은 open이 이미 보냈다
        while (true) {
            List<Object> items;
            boolean done;
            AppLogEndReason reason;
            boolean interrupted = false;
            lock.lock();
            try {
                long flushAt = System.nanoTime() + batchNanos;
                while (!finishing && buffer.size() < props.batchLines()) {
                    long wait = Math.min(flushAt, nextPing) - System.nanoTime();
                    if (wait <= 0) break;
                    try {
                        wake.awaitNanos(wait);
                    } catch (InterruptedException e) {
                        interrupted = true; // 이 스레드를 끊는 곳은 없다 — 생기면 끊김으로 보고 닫는다
                        break;
                    }
                }
                items = new ArrayList<>(buffer);
                buffer.clear();
                notFull.signalAll();
                done = finishing;
                reason = endReason;
            } finally {
                lock.unlock();
            }
            if (done) {
                finish(items, reason);
                return;
            }
            if (interrupted) {
                close(null);
                continue;
            }
            try {
                sendItems(items);
                long now = System.nanoTime();
                if (now - nextPing >= 0) {
                    sink.comment(PING);
                    nextPing = now + pingNanos;
                }
            } catch (IOException | RuntimeException e) {
                // 받는 쪽이 끊겼다 — onError 콜백을 기다리지 않고 여기서 정리한다. 다음 바퀴에 finishing을 보고 끝난다.
                close(null);
            }
        }
    }

    /** 마지막 전송 — reason이 있으면 남은 줄과 `end`를 보내고(실패는 무시) complete한다. */
    private void finish(List<Object> remaining, AppLogEndReason reason) {
        if (reason != null) {
            try {
                sendItems(remaining);
                sink.event(EVENT_END, endData(reason));
            } catch (IOException | RuntimeException ignored) {
                // 받는 쪽이 이미 끊겼다
            }
        }
        sink.complete();
    }

    /** 들어온 순서대로 보낸다 — 이어진 줄은 `lines` 하나에 batchLines줄까지, 안내 문장은 `info` 하나씩. */
    private void sendItems(List<Object> items) throws IOException {
        List<AppLogLine> run = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof AppLogLine line) {
                run.add(line);
                if (run.size() >= props.batchLines()) {
                    sink.event(EVENT_LINES, Map.of("lines", run));
                    run = new ArrayList<>();
                }
            } else {
                if (!run.isEmpty()) {
                    sink.event(EVENT_LINES, Map.of("lines", run));
                    run = new ArrayList<>();
                }
                sink.event(EVENT_INFO, Map.of("message", item));
            }
        }
        if (!run.isEmpty()) sink.event(EVENT_LINES, Map.of("lines", run));
    }

    /** `end` 데이터 — 칸 순서를 `{reason, message}`로 고정한다. */
    private Map<String, String> endData(AppLogEndReason reason) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", reason.wire());
        data.put("message", reason.message(props));
        return data;
    }
}
