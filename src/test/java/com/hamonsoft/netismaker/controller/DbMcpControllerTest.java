package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpTokenService;
import com.hamonsoft.netismaker.dbmcp.DbMcpTools;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DbMcpControllerTest {

    private final DbMcpTokenService tokens = mock(DbMcpTokenService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final DbMcpTools tools = mock(DbMcpTools.class);
    private MockMvc mvc;
    private final DbConnectionRef ref =
            new DbConnectionRef("db-12", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DbMcpController(tokens, db, tools)).build();
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        t.setDbConnectionIds(new ArrayList<>(List.of(12L)));
        when(tokens.authenticate("good")).thenReturn(Optional.of(t));
        when(db.resolveForClaim(List.of(12L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
    }

    private org.springframework.test.web.servlet.ResultActions rpc(String token, long conn, String body) throws Exception {
        var req = post("/mcp/db/" + conn).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    @Test
    void initialize_echoes_known_protocol_and_declares_tools() throws Exception {
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("netismaker-db-12"));
    }

    @Test
    void unknown_protocol_gets_server_default() throws Exception {
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .andExpect(jsonPath("$.result.protocolVersion").value(DbMcpController.DEFAULT_PROTOCOL));
    }

    @Test
    void notification_is_202() throws Exception {
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").andExpect(status().isAccepted());
    }

    @Test
    void tools_list_and_call() throws Exception {
        when(tools.toolDefinitions("운영 (MySQL)")).thenReturn(List.of(Map.of("name", "query")));
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\"}")
                .andExpect(jsonPath("$.result.tools[0].name").value("query"));

        when(tools.call(eq(ref), eq("query"), anyMap())).thenReturn(new DbMcpTools.ToolResult("{\"rows\":[]}", false));
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"query\",\"arguments\":{\"sql\":\"SELECT 1\"}}}")
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("{\"rows\":[]}"))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void unusable_connection_is_tool_error() throws Exception {
        when(db.resolveForClaim(List.of(12L))).thenReturn(new DbConnectionService.ClaimDb(List.of(),
                List.of("DB 연결 #12를 사용할 수 없습니다 — 삭제되었거나 비활성화됨")));
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"query\",\"arguments\":{}}}")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("DB 연결 #12를 사용할 수 없습니다 — 삭제되었거나 비활성화됨"));
        verify(tools, never()).call(any(), any(), any());
    }

    @Test
    void unknown_method_is_jsonrpc_error() throws Exception {
        rpc("good", 12, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void missing_or_bad_token_is_401() throws Exception {
        rpc(null, 12, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").andExpect(status().isUnauthorized());
        rpc("bad", 12, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void unselected_connection_is_404() throws Exception {
        rpc("good", 99, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").andExpect(status().isNotFound());
        verify(db, never()).resolveForClaim(any());
    }

    @Test
    void get_is_405() throws Exception {
        mvc.perform(get("/mcp/db/12").header("Authorization", "Bearer good"))
                .andExpect(status().isMethodNotAllowed());
    }

    /** 감사 로그 위조 방지 — 도구 이름·SQL 앞부분의 CR/LF·제어문자는 공백으로. */
    @Test
    void log_safe_replaces_control_chars_and_clips() {
        org.assertj.core.api.Assertions.assertThat(
                DbMcpController.logSafe("query\r\n2026-10-14 INFO forged\tx\u0000\u0085 y", 200))
                .isEqualTo("query  2026-10-14 INFO forged x   y");
        org.assertj.core.api.Assertions.assertThat(DbMcpController.logSafe("a".repeat(205), 200))
                .isEqualTo("a".repeat(200) + "…");
    }
}
