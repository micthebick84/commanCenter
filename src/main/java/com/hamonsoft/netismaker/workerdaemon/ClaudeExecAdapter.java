package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *  `claude -p "<prompt>"` 실행 어댑터.
 *
 *  레포 디렉토리에서 실행. stdout 캡처. timeout 만료 시 강제 종료.
 *  WorkerMcpSupport로 합본 MCP config과 도구 허용 인자를 prepend.
 */
@Component
@Profile("worker")
@Slf4j
public class ClaudeExecAdapter {

    private final WorkerProperties props;
    private final WorkerMcpSupport mcp;

    public ClaudeExecAdapter(WorkerProperties props, WorkerMcpSupport mcp) {
        this.props = props;
        this.mcp = mcp;
    }

    public ExecResult exec(String prompt, File workingDir, Duration timeout) throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        // 프롬프트는 stdin으로 전달. 이유:
        // 1) --allowedTools <tools...>가 variadic이라 뒤에 위치한 prompt arg를 삼킴
        // 2) ARG_MAX(~256KB)를 넘는 큰 프롬프트도 안전
        // 3) ps에 프롬프트 본문이 노출되지 않음 (PAT가 들어있을 경우 보호)
        List<String> cmd = new ArrayList<>();
        cmd.add(props.claudeCliPath());
        cmd.add("-p");
        cmd.addAll(mcp.buildClaudeArgs());
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
    }

    public record ExecResult(int exitCode, String stdout, long durationMs) {}
}
