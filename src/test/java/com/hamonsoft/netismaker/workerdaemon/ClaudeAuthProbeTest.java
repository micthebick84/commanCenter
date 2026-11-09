package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.ClaudeAuthProbe.Method;
import com.hamonsoft.netismaker.workerdaemon.ClaudeAuthProbe.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** claude 로그인 판정 — env 토큰 → credentials 파일 → `claude auth status` (스펙 2026-11-02 §3). */
class ClaudeAuthProbeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T01:20:00Z");
    private static final String SECRET = "sk-ant-oat01-SECRET-VALUE";

    @TempDir
    Path dir;

    private Path credentials(String oauthJson) throws Exception {
        Path f = dir.resolve(".credentials.json");
        Files.writeString(f, "{\"mcpOAuth\":{},\"claudeAiOauth\":" + oauthJson + "}");
        return f;
    }

    private static String oauth(String access, String refresh, long expiresAt, Long refreshExpiresAt) {
        return "{\"accessToken\":\"" + access + "\",\"refreshToken\":\"" + refresh + "\",\"expiresAt\":" + expiresAt
                + (refreshExpiresAt == null ? "" : ",\"refreshTokenExpiresAt\":" + refreshExpiresAt)
                + ",\"scopes\":[\"user:inference\"],\"subscriptionType\":\"team\"}";
    }

    /** CLI를 부르면 실패하게 — 파일·env로 답이 나와야 하는 경우. */
    private static final Supplier<Boolean> NO_CLI = () -> {
        throw new AssertionError("CLI를 부르면 안 된다");
    };

    @Test
    void env_setup_token_is_ok_without_reading_file_or_cli() throws Exception {
        Path file = credentials(oauth("", "", 0, NOW.minus(Duration.ofDays(2)).toEpochMilli()));

        Status s = ClaudeAuthProbe.evaluate(Map.of("CLAUDE_CODE_OAUTH_TOKEN", SECRET), file, NOW, NO_CLI);

        assertThat(s).isEqualTo(new Status(true, Method.ENV_TOKEN, null));
    }

    @Test
    void env_token_name_is_matched_case_insensitively_and_blank_is_ignored() throws Exception {
        assertThat(ClaudeAuthProbe.evaluate(Map.of("claude_code_oauth_token", SECRET), null, NOW, NO_CLI).method())
                .isEqualTo(Method.ENV_TOKEN);
        assertThat(ClaudeAuthProbe.evaluate(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "  "), null, NOW, () -> true).method())
                .isEqualTo(Method.CLI);
    }

    @Test
    void cli_login_with_future_refresh_expiry_is_ok_and_reports_that_expiry() throws Exception {
        Instant refreshExp = NOW.plus(Duration.ofDays(20));
        Path file = credentials(oauth(SECRET, SECRET, NOW.minus(Duration.ofHours(1)).toEpochMilli(), refreshExp.toEpochMilli()));

        Status s = ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI);

        // access token이 지났어도 refresh token이 살아 있으면 CLI가 갱신한다
        assertThat(s).isEqualTo(new Status(true, Method.CREDENTIALS, refreshExp));
    }

    @Test
    void refresh_token_past_expiry_is_ng_even_before_cli_clears_tokens() throws Exception {
        // 10-31 11:23 ~ 11-02 10:20: 토큰은 아직 파일에 있지만 refresh token이 만료됐다
        Instant refreshExp = Instant.parse("2026-10-31T02:23:45.460Z");
        Path file = credentials(oauth(SECRET, SECRET, refreshExp.toEpochMilli(), refreshExp.toEpochMilli()));

        Status s = ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI);

        assertThat(s).isEqualTo(new Status(false, Method.CREDENTIALS, refreshExp));
    }

    @Test
    void cleared_tokens_after_failed_refresh_is_ng() throws Exception {
        // 11-02 실제 상태: CLI가 갱신에 실패해 토큰을 비웠다(expiresAt 0)
        Instant refreshExp = Instant.parse("2026-10-31T02:23:45.460Z");
        Path file = credentials(oauth("", "", 0, refreshExp.toEpochMilli()));

        Status s = ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI);

        assertThat(s.ok()).isFalse();
        assertThat(s.method()).isEqualTo(Method.CREDENTIALS);
        assertThat(s.expiresAt()).isEqualTo(refreshExp);
    }

    @Test
    void cleared_tokens_without_expiry_field_is_ng_without_expiry() throws Exception {
        Path file = credentials(oauth("", "", 0, null));

        assertThat(ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI))
                .isEqualTo(new Status(false, Method.CREDENTIALS, null));
    }

    @Test
    void missing_refresh_expiry_field_is_ok_without_expiry() throws Exception {
        Path file = credentials(oauth(SECRET, SECRET, NOW.plus(Duration.ofHours(5)).toEpochMilli(), null));

        assertThat(ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI))
                .isEqualTo(new Status(true, Method.CREDENTIALS, null));
    }

    @Test
    void access_token_only_uses_access_expiry() throws Exception {
        Instant accessExp = NOW.minus(Duration.ofMinutes(1));
        Path file = credentials(oauth(SECRET, "", accessExp.toEpochMilli(), null));

        assertThat(ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI))
                .isEqualTo(new Status(false, Method.CREDENTIALS, accessExp));
    }

    @Test
    void missing_file_falls_back_to_cli() {
        Path missing = dir.resolve("nope.json");

        assertThat(ClaudeAuthProbe.evaluate(Map.of(), missing, NOW, () -> true))
                .isEqualTo(new Status(true, Method.CLI, null));
        assertThat(ClaudeAuthProbe.evaluate(Map.of(), missing, NOW, () -> false))
                .isEqualTo(new Status(false, Method.CLI, null));
    }

    @Test
    void file_without_claude_login_or_broken_json_falls_back_to_cli() throws Exception {
        // macOS는 로그인을 키체인에 두고 파일에는 MCP OAuth만 있을 수 있다
        Path mcpOnly = dir.resolve("mcp-only.json");
        Files.writeString(mcpOnly, "{\"mcpOAuth\":{\"x\":{\"accessToken\":\"" + SECRET + "\"}}}");
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"claudeAiOauth\":{\"accessToken\":");

        assertThat(ClaudeAuthProbe.evaluate(Map.of(), mcpOnly, NOW, () -> false).method()).isEqualTo(Method.CLI);
        assertThat(ClaudeAuthProbe.evaluate(Map.of(), broken, NOW, () -> true).method()).isEqualTo(Method.CLI);
    }

    @Test
    void cli_failure_is_unknown() {
        assertThat(ClaudeAuthProbe.evaluate(Map.of(), null, NOW, () -> null)).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void status_never_carries_token_values() throws Exception {
        Path file = credentials(oauth(SECRET, SECRET, 0, NOW.plus(Duration.ofDays(1)).toEpochMilli()));

        Status s = ClaudeAuthProbe.evaluate(Map.of(), file, NOW, NO_CLI);

        assertThat(s.toString()).doesNotContain("SECRET");
    }

    @Test
    void parses_auth_status_json_including_logged_out_output() {
        String loggedOut = """
                {
                  "loggedIn": false,
                  "authMethod": "none",
                  "apiProvider": "firstParty"
                }
                """;
        assertThat(ClaudeAuthProbe.parseLoggedIn(loggedOut)).isFalse();
        assertThat(ClaudeAuthProbe.parseLoggedIn("경고: 새 버전\n{\"loggedIn\": true, \"authMethod\": \"claude.ai\"}\n"))
                .isTrue();
        assertThat(ClaudeAuthProbe.parseLoggedIn("error: unknown command 'auth'")).isNull();
        assertThat(ClaudeAuthProbe.parseLoggedIn("{\"authMethod\": \"none\"}")).isNull();
        assertThat(ClaudeAuthProbe.parseLoggedIn(null)).isNull();
    }

    @Test
    void credentials_file_honors_claude_config_dir() {
        assertThat(ClaudeAuthProbe.credentialsFile(Map.of("CLAUDE_CONFIG_DIR", dir.toString())))
                .isEqualTo(dir.resolve(".credentials.json"));
        assertThat(ClaudeAuthProbe.credentialsFile(Map.of()))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".claude", ".credentials.json"));
    }

    /** 사용량 수집기도 같은 규칙으로 기록 폴더를 찾는다 — 홈은 주입(시험이 실제 ~/.claude를 보지 않게). */
    @Test
    void claude_config_dir() {
        Path home = dir.resolve("home");
        assertThat(ClaudeAuthProbe.claudeConfigDir(Map.of(), home)).isEqualTo(home.resolve(".claude"));
        Path x = dir.resolve("x");
        assertThat(ClaudeAuthProbe.claudeConfigDir(Map.of("CLAUDE_CONFIG_DIR", x.toString()), home)).isEqualTo(x);
        assertThat(ClaudeAuthProbe.claudeConfigDir(Map.of("claude_config_dir", x.toString()), home)).isEqualTo(x);
        assertThat(ClaudeAuthProbe.claudeConfigDir(Map.of("CLAUDE_CONFIG_DIR", " "), home))
                .isEqualTo(home.resolve(".claude"));
    }

    @Test
    void refresh_caches_until_next_run_and_reads_the_live_file() throws Exception {
        Instant refreshExp = Instant.parse("2026-10-31T02:23:45.460Z");
        credentials(oauth("", "", 0, refreshExp.toEpochMilli()));
        AtomicInteger cliCalls = new AtomicInteger();
        ClaudeAuthProbe probe = new ClaudeAuthProbe(mock(ClaudeExecAdapter.class)) {
            @Override
            Boolean cliLoggedIn() {
                cliCalls.incrementAndGet();
                return null;
            }
        };
        probe.env = Map.of("CLAUDE_CONFIG_DIR", dir.toString());
        probe.clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThat(probe.current()).isEqualTo(Status.UNKNOWN);   // 첫 판정 전
        probe.refresh();
        assertThat(probe.current()).isEqualTo(new Status(false, Method.CREDENTIALS, refreshExp));

        // 재로그인 — 다음 판정 회차에 반영된다
        Instant renewed = NOW.plus(Duration.ofDays(30));
        credentials(oauth(SECRET, SECRET, NOW.plus(Duration.ofHours(8)).toEpochMilli(), renewed.toEpochMilli()));
        assertThat(probe.current().ok()).isFalse();
        probe.refresh();
        assertThat(probe.current()).isEqualTo(new Status(true, Method.CREDENTIALS, renewed));
        assertThat(cliCalls).hasValue(0);
    }

    @Test
    void wire_values() {
        Instant exp = Instant.parse("2026-10-31T02:23:45.460Z");
        Status s = new Status(false, Method.CREDENTIALS, exp);
        assertThat(s.methodName()).isEqualTo("CREDENTIALS");
        assertThat(s.expiresAtUtc()).isEqualTo(exp.atOffset(ZoneOffset.UTC));
        assertThat(Status.UNKNOWN.methodName()).isNull();
        assertThat(Status.UNKNOWN.expiresAtUtc()).isNull();
    }
}
