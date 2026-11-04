package com.hamonsoft.netismaker.service.applog;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 시험용 docker logs 프로세스. stdout·stderr는 {@link BlockingQueue} 기반 InputStream이다 — PipedInputStream은 쓴 스레드가
 * 끝나면 다음 read가 `Write end dead`로 깨져 시험이 들쭉날쭉해진다. EOF는 {@link #exit}·{@link #destroyForcibly} 때만 낸다.
 * stderr는 실제 파이프처럼 64KB로 묶여 있어, 아무도 읽지 않으면 {@link #writeErr}가 읽힐 때까지 막힌다
 * (stderr를 안 읽는 구현이 docker를 세우는 교착을 시험이 흉내 낼 수 있게). stdout은 묶지 않는다.
 */
final class FakeDockerProcess extends Process {

    private static final int CHUNK_BYTES = 1024;
    private static final int ERR_PIPE_CHUNKS = 64; // 64 × 1KB = 64KB

    private final QueueInputStream out = new QueueInputStream(new LinkedBlockingQueue<>());
    private final QueueInputStream err = new QueueInputStream(new LinkedBlockingQueue<>(ERR_PIPE_CHUNKS));
    private final CountDownLatch exited = new CountDownLatch(1);
    private final CompletableFuture<Process> onExit = new CompletableFuture<>();
    private final AtomicInteger destroyForciblyCount = new AtomicInteger();
    private final Object exitLock = new Object();
    private int exitCode;

    void writeOut(String text) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 64KB가 차 있으면 읽힐 때까지 막힌다. 프로세스가 끝나면 남은 것은 버리고 돌아온다. */
    void writeErr(String text) {
        err.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 두 스트림에 EOF를 넣고 exit 코드를 정한다. 두 번째부터는 아무것도 안 한다. */
    void exit(int code) {
        synchronized (exitLock) {
            if (exited.getCount() == 0) return;
            exitCode = code;
            out.close();
            err.close();
            exited.countDown();
        }
        onExit.complete(this);
    }

    int destroyForciblyCount() {
        return destroyForciblyCount.get();
    }

    /** 카운트를 올리고 exit(137)과 같게 동작한다 — 그래야 killTree(p, 5s)가 5초를 기다리지 않는다. */
    @Override
    public Process destroyForcibly() {
        destroyForciblyCount.incrementAndGet();
        exit(137);
        return this;
    }

    @Override
    public void destroy() {
        exit(143);
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return out;
    }

    @Override
    public InputStream getErrorStream() {
        return err;
    }

    @Override
    public int waitFor() throws InterruptedException {
        exited.await();
        return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return exited.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (exited.getCount() != 0) throw new IllegalThreadStateException("아직 실행 중");
        return exitCode;
    }

    @Override
    public boolean isAlive() {
        return exited.getCount() != 0;
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return onExit;
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return Stream.empty();
    }

    /**
     * 1KB 조각의 큐를 읽는 InputStream(읽는 스레드는 하나). 닫힘은 플래그로 낸다 — 큐가 차 있어 EOF 표식을 못 넣어도
     * 읽는 쪽이 큐를 비운 뒤 플래그를 보고 끝난다.
     */
    private static final class QueueInputStream extends InputStream {

        private static final byte[] EOF = new byte[0];

        private final BlockingQueue<byte[]> queue;
        private volatile boolean closed;
        private boolean eof;
        private byte[] current;
        private int pos;

        QueueInputStream(BlockingQueue<byte[]> queue) {
            this.queue = queue;
        }

        void write(byte[] data) {
            for (int off = 0; off < data.length && !closed; off += CHUNK_BYTES) {
                byte[] chunk = Arrays.copyOfRange(data, off, Math.min(data.length, off + CHUNK_BYTES));
                try {
                    while (!closed && !queue.offer(chunk, 20, TimeUnit.MILLISECONDS)) {
                        // 자리가 날 때까지(또는 끝날 때까지) 기다린다
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            queue.offer(EOF); // 가득 차 있으면 못 넣지만 읽는 쪽이 비운 뒤 closed를 본다
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (eof) return -1;
            if (current == null || pos == current.length) {
                byte[] next;
                try {
                    next = closed ? queue.poll() : queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                if (next == null || next == EOF) {
                    eof = true;
                    return -1;
                }
                current = next;
                pos = 0;
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
