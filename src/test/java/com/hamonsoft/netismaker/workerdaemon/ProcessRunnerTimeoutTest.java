package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ProcessRunner의 시간 상한이 출력 EOF와 무관하게 실제로 걸리는지 확인한다.
 *
 * 예전 구현은 stdout을 EOF까지 읽은 뒤에야 waitFor(timeout)을 불러서, 자식이 stdout을 연 채 멈추면
 * (원격 데몬 ssh 위의 docker build, 응답 없는 git fetch) 상한이 발동하지 않고 직렬 워커 스레드가 무기한 막혔다.
 *
 * 자식은 JDK의 java 실행 파일로 띄우는 단일 파일 소스 프로그램({@link #CHILD_SOURCE})이다 — Windows·Linux CI 공통, 셸 불필요.
 */
class ProcessRunnerTimeoutTest {

    /**
     * 테스트용 자식 프로그램.
     *  hang  pid파일 ms               : pid 기록 → "started" 출력 → stdout을 연 채 ms 동안 잔다
     *  exit  code                     : "out"(stdout)·"err"(stderr) 출력 후 code로 끝난다
     *  lines n                        : line-1 … line-n 출력 후 끝난다
     *  spawn pid파일 손자pid파일 소스 ms then
     *                                 : 손자(hang)를 stdout 상속으로 띄워 같은 파이프를 쥐게 한 뒤
     *                                   then=hang이면 자신도 ms 동안 자고, then=exit이면 "done" 출력 후 바로 끝난다
     */
    private static final String CHILD_SOURCE = """
            import java.nio.file.*;

            public class Child {
                public static void main(String[] a) throws Exception {
                    switch (a[0]) {
                        case "hang" -> {
                            Files.writeString(Path.of(a[1]), String.valueOf(ProcessHandle.current().pid()));
                            System.out.println("started");
                            System.out.flush();
                            Thread.sleep(Long.parseLong(a[2]));
                        }
                        case "exit" -> {
                            System.out.println("out");
                            System.err.println("err");
                            System.exit(Integer.parseInt(a[1]));
                        }
                        case "lines" -> {
                            for (int i = 1; i <= Integer.parseInt(a[1]); i++) System.out.println("line-" + i);
                        }
                        case "spawn" -> {
                            Files.writeString(Path.of(a[1]), String.valueOf(ProcessHandle.current().pid()));
                            String java = ProcessHandle.current().info().command().orElseThrow();
                            Process g = new ProcessBuilder(java, a[3], "hang", a[2], a[4]).inheritIO().start();
                            Files.writeString(Path.of(a[2]), String.valueOf(g.pid()));
                            if (a[5].equals("exit")) {
                                System.out.println("done");
                                System.out.flush();
                                System.exit(0);
                            }
                            Thread.sleep(Long.parseLong(a[4]));
                        }
                        default -> throw new IllegalArgumentException(a[0]);
                    }
                }
            }
            """;

    /** 자식이 잠드는 시간 — 예전 구현이면 호출이 이만큼 막힌다. 상한이 걸리면 한참 전에 끝나야 한다. */
    private static final long CHILD_SLEEP_MILLIS = 30_000;

    @TempDir Path tmp;
    private Path source;

    @BeforeEach
    void writeChildSource() throws IOException {
        source = tmp.resolve("Child.java");
        Files.writeString(source, CHILD_SOURCE);
    }

    /**
     * 단언이 실패해도 잠든 자식·손자가 남지 않게 — pid 파일에 적힌 java 프로세스만 정리한다.
     * 끝날 때까지 기다린다: Windows에선 살아 있는 프로세스의 작업 디렉터리(@TempDir)를 지울 수 없다.
     */
    @AfterEach
    void killLeftovers() throws Exception {
        try (Stream<Path> files = Files.list(tmp)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pid")).toList()) {
                Optional<ProcessHandle> h = pidIn(f).flatMap(ProcessHandle::of)
                        .filter(p -> p.info().command().map(c -> c.contains("java")).orElse(false));
                if (h.isPresent()) {
                    h.get().destroyForcibly();
                    h.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }

    private List<String> child(String... args) {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> cmd = new ArrayList<>(List.of(java, source.toString()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static Optional<Long> pidIn(Path pidFile) {
        try {
            return Optional.of(Long.parseLong(Files.readString(pidFile).strip()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();   // 아직 안 썼거나 쓰는 중
        }
    }

    /** pid 파일의 프로세스가 5초 안에 사라지는지. 파일이 없으면(자식이 그 전에 끊김) 검사를 건너뛴다. */
    private static void assertDead(Path pidFile, String who) throws InterruptedException {
        Optional<Long> pid = pidIn(pidFile);
        assumeTrue(pid.isPresent(), who + " pid를 쓰기 전에 끊겨 생존 검사 생략");
        Optional<ProcessHandle> h = ProcessHandle.of(pid.get());
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (h.isPresent() && h.get().isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(h.map(ProcessHandle::isAlive).orElse(false)).as(who + "는 죽어 있어야 한다").isFalse();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Test
    void run_정상_종료하면_종료_코드와_stdout_stderr_합본을_돌려준다() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(tmp.toFile(), child("exit", "3"), 60);

        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdout()).contains("out").contains("err");
    }

    @Test
    void run_자식이_stdout을_연_채_멈춰도_시간_상한에서_끊고_자식을_죽인다() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        long started = System.nanoTime();

        assertThatThrownBy(() -> ProcessRunner.run(tmp.toFile(),
                child("hang", pidFile.toString(), String.valueOf(CHILD_SLEEP_MILLIS)), 3))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("process timeout (3s): ");

        assertThat(millisSince(started)).as("자식의 30초 대기가 끝나기 한참 전에 끊겨야 한다").isLessThan(15_000);
        assertDead(pidFile, "시간 초과된 자식");
    }

    @Test
    void requireSuccess도_같은_시간_상한을_받는다() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        long started = System.nanoTime();

        assertThatThrownBy(() -> ProcessRunner.requireSuccess(tmp.toFile(),
                child("hang", pidFile.toString(), String.valueOf(CHILD_SLEEP_MILLIS)), 3))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("process timeout (3s): ");

        assertThat(millisSince(started)).isLessThan(15_000);
    }

    @Test
    void runStreaming_상한_전까지는_줄마다_콜백하고_상한에서_끊는다() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        List<String> lines = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();

        assertThatThrownBy(() -> ProcessRunner.runStreaming(tmp.toFile(),
                child("hang", pidFile.toString(), String.valueOf(CHILD_SLEEP_MILLIS)), 5, lines::add))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("process timeout (5s): ");

        assertThat(millisSince(started)).isLessThan(15_000);
        assertThat(lines).as("멈추기 전에 낸 줄은 실시간으로 콜백에 전달돼야 한다").containsExactly("started");
        assertDead(pidFile, "시간 초과된 자식");
    }

    @Test
    void runStreaming_줄_순서대로_콜백하고_콜백이_던져도_출력은_다_모은다() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();

        ProcessRunner.Result r = ProcessRunner.runStreaming(tmp.toFile(), child("lines", "50"), 60, line -> {
            seen.add(line);
            throw new IllegalStateException("콜백 실패");
        });

        assertThat(r.exitCode()).isZero();
        assertThat(seen).hasSize(50).startsWith("line-1", "line-2").endsWith("line-50");
        assertThat(r.stdout().lines().toList()).isEqualTo(seen);
    }

    @Test
    void runStreaming_콜백이_느려도_자식이_끝난_뒤_밀린_줄을_모두_전달한다() throws Exception {
        // 자식은 20줄을 내고 곧바로 끝나지만 콜백(배포 로그 HTTP 전송)이 줄마다 400ms 걸린다 — 밀린 줄 처리에 약 8초.
        // 끝난 뒤 정해진 시간만 기다리고 끊으면 빌드 실패 로그의 꼬리(오류 메시지)를 잃는다.
        List<String> seen = new CopyOnWriteArrayList<>();

        ProcessRunner.Result r = ProcessRunner.runStreaming(tmp.toFile(), child("lines", "20"), 60, line -> {
            seen.add(line);
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        assertThat(r.exitCode()).isZero();
        assertThat(seen).hasSize(20).endsWith("line-20");
        assertThat(r.stdout().lines().toList()).isEqualTo(seen);
    }

    @Test
    void run_손자가_같은_파이프를_쥐고_있어도_시간_상한에서_자식과_손자를_모두_죽인다() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        Path grandPidFile = tmp.resolve("grandchild.pid");
        long started = System.nanoTime();

        // 자식만 죽이면 손자가 stdout 파이프를 계속 쥐고 있어 읽기가 끝나지 않는다(docker CLI의 ssh 헬퍼, git의 remote 헬퍼).
        assertThatThrownBy(() -> ProcessRunner.run(tmp.toFile(),
                child("spawn", pidFile.toString(), grandPidFile.toString(), source.toString(),
                        String.valueOf(CHILD_SLEEP_MILLIS), "hang"), 6))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("process timeout (6s): ");

        assertThat(millisSince(started)).isLessThan(20_000);
        assertDead(pidFile, "시간 초과된 자식");
        assertDead(grandPidFile, "시간 초과된 자식의 손자");
    }

    @Test
    void run_자식은_끝났는데_손자가_파이프를_쥐고_있어도_막히지_않고_결과를_돌려준다() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        Path grandPidFile = tmp.resolve("grandchild.pid");
        long started = System.nanoTime();

        ProcessRunner.Result r = ProcessRunner.run(tmp.toFile(),
                child("spawn", pidFile.toString(), grandPidFile.toString(), source.toString(),
                        String.valueOf(CHILD_SLEEP_MILLIS), "exit"), 60);

        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("done");
        assertThat(millisSince(started)).as("손자의 30초 대기를 기다리지 않아야 한다").isLessThan(20_000);
    }
}
