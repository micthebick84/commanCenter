package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *  `claude -p "<prompt>"` 실행 어댑터.
 *
 *  레포 디렉토리에서 실행. stdout 캡처. timeout 만료 시 강제 종료.
 *  WorkerMcpSupport로 합본 MCP config과 도구 허용 인자를 prepend.
 *
 *  시작 시 claude 바이너리 경로 자동 탐색:
 *   ① 설정값(netis-maker.worker.claude-cli-path)이 실제 실행 가능 파일이면 그대로
 *   ② PATH 환경변수에서 "claude" 검색 (보통 셸 사용자 환경)
 *   ③ 알려진 설치 경로(~/.local/bin, ~/.claude/local, /opt/homebrew/bin, /usr/local/bin, /usr/bin) 시도
 *   ④ 모두 실패 시 설정값 그대로 사용 → exec 시점에 명확한 에러 발생
 *
 *  ②~③이 필요한 이유: GUI 런처(IntelliJ, IDE)에서 띄운 JVM은 셸 rc를 거치지 않아
 *  사용자 PATH를 못 받는 경우가 있고, 머신마다 Homebrew prefix가 다르기 때문.
 */
@Component
@Profile("worker")
@Slf4j
public class ClaudeExecAdapter {

    private final WorkerProperties props;
    private final WorkerMcpSupport mcp;
    private String resolvedClaudePath;

    public ClaudeExecAdapter(WorkerProperties props, WorkerMcpSupport mcp) {
        this.props = props;
        this.mcp = mcp;
    }

    @PostConstruct
    public void init() {
        String configured = props.claudeCliPath();
        String resolved = resolveClaudePath(configured);
        if (resolved == null) {
            log.error("claude CLI 실행 파일을 찾을 수 없습니다. 설정값={}. " +
                    "PATH 및 ~/.local/bin, ~/.claude/local, /opt/homebrew/bin, /usr/local/bin, /usr/bin 모두 확인 실패. " +
                    "claude 분석 작업은 실패합니다. CLAUDE_CLI 환경변수로 절대경로 지정 필요.", configured);
            this.resolvedClaudePath = configured;  // exec 시점에 자연스러운 에러
        } else if (!resolved.equals(configured)) {
            log.info("claude CLI 자동 탐색: 설정값 '{}' 없음 → 사용: {}", configured, resolved);
            this.resolvedClaudePath = resolved;
        } else {
            log.info("claude CLI 경로: {}", resolved);
            this.resolvedClaudePath = resolved;
        }
    }

    /** UI/디버그 노출용. */
    public String getResolvedClaudePath() {
        return resolvedClaudePath;
    }

    private static String resolveClaudePath(String configured) {
        if (isExecutable(configured)) return configured;

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                String candidate = dir + File.separator + "claude";
                if (isExecutable(candidate)) return candidate;
            }
        }

        String home = System.getProperty("user.home");
        for (String candidate : List.of(
                home + "/.local/bin/claude",
                home + "/.claude/local/claude",
                "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude",
                "/usr/bin/claude")) {
            if (isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isExecutable(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            Path p = Path.of(path);
            return Files.isRegularFile(p) && Files.isExecutable(p);
        } catch (Exception e) {
            return false;
        }
    }

    public ExecResult exec(String prompt, File workingDir, Duration timeout) throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, List.of());
    }

    /**
     * task별 추가 MCP 스펙(extras)을 베이스에 머지해 임시 config로 claude 실행.
     * extras가 비어있으면 베이스 args 그대로.
     */
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras)
            throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        WorkerMcpSupport.TaskClaudeArgs mcpArgs = mcp.buildClaudeArgsForTask(extras);
        try {
            // 프롬프트는 stdin으로 전달. 이유:
            // 1) --allowedTools <tools...>가 variadic이라 뒤에 위치한 prompt arg를 삼킴
            // 2) ARG_MAX(~256KB)를 넘는 큰 프롬프트도 안전
            // 3) ps에 프롬프트 본문이 노출되지 않음 (PAT가 들어있을 경우 보호)
            List<String> cmd = new ArrayList<>();
            cmd.add(resolvedClaudePath);
            cmd.add("-p");
            cmd.addAll(mcpArgs.args());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workingDir)
                    .redirectErrorStream(true);
            Process p = pb.start();
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (IOException e) {
                    log.warn("claude stdout 읽기 실패: {}", e.getMessage());
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("claude timeout after " + timeout);
            }
            reader.join(2000);

            return new ExecResult(p.exitValue(), out.toString(), durationMs);
        } finally {
            Path tmp = mcpArgs.tempConfigPath();
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); }
                catch (Exception e) { log.warn("임시 MCP config 삭제 실패: {} ({})", tmp, e.getMessage()); }
            }
        }
    }

    public record ExecResult(int exitCode, String stdout, long durationMs) {}
}
