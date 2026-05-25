package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 워커가 사용하는 외부 프로세스 실행 공통 헬퍼.
 *
 *  - stdout/stderr 합본 캡처 (redirectErrorStream=true)
 *  - timeout 시 destroyForcibly
 *  - exitCode != 0이면 ProcessException (stdout 일부 포함)
 *
 *  GitRepoCache의 run/capture와 동일 패턴이지만 worktree/gh 호출에서도 공유.
 */
@Slf4j
public final class ProcessRunner {

    private ProcessRunner() {}

    public static Result run(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workingDir, command, Map.of(), timeoutSeconds);
    }

    public static Result run(File workingDir, List<String> command,
                             Map<String, String> extraEnv, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("exec ({}): {}", workingDir, String.join(" ", command));
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
            throw new IOException("process timeout (" + timeoutSeconds + "s): "
                    + String.join(" ", command));
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
            super("process failed (" + exitCode + "): " + String.join(" ", cmd)
                    + (stdout == null || stdout.isBlank() ? "" : "\n--- output ---\n" + tail(stdout, 2000)));
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
