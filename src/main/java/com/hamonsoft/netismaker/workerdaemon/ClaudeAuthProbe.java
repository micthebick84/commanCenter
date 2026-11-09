package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.util.StackSecretEnv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 *  claude CLI 로그인 상태 판정 — 하트비트의 claude_session_ok·claude_auth_method·claude_auth_expires_at (스펙 2026-11-02 §3).
 *  모델을 부르지 않는다. 순서대로 보고 처음 답이 나온 곳에서 멈춘다:
 *
 *   ① env CLAUDE_CODE_OAUTH_TOKEN(setup-token — 원격 워커)이 있으면 OK. CLI도 저장된 로그인보다 먼저 쓴다. 만료 시각은 모른다
 *   ② {CLAUDE_CONFIG_DIR 또는 ~/.claude}/.credentials.json의 claudeAiOauth(CLI 로그인 — Windows·Linux)
 *      토큰이 다 비었으면 NG(CLI가 갱신에 실패하면 비운다), 만료 시각(refreshTokenExpiresAt)이 지났으면 NG, 아니면 OK
 *   ③ 파일이 없거나 claudeAiOauth가 없으면(macOS 키체인) `claude auth status --json`의 loggedIn
 *
 *  `auth status`를 먼저 보지 않는 이유: refresh token이 만료돼도 CLI가 다음 갱신을 시도하기 전까지는 loggedIn=true다
 *  (2026-10-31 11:23 만료 → 11-02 10:20 첫 실행에서야 비워짐). 답을 못 내면 전부 null(화면 —).
 *  토큰 문자열은 빈 값인지만 보고 로그·결과·예외 메시지에 싣지 않는다.
 *
 *  판정은 별도 스케줄(기본 5분)이 하고 하트비트(10초)는 {@link #current()}로 읽기만 한다 — CLI 실행에 하트비트가 막히지 않게.
 */
@Component
@Profile("worker")
@Slf4j
public class ClaudeAuthProbe {

    /** 만료가 이만큼 안으로 남으면 임박 — 화면(frontend/composables/claudeAuth.ts)과 같은 값. 여기서는 로그에만 쓴다. */
    static final Duration EXPIRING_SOON = Duration.ofDays(3);

    private static final long CLI_TIMEOUT_SECONDS = 30;
    private static final String ENV_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public enum Method { ENV_TOKEN, CREDENTIALS, CLI }

    /** ok·method·expiresAt 모두 null이면 판정 못 함. 하트비트에는 이름·UTC 시각으로 싣는다. */
    public record Status(Boolean ok, Method method, Instant expiresAt) {
        public static final Status UNKNOWN = new Status(null, null, null);

        public String methodName() {
            return method == null ? null : method.name();
        }

        public OffsetDateTime expiresAtUtc() {
            return expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC);
        }
    }

    private enum Level { UNKNOWN, OK, EXPIRING, NG }

    private final ClaudeExecAdapter claude;

    /** 판정에 쓰는 env·시계 — 테스트가 바꾼다. */
    Map<String, String> env = System.getenv();
    Clock clock = Clock.systemUTC();

    private volatile Status current = Status.UNKNOWN;
    /** 마지막으로 로그에 남긴 상태 — 바뀔 때만 남긴다. 스케줄 스레드만 쓴다. */
    private Level loggedLevel = Level.UNKNOWN;
    private Status loggedStatus = Status.UNKNOWN;

    public ClaudeAuthProbe(ClaudeExecAdapter claude) {
        this.claude = claude;
    }

    /** 지금까지의 판정 — 첫 판정 전에는 UNKNOWN. */
    public Status current() {
        return current;
    }

    @Scheduled(fixedDelayString = "#{${netis-maker.worker.claude-auth-check-interval-seconds:300} * 1000}")
    public void refresh() {
        Instant now = clock.instant();
        Status next;
        try {
            next = evaluate(env, credentialsFile(env), now, this::cliLoggedIn);
        } catch (RuntimeException e) {
            log.warn("claude 로그인 판정 실패: {}", e.getClass().getSimpleName());
            next = Status.UNKNOWN;
        }
        current = next;
        logIfChanged(next, now);
    }

    static Status evaluate(Map<String, String> env, Path credentials, Instant now, Supplier<Boolean> cliLoggedIn) {
        String token = envValue(env, ENV_TOKEN);
        if (token != null && !token.isBlank()) return new Status(true, Method.ENV_TOKEN, null);
        Optional<Status> fromFile = fromCredentials(credentials, now);
        if (fromFile.isPresent()) return fromFile.get();
        Boolean loggedIn = cliLoggedIn.get();
        return loggedIn == null ? Status.UNKNOWN : new Status(loggedIn, Method.CLI, null);
    }

    /** 파일로 판정할 수 없으면(없음·읽기 실패·claudeAiOauth 없음) empty — CLI에 묻는다. */
    static Optional<Status> fromCredentials(Path file, Instant now) {
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        JsonNode oauth;
        try {
            oauth = JSON.readTree(file.toFile()).path("claudeAiOauth");
        } catch (IOException e) {
            return Optional.empty();   // CLI가 쓰는 중이거나 깨진 파일
        }
        if (!oauth.isObject()) return Optional.empty();
        boolean hasRefresh = nonBlank(oauth.path("refreshToken"));
        boolean hasAccess = nonBlank(oauth.path("accessToken"));
        // refresh token이 없고 access token만 있으면 access token의 만료가 끝이다
        Instant expiresAt = epochMillis(oauth.path(!hasRefresh && hasAccess ? "expiresAt" : "refreshTokenExpiresAt"));
        if (!hasRefresh && !hasAccess) return Optional.of(new Status(false, Method.CREDENTIALS, expiresAt));
        boolean ok = expiresAt == null || expiresAt.isAfter(now);
        return Optional.of(new Status(ok, Method.CREDENTIALS, expiresAt));
    }

    static Path credentialsFile(Map<String, String> env) {
        return claudeConfigDir(env, Path.of(System.getProperty("user.home"))).resolve(".credentials.json");
    }

    /** claude CLI의 설정 폴더 — env CLAUDE_CONFIG_DIR(대소문자 무시) 또는 {home}/.claude. 사용량 수집기도 쓴다(홈은 주입). */
    static Path claudeConfigDir(Map<String, String> env, Path home) {
        String configDir = envValue(env, "CLAUDE_CONFIG_DIR");
        return configDir != null && !configDir.isBlank() ? Path.of(configDir) : home.resolve(".claude");
    }

    /** `claude auth status --json` — 로그인 안 됨이면 종료 코드 1이라 출력 JSON만 본다. 실행·해석 실패면 null. */
    Boolean cliLoggedIn() {
        String cli = claude.getResolvedClaudePath();
        if (cli == null || cli.isBlank()) return null;
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File(System.getProperty("user.home")),
                    List.of(cli, "auth", "status", "--json"), Map.of(), Set.copyOf(StackSecretEnv.NAMES),
                    CLI_TIMEOUT_SECONDS);
            Boolean loggedIn = parseLoggedIn(r.stdout());
            if (loggedIn == null) log.warn("claude auth status 출력을 해석하지 못함(exit={})", r.exitCode());
            return loggedIn;
        } catch (IOException e) {
            log.warn("claude auth status 실행 실패: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 출력에서 첫 '{'부터 마지막 '}'까지를 JSON으로 읽어 loggedIn — stderr 경고가 섞여도 읽게. */
    static Boolean parseLoggedIn(String output) {
        if (output == null) return null;
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            JsonNode loggedIn = JSON.readTree(output.substring(start, end + 1)).path("loggedIn");
            return loggedIn.isBoolean() ? loggedIn.booleanValue() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void logIfChanged(Status s, Instant now) {
        Level level = levelOf(s, now);
        if (level == loggedLevel && Objects.equals(s, loggedStatus)) return;
        loggedLevel = level;
        loggedStatus = s;
        String until = s.expiresAt() == null ? "" : ", 만료 " + localTime(s.expiresAt());
        switch (level) {
            case NG -> log.warn("claude 로그인 끊김({}{}) — {}", s.method(), until,
                    s.method() == Method.ENV_TOKEN ? "토큰 교체 필요" : "이 PC에서 claude 다시 로그인 또는 setup-token 필요");
            case EXPIRING -> log.warn("claude 로그인 만료 임박({}{}) — 이 PC에서 claude 다시 로그인 또는 setup-token 필요",
                    s.method(), until);
            case OK -> log.info("claude 로그인 확인({}{})", s.method(), until);
            case UNKNOWN -> log.info("claude 로그인 상태를 판정하지 못함");
        }
    }

    private static Level levelOf(Status s, Instant now) {
        if (s.ok() == null) return Level.UNKNOWN;
        if (!s.ok()) return Level.NG;
        if (s.expiresAt() != null && s.expiresAt().isBefore(now.plus(EXPIRING_SOON))) return Level.EXPIRING;
        return Level.OK;
    }

    private static String localTime(Instant t) {
        return LOG_TIME.format(t.atZone(ZoneId.systemDefault()));
    }

    /** env 이름을 대소문자 없이 찾는다 — Windows env는 대소문자를 가리지 않는다. */
    private static String envValue(Map<String, String> env, String name) {
        String exact = env.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static boolean nonBlank(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank();
    }

    private static Instant epochMillis(JsonNode n) {
        return n.isNumber() && n.asLong() > 0 ? Instant.ofEpochMilli(n.asLong()) : null;
    }
}
