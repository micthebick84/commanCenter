package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbMcpToolsTest {

    private static final DbConnectionRef ORA =
            new DbConnectionRef("db-3", "운영 (Oracle)", "ORACLE", "h", 1521, "XE", "u", "S3cret!");

    private final List<String> executed = new ArrayList<>();
    private final ReadOnlyQueryExecutor fake = (ref, sql, params, maxRows) -> {
        executed.add(sql);
        return new QueryResult(List.of("A"), List.of(List.of(1)), false);
    };

    @Test
    void lists_three_tools_with_schemas() {
        var defs = new DbMcpTools(fake).toolDefinitions("운영 (Oracle)");
        assertThat(defs).extracting(d -> d.get("name")).containsExactly("query", "list_tables", "describe_table");
        assertThat(defs.get(0).get("inputSchema").toString()).contains("sql");
    }

    @Test
    void query_runs_read_only_sql() {
        var r = new DbMcpTools(fake).call(ORA, "query", Map.of("sql", "SELECT 1 FROM dual"));
        assertThat(r.isError()).isFalse();
        assertThat(r.text()).isEqualTo("{\"columns\":[\"A\"],\"rows\":[[1]],\"rowCount\":1,\"truncated\":false}");
    }

    @Test
    void query_strips_trailing_semicolon() {
        new DbMcpTools(fake).call(ORA, "query", Map.of("sql", "SELECT 1 FROM dual;"));
        assertThat(executed).containsExactly("SELECT 1 FROM dual");
    }

    @Test
    void write_sql_is_tool_error_and_never_executed() {
        var r = new DbMcpTools(fake).call(ORA, "query", Map.of("sql", "DELETE FROM t"));
        assertThat(r.isError()).isTrue();
        assertThat(r.text()).startsWith("읽기 전용 도구입니다 — 조회문만 허용됩니다");
        assertThat(executed).isEmpty();
    }

    @Test
    void db_errors_are_tool_errors_with_password_masked() {
        ReadOnlyQueryExecutor failing = (ref, sql, params, maxRows) -> {
            throw new SQLException("login failed for S3cret! at h");
        };
        var r = new DbMcpTools(failing).call(ORA, "list_tables", Map.of());
        assertThat(r.isError()).isTrue();
        assertThat(r.text()).isEqualTo("DB 오류: login failed for **** at h");
    }

    @Test
    void describe_table_combines_columns_and_indexes() {
        var r = new DbMcpTools(fake).call(ORA, "describe_table", Map.of("table", "USERS"));
        assertThat(r.text()).startsWith("{\"columns\":{").contains(",\"indexes\":{");
        assertThat(executed).hasSize(2);
    }

    @Test
    void bad_identifier_and_unknown_tool_are_errors() {
        var tools = new DbMcpTools(fake);
        assertThat(tools.call(ORA, "describe_table", Map.of("table", "a\u0001b")).isError()).isTrue();
        assertThat(tools.call(ORA, "describe_table", Map.of()).isError()).isTrue();
        assertThat(tools.call(ORA, "drop_all", Map.of()).isError()).isTrue();
        assertThat(executed).isEmpty();
    }
}
