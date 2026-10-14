package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * runBounded 검증 — 자식이 stdout을 계속 열어 둔 채(EOF 없이) 멈춰도 시간 상한이 걸리는지.
 * JDK의 java 실행 파일로 이 클래스의 {@link Child}를 띄운다(Windows·Linux 공통, 셸 불필요).
 */
class ProcessRunnerBoundedTest {

    @TempDir Path tmp;

    /** 테스트용 자식 프로세스: pid 파일 기록 → "out"(stdout)·"err"(stderr) 출력 → 지정 시간 대기 → 지정 종료 코드로 끝. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), String.valueOf(ProcessHandle.current().pid()));
            System.out.println("out");
            System.err.println("err");
            Thread.sleep(Long.parseLong(args[1]));
            System.exit(Integer.parseInt(args[2]));
        }
    }

    private List<String> child(Path pidFile, long sleepMillis, int exitCode) throws Exception {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(java, "-cp", classes.toString(), Child.class.getName(),
                pidFile.toString(), String.valueOf(sleepMillis), String.valueOf(exitCode));
    }

    @Test
    void 정상_종료하면_종료_코드와_stdout_stderr_합본을_돌려준다() throws Exception {
        ProcessRunner.Result r = ProcessRunner.runBounded(null, child(tmp.resolve("pid"), 0, 3), 60);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdout()).contains("out").contains("err");
    }

    @Test
    void 출력을_닫지_않은_채_멈춘_자식도_시간_상한에서_끊고_프로세스를_죽인다() throws Exception {
        Path pidFile = tmp.resolve("pid");
        long started = System.nanoTime();

        // 자식은 "out"을 내보낸 뒤 30초 동안 stdout을 연 채 잔다 — run()이었다면 EOF를 기다리며 여기서 30초 막힌다.
        assertThatThrownBy(() -> ProcessRunner.runBounded(null, child(pidFile, 30_000, 0), 2))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("process timeout (2s): ");

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMillis).as("30초 대기가 끝나기 한참 전에 끊겨야 한다").isLessThan(15_000);

        // JVM 기동이 2초보다 늦어 pid를 쓰기 전에 끊긴 아주 느린 머신이면 생존 검사만 건너뛴다(그때도 위 단언은 이미 통과).
        assumeTrue(Files.exists(pidFile), "자식이 2초 안에 pid를 쓰지 못해 생존 검사 생략");
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        long deadline = System.nanoTime() + 5_000_000_000L;
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        while (h.isPresent() && h.get().isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(h.map(ProcessHandle::isAlive).orElse(false)).as("시간 초과된 자식은 죽어 있어야 한다").isFalse();
    }
}
