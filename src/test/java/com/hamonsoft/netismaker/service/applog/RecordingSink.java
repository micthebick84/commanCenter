package com.hamonsoft.netismaker.service.applog;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시험용 sink — 성공한 이벤트·주석·complete를 순서대로 적는다(실패한 전송은 적지 않는다).
 * 전송(event·comment)은 1부터 센다. {@link #failFrom}이면 n번째부터 IOException, {@link #blockFrom}이면 n번째부터
 * {@link #release()}까지 막힌다(느린 소비자).
 */
final class RecordingSink implements AppLogSink {

    enum Kind { EVENT, COMMENT, COMPLETE }

    /** 기록 하나. 주석이면 name이 주석 글, complete면 name·data 모두 null. */
    record Rec(Kind kind, String name, Object data) {
        static Rec event(String name, Object data) {
            return new Rec(Kind.EVENT, name, data);
        }

        static Rec comment(String text) {
            return new Rec(Kind.COMMENT, text, null);
        }

        static Rec complete() {
            return new Rec(Kind.COMPLETE, null, null);
        }
    }

    private final List<Rec> records = new CopyOnWriteArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile int failFrom = Integer.MAX_VALUE;
    private volatile int blockFrom = Integer.MAX_VALUE;

    RecordingSink failFrom(int n) {
        failFrom = n;
        return this;
    }

    RecordingSink blockFrom(int n) {
        blockFrom = n;
        return this;
    }

    void release() {
        released.countDown();
    }

    @Override
    public void event(String name, Object data) throws IOException {
        gate();
        records.add(Rec.event(name, data));
    }

    @Override
    public void comment(String text) throws IOException {
        gate();
        records.add(Rec.comment(text));
    }

    @Override
    public void complete() {
        records.add(Rec.complete());
    }

    private void gate() throws IOException {
        int n = attempts.incrementAndGet();
        if (n >= blockFrom) {
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("가짜 전송 대기 중 인터럽트");
            }
        }
        if (n >= failFrom) throw new IOException("가짜 전송 실패(" + n + "번째)");
    }

    /** 시도한 전송 수(성공·실패·막힘 모두). */
    int attempts() {
        return attempts.get();
    }

    List<Rec> records() {
        return List.copyOf(records);
    }

    /** 이름이 name인 이벤트의 데이터, 받은 순서대로. */
    List<Object> events(String name) {
        return records.stream().filter(r -> r.kind() == Kind.EVENT && r.name().equals(name)).map(Rec::data).toList();
    }

    /** `lines` 이벤트마다 그 줄 목록. */
    @SuppressWarnings("unchecked")
    List<List<AppLogLine>> lineBatches() {
        return events(AppLogStream.EVENT_LINES).stream()
                .map(d -> (List<AppLogLine>) ((Map<String, Object>) d).get("lines"))
                .toList();
    }

    /** 받은 줄 전부, 순서대로. */
    List<AppLogLine> lines() {
        return lineBatches().stream().flatMap(List::stream).toList();
    }

    long pings() {
        return records.stream().filter(r -> r.equals(Rec.comment(AppLogStream.PING))).count();
    }

    long completes() {
        return records.stream().filter(r -> r.kind() == Kind.COMPLETE).count();
    }

    boolean completed() {
        return completes() > 0;
    }
}
