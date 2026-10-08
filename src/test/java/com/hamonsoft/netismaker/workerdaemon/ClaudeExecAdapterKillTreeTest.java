package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간 초과 시 claude가 띄운 자식까지 끝낸다(2026-10-08 작업 9): Dockerfile 생성 claude가 검증하려고 돌린
 * `docker build`가 timeout 뒤에도 고아로 남아 원격 빌드를 계속했다. claude만 destroyForcibly하면 자식은 살아남는다.
 */
class ClaudeExecAdapterKillTreeTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** 자식 하나(오래 사는 sleep/ping)를 띄우고 기다리는 부모 프로세스. */
    private static Process startParentWithChild() throws Exception {
        List<String> cmd = WINDOWS
                ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                : List.of("sh", "-c", "sleep 60 & wait");
        return new ProcessBuilder(cmd).start();
    }

    private static ProcessHandle awaitChild(Process parent) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<ProcessHandle> child = parent.toHandle().children().findFirst();
            if (child.isPresent()) return child.get();
            Thread.sleep(50);
        }
        throw new AssertionError("자식 프로세스가 뜨지 않음");
    }

    @Test
    void destroyTree_kills_the_children_too() throws Exception {
        Process parent = startParentWithChild();
        ProcessHandle child = awaitChild(parent);

        ClaudeExecAdapter.destroyTree(parent);

        assertThat(parent.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.onExit().toCompletableFuture()
                .completeOnTimeout(null, Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .get()).as("자식도 종료돼야 한다").isNotNull();
        assertThat(child.isAlive()).isFalse();
    }
}
