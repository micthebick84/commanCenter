package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 워커가 사용하는 외부 프로세스 실행 공통 헬퍼.
 *
 *  - stdout/stderr 합본 캡처 (redirectErrorStream=true)
 *  - timeout 시 destroyForcibly
 *  - exitCode != 0이면 ProcessException (stdout 일부 포함)
 *
 *  GitRepoCache의 run/capture와 동일 패턴이지만 worktree/gh 호출에서도 공유.
 *
 *  ⚠ argv에 자격증명이 실릴 수 있다(git push/fetch/clone은 인증 URL을 인자로 받는다).
 *  application.yml이 com.hamonsoft.netismaker를 DEBUG로 두므로 명령 원문이 worker.log로 나간다
 *  → 명령/출력을 밖으로 내보내는 모든 지점은 {@link #describe}/{@link GitRemotes#mask}를 거친다.
 */
@Slf4j
public final class ProcessRunner {

    private ProcessRunner() {}

    /** 로그·예외 메시지에 실을 명령 문자열. 자격증명은 항상 가린다. */
    static String describe(List<String> command) {
        return GitRemotes.mask(String.join(" ", command));
    }

    /** 알려진 한계: timeout은 출력이 EOF가 된 뒤에야 적용된다 — 시간 상한이 꼭 필요하면 {@link #runBounded}. */
    public static Result run(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workingDir, command, Map.of(), timeoutSeconds);
    }

    /** 정상 종료 뒤 출력 읽기 스레드를 기다리는 상한(ms) — 하위 프로세스가 stdout을 쥐고 있어도 호출이 막히지 않게. */
    private static final long READER_JOIN_MILLIS = 5_000;

    /**
     * run과 같은 일을 하되 시간 상한(timeoutSeconds)이 출력 EOF와 무관하게 실제로 걸린다.
     *
     * run·runStreaming은 stdout을 EOF까지 읽은 다음에야 waitFor(timeout)을 부른다. 자식이 stdout을 연 채 멈추면
     * (예: 원격 데몬 ssh 위의 docker cp) 상한이 발동하지 않고 호출 스레드가 무기한 막힌다. 여기서는 출력을 별도
     * 데몬 스레드가 비우고 호출 스레드는 곧바로 waitFor한다. 시간이 지나면 자식과 그 하위 프로세스를 강제 종료하고
     * run과 같은 모양의 "process timeout" IOException을 던진다(그때까지의 출력은 버린다).
     *
     * run·runStreaming은 기존 동작을 그대로 둔다 — 배포 빌드 같은 다른 호출자가 갑자기 시간 상한을 받지 않도록
     * 이 메서드만 새로 두었고, 그쪽 전환은 별도 후속 작업이다.
     */
    public static Result runBounded(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("exec-bounded ({}): {}", workingDir, describe(command));
        Process p = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (out) { out.append(line).append('\n'); }
                }
            } catch (IOException ignore) { /* 프로세스를 죽이면 스트림이 닫히며 읽기가 끊긴다 */ }
        }, "process-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            destroyTree(p);
            throw e;
        }
        if (!finished) {
            destroyTree(p);
            reader.join(READER_JOIN_MILLIS);
            throw new IOException("process timeout (" + timeoutSeconds + "s): " + describe(command));
        }
        reader.join(READER_JOIN_MILLIS);
        synchronized (out) {
            return new Result(p.exitValue(), out.toString());
        }
    }

    /** 자식과 하위 프로세스를 강제 종료 — 하위 프로세스(예: docker의 ssh 헬퍼)가 파이프를 쥐고 있으면 읽기가 안 끝난다. */
    private static void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);   // 부모가 죽기 전에 목록을 떠야 한다
        } catch (RuntimeException ignore) { /* 하위 프로세스 조회 불가 — 자식만 종료 */ }
        p.destroyForcibly();
    }

    /** 알려진 한계: timeout은 출력이 EOF가 된 뒤에야 적용된다 — 시간 상한이 꼭 필요하면 {@link #runBounded}. */
    public static Result run(File workingDir, List<String> command,
                             Map<String, String> extraEnv, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("exec ({}): {}", workingDir, describe(command));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        if (!extraEnv.isEmpty()) pb.environment().putAll(extraEnv);

        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("process timeout (" + timeoutSeconds + "s): " + describe(command));
        }
        return new Result(p.exitValue(), out.toString());
    }

    /**
     * run과 동일하나 stdout을 라인 단위로 onLine 콜백에 흘리며 누적도 함께 반환한다.
     * 빌드처럼 오래 걸리는 명령의 실시간 로그 스트리밍용.
     * run과 같은 한계: timeout은 출력이 EOF가 된 뒤에야 적용된다(시간 상한이 필요하면 {@link #runBounded}).
     */
    public static Result runStreaming(File workingDir, List<String> command,
                                      long timeoutSeconds, Consumer<String> onLine)
            throws IOException, InterruptedException {
        log.debug("exec-stream ({}): {}", workingDir, describe(command));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                try { onLine.accept(line); } catch (Exception ignore) { /* 콜백 실패가 빌드를 막지 않음 */ }
            }
        }
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("process timeout (" + timeoutSeconds + "s): " + describe(command));
        }
        return new Result(p.exitValue(), out.toString());
    }

    public static String requireSuccess(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        Result r = run(workingDir, command, timeoutSeconds);
        if (r.exitCode() != 0) {
            throw new ProcessException(command, r.exitCode(), r.stdout());
        }
        return r.stdout();
    }

    public record Result(int exitCode, String stdout) {}

    public static class ProcessException extends IOException {
        private final int exitCode;
        private final String stdout;
        public ProcessException(List<String> cmd, int exitCode, String stdout) {
            // 명령 원문(인증 URL 포함 가능)과 캡처 출력을 생성 시점에 마스킹한다 —
            // getMessage()를 쓰는 모든 소비자가 구성에 의해 안전해지도록.
            super("process failed (" + exitCode + "): " + describe(cmd)
                    + (stdout == null || stdout.isBlank() ? ""
                        : "\n--- output ---\n" + GitRemotes.mask(tail(stdout, 2000))));
            this.exitCode = exitCode;
            this.stdout = stdout;
        }
        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }

        private static String tail(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : "…" + s.substring(s.length() - max);
        }
    }
}
