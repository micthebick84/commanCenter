package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 기다리던 스레드가 인터럽트(Spring 종료 시 스케줄러 shutdownNow 등)되면 claude와 그 자식을 함께 끝낸다.
 * 예전에는 InterruptedException만 던지고 claude가 고아로 남았다(timeout 때만 destroyTree).
 */
class ClaudeExecAdapterInterruptTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmpDir;

    @Test
    void interrupt_while_waiting_kills_process_and_children() throws Exception {
        ClaudeExecAdapter adapter = new ClaudeExecAdapter(mock(WorkerProperties.class), mock(WorkerMcpSupport.class));
        List<String> cmd = WINDOWS
                ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                : List.of("sh", "-c", "sleep 60 & wait");

        Set<Long> before = new HashSet<>();
        ProcessHandle.current().children().forEach(h -> before.add(h.pid()));

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                adapter.run(cmd, "", tmpDir.toFile(), Duration.ofMinutes(5), System.currentTimeMillis(), Map.of());
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "interrupt-test-runner");
        runner.start();

        ProcessHandle parent = null;
        ProcessHandle child = null;
        try {
            // 테스트 시작 뒤에 생긴 직계 자식 중 자기 자식(ping/sleep)까지 뜬 프로세스를 부모로 본다.
            for (int i = 0; i < 200 && child == null; i++) {
                for (ProcessHandle h : (Iterable<ProcessHandle>) ProcessHandle.current().children()::iterator) {
                    if (before.contains(h.pid())) continue;
                    var c = h.children().findFirst();
                    if (c.isPresent()) {
                        parent = h;
                        child = c.get();
                        break;
                    }
                }
                if (child == null) Thread.sleep(50);
            }
            assertThat(child).as("부모+자식 프로세스가 떠야 한다").isNotNull();

            runner.interrupt();
            runner.join(5000);

            assertThat(runner.isAlive()).as("인터럽트 뒤 5초 안에 끝나야 한다").isFalse();
            assertThat(caught.get()).isInstanceOf(InterruptedException.class);
            assertThat(awaitDead(parent)).as("claude(부모)가 종료돼야 한다").isTrue();
            assertThat(awaitDead(child)).as("claude의 자식도 종료돼야 한다").isTrue();
        } finally {
            runner.interrupt();
            if (parent != null) parent.descendants().forEach(ProcessHandle::destroyForcibly);
            if (parent != null) parent.destroyForcibly();
            if (child != null) child.destroyForcibly();
        }
    }

    private static boolean awaitDead(ProcessHandle h) throws Exception {
        h.onExit().get(5, TimeUnit.SECONDS);
        return !h.isAlive();
    }
}
