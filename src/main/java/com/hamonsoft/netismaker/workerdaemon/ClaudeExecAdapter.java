package com.hamonsoft.netismaker.workerdaemon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 *  `claude -p "<prompt>"` 실행 어댑터.
 *
 *  레포 디렉토리에서 실행. stdout 캡처. timeout 만료 시 강제 종료.
 */
@Component
@Profile("worker")
@Slf4j
public class ClaudeExecAdapter {

    private final WorkerProperties props;

    public ClaudeExecAdapter(WorkerProperties props) {
        this.props = props;
    }

    public ExecResult exec(String prompt, File workingDir, Duration timeout) throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(props.claudeCliPath(), "-p", prompt)
                .directory(workingDir)
                .redirectErrorStream(true);
        Process p = pb.start();

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
