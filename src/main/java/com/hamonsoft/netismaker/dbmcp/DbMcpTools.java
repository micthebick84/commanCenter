package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DB MCP 도구 3개 — 인터뷰 서비스 sdk/dbMcp.ts의 Java 판 (스펙 2026-10-14 §6.1·§6.3).
 * 검사·실행이 전부 API 서버에서 일어나므로 워커의 --dangerously-skip-permissions로도 우회할 수 없다.
 */
@Component
@Profile("api")
public class DbMcpTools {

    public record ToolResult(String text, boolean isError) {}

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private final ReadOnlyQueryExecutor executor;

    public DbMcpTools(ReadOnlyQueryExecutor executor) {
        this.executor = executor;
    }

    public List<Map<String, Object>> toolDefinitions(String label) {
        return List.of(
                tool("query",
                        label + " 읽기 전용 조회. SELECT 계열 단일 문장만 실행되고, 결과는 최대 " + DbMcpLimits.MAX_ROWS
                                + "행, " + DbMcpLimits.STATEMENT_TIMEOUT_SECONDS
                                + "초 제한이다. 큰 테이블은 WHERE·LIMIT으로 좁혀서 조회할 것.",
                        Map.of("sql", Map.of("type", "string")), List.of("sql")),
                tool("list_tables",
                        label + "의 테이블·뷰 목록. schema를 생략하면 기본 스키마(PostgreSQL은 시스템 스키마 제외 전체).",
                        Map.of("schema", Map.of("type", "string")), List.of()),
                tool("describe_table",
                        label + "의 테이블 컬럼(이름·타입·널·기본값·주석)과 인덱스. 결과는 {\"columns\":…,\"indexes\":…} JSON 한 덩어리.",
                        orderedProps("table", "schema"), List.of("table")));
    }

    public ToolResult call(DbConnectionRef ref, String tool, Map<String, Object> args) {
        Map<String, Object> a = args == null ? Map.of() : args;
        try {
            SqlDialect d = SqlDialect.of(DbType.valueOf(ref.dbType()));
            return switch (tool) {
                case "query" -> query(ref, d, a);
                case "list_tables" -> {
                    CatalogSql.Query q = CatalogSql.of(d, CatalogSql.Kind.TABLES, ident(a, "schema", false), null);
                    yield ok(ResultFormatter.format(executor.run(ref, q.sql(), q.params(), DbMcpLimits.MAX_CATALOG_ROWS),
                            DbMcpLimits.MAX_CATALOG_ROWS, DbMcpLimits.MAX_RESULT_CHARS));
                }
                case "describe_table" -> {
                    String table = ident(a, "table", true);
                    String schema = ident(a, "schema", false);
                    CatalogSql.Query c = CatalogSql.of(d, CatalogSql.Kind.COLUMNS, schema, table);
                    CatalogSql.Query i = CatalogSql.of(d, CatalogSql.Kind.INDEXES, schema, table);
                    QueryResult cols = executor.run(ref, c.sql(), c.params(), DbMcpLimits.MAX_CATALOG_ROWS);
                    QueryResult idx = executor.run(ref, i.sql(), i.params(), DbMcpLimits.MAX_CATALOG_ROWS);
                    int half = DbMcpLimits.MAX_RESULT_CHARS / 2;
                    yield ok("{\"columns\":" + ResultFormatter.format(cols, DbMcpLimits.MAX_CATALOG_ROWS, half)
                            + ",\"indexes\":" + ResultFormatter.format(idx, DbMcpLimits.MAX_CATALOG_ROWS, half) + "}");
                }
                default -> new ToolResult("알 수 없는 도구입니다: " + tool, true);
            };
        } catch (IllegalArgumentException e) {
            return new ToolResult("입력 오류: " + e.getMessage(), true);
        } catch (SQLException | RuntimeException e) {
            return new ToolResult("DB 오류: " + safeMessage(e, ref.password()), true);
        }
    }

    private ToolResult query(DbConnectionRef ref, SqlDialect d, Map<String, Object> a) throws SQLException {
        Object raw = a.get("sql");
        if (!(raw instanceof String sql) || sql.isEmpty() || sql.length() > 100_000) {
            throw new IllegalArgumentException("sql은 1~100000자 문자열이어야 합니다");
        }
        SqlReadOnlyChecker.Check check = SqlReadOnlyChecker.check(sql, d);
        if (!check.ok()) {
            return new ToolResult("읽기 전용 도구입니다 — " + check.reason()
                    + ". SELECT 계열 단일 문장만 실행할 수 있습니다.", true);
        }
        String body = SqlReadOnlyChecker.stripTrailingSemicolon(sql, d);
        return ok(ResultFormatter.format(executor.run(ref, body, List.of(), DbMcpLimits.MAX_ROWS)));
    }

    private static String ident(Map<String, Object> a, String key, boolean required) {
        Object v = a.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException(key + "가 필요합니다");
            return null;
        }
        if (!(v instanceof String s) || s.isEmpty() || s.length() > 128 || CONTROL.matcher(s).find()) {
            throw new IllegalArgumentException(key + "는 제어문자 없는 1~128자 문자열이어야 합니다");
        }
        return s;
    }

    /** 드라이버 오류에서 비밀번호를 지우고 500자로 자른다 (dbMcp.ts safeMessage와 같음). */
    static String safeMessage(Throwable e, String password) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (password != null && !password.isEmpty()) msg = msg.replace(password, "****");
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    private static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props,
                                            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private static Map<String, Object> orderedProps(String... names) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, Map.of("type", "string"));
        return m;
    }
}
