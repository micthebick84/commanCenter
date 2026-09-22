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

    public static Result run(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workingDir, command, Map.of(), timeoutSeconds);
    }

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
