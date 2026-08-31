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
        return exec(prompt, workingDir, timeout, List.of(), false, null, null);
    }

    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras)
            throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, extras, false, null, null);
    }

    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions) throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, extras, dangerouslySkipPermissions, null, null);
    }

    /** claude 명령 조립. --model/--effort는 -p(및 skip) 뒤, mcpArgs(--allowedTools variadic) 앞. blank면 생략. */
    static List<String> buildCommand(String claudePath, boolean dangerouslySkipPermissions,
                                     String model, String effort, List<String> mcpArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudePath);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        if (dangerouslySkipPermissions) cmd.add("--dangerously-skip-permissions");
        if (model != null && !model.isBlank()) { cmd.add("--model"); cmd.add(model); }
        if (effort != null && !effort.isBlank()) { cmd.add("--effort"); cmd.add(effort); }
        cmd.addAll(mcpArgs);
        return cmd;
    }

    /**
     * task별 추가 MCP 스펙(extras)을 베이스에 머지해 임시 config로 claude 실행.
     * extras가 비어있으면 베이스 args 그대로.
     *
     * @param dangerouslySkipPermissions true면 --dangerously-skip-permissions 추가.
     *   비대화식 모드(claude -p)에서 Write/Edit/Bash 같은 built-in tool 호출이
     *   기본으로 차단되어 hang 후 종료되는 문제 회피. 구현 단계 전용.
     *   분석 단계는 read-only라 false로 유지.
     * @param model claude --model 플래그 값. null/blank면 생략.
     * @param effort claude --effort 플래그 값. null/blank면 생략.
     */
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions, String model, String effort)
            throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        WorkerMcpSupport.TaskClaudeArgs mcpArgs = mcp.buildClaudeArgsForTask(extras);
        try {
            // 프롬프트는 stdin으로 전달. 이유:
            // 1) --allowedTools <tools...>가 variadic이라 뒤에 위치한 prompt arg를 삼킴
            // 2) ARG_MAX(~256KB)를 넘는 큰 프롬프트도 안전
            // 3) ps에 프롬프트 본문이 노출되지 않음 (PAT가 들어있을 경우 보호)
            List<String> cmd = buildCommand(resolvedClaudePath, dangerouslySkipPermissions,
                    model, effort, mcpArgs.args());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workingDir);            // redirectErrorStream(true) 제거!
            Process p = pb.start();
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread reader = drain(p.getInputStream(), out);
            Thread errReader = drain(p.getErrorStream(), err);

            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("claude timeout after " + timeout);
            }
            reader.join(2000);
            errReader.join(2000);

            Parsed parsed = parseEnvelope(out.toString());
            if (parsed.usage() == null) {
                log.warn("claude envelope 파싱 실패 — usage 미수집 (stdout {} bytes)", out.length());
            }
            String resultText = parsed.resultText();
            // 실패 진단: exit != 0이면 stderr tail을 결과 텍스트에 덧붙인다 (기존 stdout 병합 대체)
            if (p.exitValue() != 0 && !err.isEmpty()) {
                String tail = err.length() > 2000 ? "…" + err.substring(err.length() - 2000) : err.toString();
                resultText = resultText + "\n[stderr]\n" + tail;
            }
            return new ExecResult(p.exitValue(), resultText, durationMs, parsed.usage());
        } finally {
            Path tmp = mcpArgs.tempConfigPath();
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); }
                catch (Exception e) { log.warn("임시 MCP config 삭제 실패: {} ({})", tmp, e.getMessage()); }
            }
        }
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.append(line).append('\n');
            } catch (IOException e) {
                log.warn("claude 출력 읽기 실패: {}", e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** claude -p --output-format json envelope의 usage (스펙 §4.1). */
    public record Usage(java.math.BigDecimal costUsd, long inputTokens, long outputTokens,
                        long cacheCreationTokens, long cacheReadTokens) {}

    /** stdout = envelope의 result 텍스트 (기존 plain stdout과 동일 내용). usage는 null 가능. */
    public record ExecResult(int exitCode, String stdout, long durationMs, Usage usage) {}

    record Parsed(String resultText, Usage usage) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper ENVELOPE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * envelope 해제. 형식이 안 맞으면 raw 그대로 + usage=null (fallback — 수집 실패가
     * 본 파이프라인을 죽이면 안 된다, 스펙 §7).
     *
     * 스펙 §1: 실패한 실행의 지출도 누적에 포함한다 — result 텍스트 유효성과 usage 추출을 분리.
     * result가 결손/null이어도 usage/cost 데이터는 수집한다 (오류 실행도 청구됨).
     */
    static Parsed parseEnvelope(String raw) {
        if (raw == null || raw.isBlank()) return new Parsed(raw == null ? "" : raw, null);
        try {
            var node = ENVELOPE_MAPPER.readTree(raw.trim());
            if (!node.isObject()) {
                return new Parsed(raw, null);
            }

            // usage 추출: total_cost_usd 또는 usage 오브젝트가 존재하면 시도
            Usage usage = null;
            var u = node.path("usage");
            if (node.path("total_cost_usd").isNumber() || u.isObject()) {
                usage = new Usage(
                        node.path("total_cost_usd").isNumber()
                                ? node.path("total_cost_usd").decimalValue()
                                : java.math.BigDecimal.ZERO,
                        u.path("input_tokens").asLong(0),
                        u.path("output_tokens").asLong(0),
                        u.path("cache_creation_input_tokens").asLong(0),
                        u.path("cache_read_input_tokens").asLong(0));
            }

            // result 텍스트 추출: textual이면 그 값, 아니면 raw 그대로
            String resultText = node.path("result").isTextual()
                    ? node.path("result").asText()
                    : raw;

            // usage가 추출되지 않았으면 raw fallback
            if (usage == null) {
                return new Parsed(raw, null);
            }

            return new Parsed(resultText, usage);
        } catch (Exception e) {
            return new Parsed(raw, null);
        }
    }
}
