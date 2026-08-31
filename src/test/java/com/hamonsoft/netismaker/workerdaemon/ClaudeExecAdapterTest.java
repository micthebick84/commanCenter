package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeExecAdapterTest {

    @Test
    void buildCommand_inserts_model_and_effort_after_p_before_mcp_args() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, "claude-opus-4-8", "high",
                List.of("--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--output-format", "json", "--model", "claude-opus-4-8", "--effort", "high",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_skip_permissions_flag() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", true, "claude-sonnet-4-6", "medium", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--output-format", "json", "--dangerously-skip-permissions",
                "--model", "claude-sonnet-4-6", "--effort", "medium", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_omits_model_and_effort_when_blank() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, "  ", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_output_format_json() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, null, List.of());
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json");
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
}
