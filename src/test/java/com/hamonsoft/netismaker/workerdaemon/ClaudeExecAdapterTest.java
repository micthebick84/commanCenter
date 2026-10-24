package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeExecAdapterTest {

    /**
     * 보안 불변식(2026-10-01 CLI 2.1.284 init 프로브): `--setting-sources`를 생략하면 CLI가 운영자
     * ~/.claude/settings.json까지 읽어 enabledPlugins 16개·플러그인 스킬 67개·훅(claude-mem 등)이
     * --dangerously-skip-permissions 구현 세션에 붙는다(--strict-mcp-config는 MCP만 막는다).
     * `project`만 두면 운영자 설정은 빠지고 대상 레포 CLAUDE.md(코드 규약)는 계속 읽힌다.
     */
    @Test
    void buildCommand_limits_setting_sources_to_project_for_every_mode() {
        for (boolean skip : new boolean[]{false, true}) {
            List<String> cmd = ClaudeExecAdapter.buildCommand("/bin/claude", skip, null, null, List.of());
            int i = cmd.indexOf("--setting-sources");
            assertThat(i).as("skip=%s", skip).isPositive();
            assertThat(cmd.get(i + 1)).isEqualTo("project");
        }
    }

    @Test
    void buildCommand_inserts_model_and_effort_after_p_before_mcp_args() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, "claude-opus-4-8", "high",
                List.of("--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--output-format", "json", "--setting-sources", "project", "--model", "claude-opus-4-8", "--effort", "high",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_skip_permissions_flag() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", true, "claude-sonnet-4-6", "medium", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--output-format", "json", "--setting-sources", "project", "--dangerously-skip-permissions",
                "--model", "claude-sonnet-4-6", "--effort", "medium", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_omits_model_and_effort_when_blank() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, "  ", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json", "--setting-sources", "project", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_output_format_json() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, null, List.of());
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json", "--setting-sources", "project");
    }

    @Test
    void parseEnvelope_extracts_result_text_and_usage() {
        String raw = """
                {"type":"result","subtype":"success","is_error":false,
                 "result":"## 1. 요구사항 요약\\n내용",
                 "total_cost_usd":0.4231,
                 "usage":{"input_tokens":1200,"output_tokens":340,
                          "cache_creation_input_tokens":50,"cache_read_input_tokens":9000}}
                """;
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).startsWith("## 1. 요구사항 요약");
        assertThat(p.usage()).isNotNull();
        assertThat(p.usage().costUsd()).isEqualByComparingTo("0.4231");
        assertThat(p.usage().inputTokens()).isEqualTo(1200);
        assertThat(p.usage().outputTokens()).isEqualTo(340);
        assertThat(p.usage().cacheCreationTokens()).isEqualTo(50);
        assertThat(p.usage().cacheReadTokens()).isEqualTo(9000);
    }

    @Test
    void parseEnvelope_falls_back_to_raw_on_plain_text() {
        String raw = "## 1. 요구사항 요약\n그냥 텍스트";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo(raw);
        assertThat(p.usage()).isNull();
    }

    @Test
    void parseEnvelope_missing_usage_fields_default_to_zero() {
        String raw = "{\"type\":\"result\",\"result\":\"ok\",\"total_cost_usd\":0.1}";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo("ok");
        assertThat(p.usage().inputTokens()).isZero();
        assertThat(p.usage().cacheReadTokens()).isZero();
    }

    @Test
    void parseEnvelope_error_subtype_without_result_but_with_usage() {
        String raw = "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true," +
                     "\"total_cost_usd\":0.05,\"usage\":{\"input_tokens\":10,\"output_tokens\":0," +
                     "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo(raw);
        assertThat(p.usage()).isNotNull();
        assertThat(p.usage().costUsd()).isEqualByComparingTo("0.05");
        assertThat(p.usage().inputTokens()).isEqualTo(10);
        assertThat(p.usage().outputTokens()).isZero();
    }

    @Test
    void 도구없는_실행은_내장도구와_MCP를_모두_끈다() {
        List<String> cmd = ClaudeExecAdapter.buildCommand("claude", false, "sonnet", null,
                ClaudeExecAdapter.NO_TOOLS_ARGS);
        assertThat(cmd).containsSubsequence("--setting-sources", "project");
        assertThat(cmd).containsSubsequence("--strict-mcp-config", "--tools", "");
        assertThat(cmd).doesNotContain("--dangerously-skip-permissions", "--mcp-config");
    }

    @Test
    void buildCommand_puts_extra_args_after_model_and_before_mcp_args() {
        List<String> cmd = ClaudeExecAdapter.buildCommand("/bin/claude", true, "claude-opus-5-5", "high",
                List.of("--plugin-dir", "C:/sp/6.4.1"),
                List.of("--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json",
                "--setting-sources", "project", "--dangerously-skip-permissions",
                "--model", "claude-opus-5-5", "--effort", "high",
                "--plugin-dir", "C:/sp/6.4.1",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void process_builder_adds_the_extra_env_on_top_of_the_worker_env() {
        ProcessBuilder pb = ClaudeExecAdapter.processBuilder(List.of("claude"), new java.io.File("."),
                java.util.Map.of("GIT_AUTHOR_NAME", "netisMaker", "GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local"));
        assertThat(pb.environment())
                .containsEntry("GIT_AUTHOR_NAME", "netisMaker")
                .containsEntry("GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local");
        assertThat(pb.directory()).isEqualTo(new java.io.File("."));
    }

    @Test
    void stack_secrets_are_removed_case_insensitively_and_everything_else_stays() {
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of(
                "GITLAB_TOKEN", "glpat-x", "worker_api_key", "k", "Spring_Datasource_Password", "pw",
                "NETISMAKER_DB_SECRET_KEY", "key", "PATH", "C:/bin", "CLAUDE_CODE_OAUTH_TOKEN", "keep",
                "NPM_TOKEN", "keep", "DOCKER_HOST", "ssh://x"));
        ClaudeExecAdapter.removeStackSecrets(env);
        assertThat(env).containsOnlyKeys("PATH", "CLAUDE_CODE_OAUTH_TOKEN", "NPM_TOKEN", "DOCKER_HOST");
    }

    @Test
    void process_builder_drops_stack_secrets_but_the_caller_can_still_pass_its_own_env() {
        ProcessBuilder pb = ClaudeExecAdapter.processBuilder(List.of("claude"), new java.io.File("."),
                java.util.Map.of("GIT_AUTHOR_NAME", "netisMaker"));
        assertThat(pb.environment().keySet())
                .noneMatch(k -> ClaudeExecAdapter.STACK_SECRET_ENV.stream().anyMatch(k::equalsIgnoreCase));
        assertThat(pb.environment()).containsEntry("GIT_AUTHOR_NAME", "netisMaker").containsKey("PATH");
    }

    /** 2026-10-15 헤드리스 SDD 실측 결과 JSON — usage는 메인 세션 것뿐이고 modelUsage에 서브에이전트가 들어 있다. */
    @Test
    void parseEnvelope_sums_model_usage_so_subagent_tokens_count() {
        String raw = """
                {"type":"result","result":"끝","total_cost_usd":1.8929,
                 "usage":{"input_tokens":50,"output_tokens":18219,"cache_creation_input_tokens":63011,"cache_read_input_tokens":1381990},
                 "modelUsage":{
                   "claude-opus-5-5":{"inputTokens":58,"outputTokens":25320,"cacheReadInputTokens":1453215,"cacheCreationInputTokens":93089,"costUSD":1.4518},
                   "claude-haiku-4-5-20251001":{"inputTokens":236,"outputTokens":10917,"cacheReadInputTokens":614504,"cacheCreationInputTokens":42292,"costUSD":0.1691},
                   "claude-sonnet-5-5":{"inputTokens":24,"outputTokens":8780,"cacheReadInputTokens":207346,"cacheCreationInputTokens":57085,"costUSD":0.2720}}}
                """;
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo("끝");
        assertThat(p.usage().costUsd()).isEqualByComparingTo("1.8929");
        assertThat(p.usage().inputTokens()).isEqualTo(58 + 236 + 24);
        assertThat(p.usage().outputTokens()).isEqualTo(25320 + 10917 + 8780);
        assertThat(p.usage().cacheReadTokens()).isEqualTo(1453215L + 614504L + 207346L);
        assertThat(p.usage().cacheCreationTokens()).isEqualTo(93089L + 42292L + 57085L);
    }

    @Test
    void parseEnvelope_with_an_empty_model_usage_falls_back_to_usage() {
        String raw = "{\"type\":\"result\",\"result\":\"ok\",\"total_cost_usd\":0.1,\"modelUsage\":{},"
                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.usage().inputTokens()).isEqualTo(7);
        assertThat(p.usage().outputTokens()).isEqualTo(3);
    }
}
