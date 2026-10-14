package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerMcpSupportHeadersTest {

    @Test
    void http_server_headers_are_written_and_allowed() throws Exception {
        WorkerMcpSupport mcp = new WorkerMcpSupport(null); // init() 미호출 = 베이스 MCP 없음 (buildClaudeArgsForTask는 props를 쓰지 않는다)
        WorkerMcpSupport.TaskClaudeArgs args = mcp.buildClaudeArgsForTask(List.of(
                new TaskMcpSpec("db-12", "http://localhost:8090/mcp/db/12", "http", Map.of("Authorization", "Bearer tok"))));
        try {
            JsonNode server = new ObjectMapper().readTree(args.tempConfigPath().toFile()).path("mcpServers").path("db-12");
            assertThat(server.path("type").asText()).isEqualTo("http");
            assertThat(server.path("headers").path("Authorization").asText()).isEqualTo("Bearer tok");
            assertThat(String.join(" ", args.args())).contains("mcp__db-12");
        } finally {
            Files.deleteIfExists(args.tempConfigPath());
        }
    }

    @Test
    void specs_without_headers_have_no_headers_key() throws Exception {
        WorkerMcpSupport mcp = new WorkerMcpSupport(null);
        WorkerMcpSupport.TaskClaudeArgs args = mcp.buildClaudeArgsForTask(List.of(new TaskMcpSpec("ctx7", "https://c", "http")));
        try {
            JsonNode server = new ObjectMapper().readTree(args.tempConfigPath().toFile()).path("mcpServers").path("ctx7");
            assertThat(server.has("headers")).isFalse();
        } finally {
            Files.deleteIfExists(args.tempConfigPath());
        }
    }
}
