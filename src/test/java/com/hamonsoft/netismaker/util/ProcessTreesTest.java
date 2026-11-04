package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTreesTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** 자손을 볼 수 없는 Process(descendants()가 UnsupportedOperationException) — 종료 호출만 센다. */
    private static class BlindProcess extends Process {
        final AtomicInteger destroyed = new AtomicInteger();
        private volatile boolean dead;
        private final boolean exitsAfterDestroy;
        private final boolean interrupted;

        BlindProcess(boolean exitsAfterDestroy, boolean interrupted) {
            this.exitsAfterDestroy = exitsAfterDestroy;
            this.interrupted = interrupted;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interrupted) throw new InterruptedException("test");
            return dead;
        }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            destroyed.incrementAndGet();
            if (exitsAfterDestroy) dead = true;
            return this;
        }
        @Override public boolean isAlive() { return !dead; }
        @Override public Stream<ProcessHandle> descendants() { throw new UnsupportedOperationException("blind"); }
    }

    @Test
    void 트리째_끝낸다() throws Exception {
        List<String> cmd = WINDOWS
                ? List.of("cmd", "/c", "ping -n 30 127.0.0.1")
                : List.of("sh", "-c", "sleep 30 & sleep 30");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            // 자식이 뜰 때까지 기다렸다가 잡아 둔다 — 끝낸 뒤 살아 있지 않은지 본다
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline && descendants.isEmpty()) {
                descendants.addAll(p.descendants().toList());
                if (descendants.isEmpty()) Thread.sleep(20);
            }
            assertThat(descendants).as("자식 프로세스가 떠야 시험이 의미가 있다").isNotEmpty();

            boolean done = ProcessTrees.killTree(p, Duration.ofSeconds(5));

            assertThat(done).isTrue();
            assertThat(p.isAlive()).isFalse();
            for (ProcessHandle d : descendants) {
                assertThat(d.isAlive()).as("자손 %d", d.pid()).isFalse();
            }
        } finally {
            descendants.forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    @Test
    void 자손을_못_보는_프로세스도_끝낸다() {
        BlindProcess p = new BlindProcess(true, false);

        boolean done = ProcessTrees.killTree(p, Duration.ofSeconds(1));

        assertThat(done).isTrue();
        assertThat(p.destroyed).hasValue(1);
    }

    @Test
    void 제때_안_끝나면_false() {
        BlindProcess p = new BlindProcess(false, false);

        boolean done = ProcessTrees.killTree(p, Duration.ofMillis(50));

        assertThat(done).isFalse();
        assertThat(p.destroyed).hasValue(1);
    }

    @Test
    void 인터럽트되면_플래그를_되살리고_false() {
        BlindProcess p = new BlindProcess(true, true);
        try {
            boolean done = ProcessTrees.killTree(p, Duration.ofSeconds(1));

            assertThat(done).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(p.destroyed).hasValue(1);
        } finally {
            Thread.interrupted(); // 다른 시험에 새지 않게 플래그를 지운다
        }
    }
}
