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
                "/bin/claude", "-p", "--model", "claude-opus-4-8", "--effort", "high",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_skip_permissions_flag() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", true, "claude-sonnet-4-6", "medium", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--dangerously-skip-permissions",
                "--model", "claude-sonnet-4-6", "--effort", "medium", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_omits_model_and_effort_when_blank() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, "  ", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--allowedTools", "mcp__a");
    }
}
