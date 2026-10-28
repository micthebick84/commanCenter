package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 워커가 사용하는 외부 프로세스 실행 공통 헬퍼. 워커의 docker·git·gh 호출이 모두 여기를 지난다
 * (GitRepoCache의 clone/fetch 포함).
 *
 *  - stdout/stderr 합본 캡처 (redirectErrorStream=true)
 *  - timeout 시 자식과 그 하위 프로세스를 destroyForcibly — 출력 EOF와 무관하게 걸린다(execute 참조)
 *  - exitCode != 0이면 ProcessException (stdout 일부 포함) — requireSuccess
 *
 *  ⚠ argv에 자격증명이 실릴 수 있다(git push/fetch/clone은 인증 URL을 인자로 받는다).
 *  application.yml이 com.hamonsoft.netismaker를 DEBUG로 두므로 명령 원문이 worker.log로 나간다
 *  → 명령/출력을 밖으로 내보내는 모든 지점은 {@link #describe}/{@link GitRemotes#mask}를 거친다.
 */
@Slf4j
public final class ProcessRunner {

    /**
     * 출력 읽기 스레드를 기다리는 단위(ms). 강제 종료 뒤에는 이만큼만 기다리고, 정상 종료 뒤에는 이 시간 동안
     * 한 줄도 진척이 없을 때 그만 기다린다 — 자식이 남긴 하위 프로세스가 파이프를 쥐고 있어도 호출이 막히지 않게.
     */
    private static final long READER_WAIT_MILLIS = 5_000;

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
        return run(workingDir, command, extraEnv, Set.of(), timeoutSeconds);
    }

    /**
     * 부모 env에서 removeEnv 이름(대소문자 무시)을 뺀 뒤 extraEnv를 얹어 실행한다 — 참조 repo git이 운영자 PC의
     * askpass를 물려받지 않게(스펙 2026-10-24 §6.2).
     */
    public static Result run(File workingDir, List<String> command, Map<String, String> extraEnv,
                             Set<String> removeEnv, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("exec ({}): {}", workingDir, describe(command));
        return execute(workingDir, command, extraEnv, removeEnv, timeoutSeconds, null);
    }

    /**
     * env에서 removeEnv의 이름을 대소문자 무시로 지우고 extraEnv를 넣는다 — 지우기 먼저, 넣기 나중
     * (인터뷰 서비스 noPromptGitEnv와 같은 규칙). Windows env 이름은 대소문자를 가리지 않아 표기가 달라도 같은 변수다.
     */
    static void applyEnv(Map<String, String> env, Map<String, String> extraEnv, Set<String> removeEnv) {
        if (!removeEnv.isEmpty()) {
            Set<String> names = removeEnv.stream()
                    .map(n -> n.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> doomed = env.keySet().stream()
                    .filter(k -> names.contains(k.toUpperCase(Locale.ROOT)))
                    .toList();
            doomed.forEach(env::remove);
        }
        env.putAll(extraEnv);
    }

    /**
     * run과 동일하나 stdout을 라인 단위로 onLine 콜백에 흘리며 누적도 함께 반환한다.
     * 빌드처럼 오래 걸리는 명령의 실시간 로그 스트리밍용.
     * onLine은 출력 읽기 스레드에서 한 줄씩 순서대로 불리고, 이 메서드가 반환하거나 예외로 끝난 뒤에는 불리지 않는다.
     */
    public static Result runStreaming(File workingDir, List<String> command,
                                      long timeoutSeconds, Consumer<String> onLine)
            throws IOException, InterruptedException {
        return runStreaming(workingDir, command, Map.of(), Set.of(), timeoutSeconds, onLine);
    }

    /** runStreaming + env 조정(규칙은 {@link #applyEnv}) — 배포 전 미리 빌드가 스택 비밀을 빼고 툴체인을 얹을 때. */
    public static Result runStreaming(File workingDir, List<String> command, Map<String, String> extraEnv,
                                      Set<String> removeEnv, long timeoutSeconds, Consumer<String> onLine)
            throws IOException, InterruptedException {
        log.debug("exec-stream ({}): {}", workingDir, describe(command));
        return execute(workingDir, command, extraEnv, removeEnv, timeoutSeconds, onLine);
    }

    public static String requireSuccess(File workingDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        Result r = run(workingDir, command, timeoutSeconds);
        if (r.exitCode() != 0) {
            throw new ProcessException(command, r.exitCode(), r.stdout());
        }
        return r.stdout();
    }

    /**
     * 실행 본체. 출력은 {@link OutputDrain} 스레드가 비우고 호출 스레드는 곧바로 waitFor(timeout)한다.
     *
     * 예전에는 stdout을 EOF까지 읽은 다음에야 waitFor를 불러서, 자식이 stdout을 연 채 멈추면(원격 데몬 ssh 위의
     * docker build, 응답 없는 git fetch) 상한이 발동하지 않고 직렬 워커 스레드가 무기한 막혔다.
     * 시간 초과·인터럽트면 자식과 하위 프로세스를 죽이고 그때까지의 출력은 버린다.
     */
    private static Result execute(File workingDir, List<String> command, Map<String, String> extraEnv,
                                  Set<String> removeEnv, long timeoutSeconds, Consumer<String> onLine)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        if (!extraEnv.isEmpty() || !removeEnv.isEmpty()) applyEnv(pb.environment(), extraEnv, removeEnv);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Process p = pb.start();
        OutputDrain drain = OutputDrain.start(p.getInputStream(), onLine);
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                destroyTree(p);
                drain.join(READER_WAIT_MILLIS);
                throw new IOException("process timeout (" + timeoutSeconds + "s): " + describe(command));
            }
            drain.awaitEof(deadline);
            return new Result(p.exitValue(), drain.close());
        } catch (InterruptedException e) {
            destroyTree(p);
            throw e;
        } finally {
            drain.close();
        }
    }

    /** 자식과 하위 프로세스를 강제 종료 — 하위 프로세스(docker의 ssh 헬퍼, git의 remote 헬퍼)가 파이프를 쥐고 있으면 읽기가 안 끝난다. */
    private static void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);   // 부모가 죽기 전에 목록을 떠야 한다
        } catch (RuntimeException ignore) { /* 하위 프로세스 조회 불가 — 자식만 종료 */ }
        p.destroyForcibly();
    }

    /**
     * 출력 읽기 스레드 — 줄마다 모으고 onLine에 흘린다. close() 뒤에는 모으지도 부르지도 않는다:
     * 하위 프로세스가 파이프를 쥐고 있어 읽기가 안 끝나도, 호출이 끝난 뒤 콜백이 호출자 상태를 건드리지 않게.
     * 모으기·콜백·close가 같은 락 안이라 호출 스레드는 close() 뒤에 읽기 스레드가 쓴 것을 모두 본다.
     */
    private static final class OutputDrain {
        private final StringBuilder out = new StringBuilder();
        private long lines;          // out으로 동기화
        private boolean closed;      // out으로 동기화
        private Thread thread;

        static OutputDrain start(InputStream in, Consumer<String> onLine) {
            OutputDrain d = new OutputDrain();
            d.thread = new Thread(() -> d.pump(in, onLine), "process-output-reader");
            d.thread.setDaemon(true);
            d.thread.start();
            return d;
        }

        private void pump(InputStream in, Consumer<String> onLine) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (out) {
                        if (closed) return;
                        out.append(line).append('\n');
                        lines++;
                        if (onLine != null) {
                            try { onLine.accept(line); } catch (Exception ignore) { /* 콜백 실패가 빌드를 막지 않음 */ }
                        }
                    }
                }
            } catch (IOException ignore) { /* 프로세스를 죽이면 스트림이 닫히며 읽기가 끊긴다 */ }
        }

        void join(long millis) throws InterruptedException {
            thread.join(millis);
        }

        /**
         * 자식이 끝난 뒤 남은 출력을 EOF까지 기다린다. 콜백이 느려 밀린 줄을 처리하는 동안은 계속 기다리고
         * (빌드 실패 로그의 꼬리를 잃지 않게), READER_WAIT_MILLIS 동안 한 줄도 진척이 없거나 원래 시간 상한을
         * 넘기면 그만 기다린다.
         */
        void awaitEof(long deadline) throws InterruptedException {
            long before;
            do {
                before = lines();
                thread.join(READER_WAIT_MILLIS);
            } while (thread.isAlive() && lines() != before && System.nanoTime() - deadline < 0);
        }

        private long lines() {
            synchronized (out) { return lines; }
        }

        /** 지금까지 모은 출력. 이후 줄은 버리고 콜백도 멈춘다. */
        String close() {
            synchronized (out) {
                closed = true;
                return out.toString();
            }
        }
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
