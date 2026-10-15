package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hamonsoft.netismaker.dbmcp.SqlDialect.*;
import static org.assertj.core.api.Assertions.assertThat;

/** TS sqlReadOnly.test.ts와 같은 케이스 — 두 구현의 판정이 갈라지면 안 된다 (스펙 2026-10-14 §6.3). */
class SqlReadOnlyCheckerTest {

    private static final List<SqlDialect> ALL = List.of(POSTGRES, MYSQL, ORACLE);

    private static void ok(String sql, SqlDialect d) {
        assertThat(SqlReadOnlyChecker.check(sql, d).ok()).as(d + ": " + sql).isTrue();
    }

    private static void no(String sql, SqlDialect d) {
        assertThat(SqlReadOnlyChecker.check(sql, d).ok()).as(d + ": " + sql).isFalse();
    }

    @Test
    void allows_basic_queries_in_all_dialects() {
        for (SqlDialect d : ALL) {
            for (String sql : List.of(
                    "SELECT 1",
                    "select * from users where id = 3;",
                    "  WITH t AS (SELECT 1 AS a) SELECT a FROM t",
                    "SELECT updated_at, created_by, set_id FROM orders",
                    "SELECT 'DELETE FROM x; DROP TABLE y' AS note",
                    "SELECT REPLACE(name, 'a', 'b') FROM t",
                    "EXPLAIN SELECT * FROM t",
                    "SELECT * FROM t WHERE name = 'it''s'",
                    "SELECT /* UPDATE 주석 */ 1",
                    "SELECT 1 -- DELETE 주석\n")) ok(sql, d);
        }
    }

    @Test
    void allows_dialect_specific_queries() {
        ok("SHOW TABLES", MYSQL);
        ok("DESC users", MYSQL);
        ok("SELECT `select` FROM `order`", MYSQL);
        ok("SELECT 1 # 주석 DELETE", MYSQL);
        ok("SHOW search_path", POSTGRES);
        ok("VALUES (1), (2)", POSTGRES);
        ok("TABLE users", POSTGRES);
        ok("SELECT $$a;b$$", POSTGRES);
        ok("SELECT \"update\" FROM t", POSTGRES);
        ok("SELECT * FROM dual", ORACLE);
        ok("SELECT \"DELETE\" FROM t", ORACLE);
    }

    @Test
    void rejects_dml_ddl_grant_tx() {
        for (SqlDialect d : ALL) {
            for (String sql : List.of(
                    "DELETE FROM t", "UPDATE t SET a = 1", "INSERT INTO t VALUES (1)",
                    "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1",
                    "CREATE TABLE x (a int)", "ALTER TABLE t ADD c int", "DROP TABLE t", "TRUNCATE t",
                    "GRANT SELECT ON t TO u", "COMMIT", "CALL p()", "", "   ")) no(sql, d);
        }
    }

    @Test
    void rejects_writes_hidden_in_query_shapes() {
        for (SqlDialect d : ALL) {
            for (String sql : List.of(
                    "SELECT 1; DELETE FROM t",
                    "SELECT 1;; ",
                    "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
                    "SELECT * INTO new_t FROM t",
                    "SELECT * FROM t FOR UPDATE",
                    "EXPLAIN ANALYZE SELECT 1",
                    "SELECT 1 /* 닫히지 않은 주석",
                    "SELECT 'unterminated",
                    "SELECT 'a\\' ; DELETE FROM t; --'")) no(sql, d);
        }
    }

    @Test
    void rejects_side_effect_and_file_functions() {
        no("SELECT pg_terminate_backend(123)", POSTGRES);
        no("SELECT set_config('x', 'y', false)", POSTGRES);
        no("SELECT pg_read_file('/etc/passwd')", POSTGRES);
        no("SELECT lo_import('/tmp/x')", POSTGRES);
        no("SELECT dblink_exec('...')", POSTGRES);
        no("SELECT nextval('seq')", POSTGRES);
        no("SELECT pg_sleep(100)", POSTGRES);
        no("SELECT LOAD_FILE('/etc/passwd')", MYSQL);
        no("SELECT SLEEP(100)", MYSQL);
        no("SELECT BENCHMARK(1e9, MD5(1))", MYSQL);
        no("SELECT DBMS_PIPE.RECEIVE_MESSAGE(1) FROM dual", ORACLE);
        no("SELECT UTL_HTTP.REQUEST('http://x') FROM dual", ORACLE);
    }

    @Test
    void rejects_dialect_comment_and_quote_tricks() {
        no("SELECT 1 /*! ; DELETE FROM t */", MYSQL);
        no("SELECT 1 /*M! DELETE FROM t */", MYSQL);
        no("SELECT 1 # x\n; DELETE FROM t", MYSQL);
        no("SELECT 1 # 1; DELETE FROM t", POSTGRES);
        no("SELECT 1--1; DELETE FROM t", MYSQL);
        no("SELECT $a$ ; DELETE FROM t; $a$", MYSQL);
        no("SELECT $$x", POSTGRES);
        no("SELECT `a` ; DELETE FROM t", MYSQL);
        no("SELECT \"a\" ; DELETE FROM t", POSTGRES);
    }

    @Test
    void pg_non_ascii_identifier_before_dollar_quote() {
        no("SELECT é$$ x ; y $$", POSTGRES);
        no("SELECT 가격$$ DELETE FROM t $$", POSTGRES);
        ok("SELECT 가격 FROM t", POSTGRES);
    }

    @Test
    void oracle_q_quote() {
        no("SELECT q'[a' ; DELETE FROM t --]'", ORACLE);
        no("SELECT Q'[x]'", ORACLE);
        no("SELECT nq'[test]'", ORACLE);
        no("SELECT Nq'[test]'", ORACLE);
        no("SELECT NQ'[test]'", ORACLE);
        ok("SELECT 'q' FROM dual", ORACLE);
        ok("SELECT seq FROM t", ORACLE);
        ok("SELECT q_col FROM t", ORACLE);
    }

    @Test
    void pg_sql_string_functions() {
        no("SELECT query_to_xml('SELECT 1', true, true, '')", POSTGRES);
        no("SELECT query_to_xml_and_xmlschema('SELECT 1', true, true, '')", POSTGRES);
        no("SELECT query_to_xmlschema('SELECT 1')", POSTGRES);
        no("SELECT cursor_to_xml('cur')", POSTGRES);
        no("SELECT ts_stat('SELECT 1')", POSTGRES);
        no("SELECT ts_rewrite('SELECT 1')", POSTGRES);
        no("SELECT pg_notify('chan', 'msg')", POSTGRES);
        no("SELECT pg_sleep_for(interval 1)", POSTGRES);
        no("SELECT pg_sleep_until(now())", POSTGRES);
        no("SELECT pg_logical_something()", POSTGRES);
    }

    @Test
    void last_top_level_semicolon() {
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 1;", POSTGRES)).isEqualTo(8);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 'a;b'", POSTGRES)).isEqualTo(-1);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 1 /* ; */ -- ;", ORACLE)).isEqualTo(-1);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 1; SELECT 2;", MYSQL)).isEqualTo(18);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT $$;$$", POSTGRES)).isEqualTo(-1);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 1 -- ;\n;", POSTGRES)).isEqualTo(14);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 1; /* x", POSTGRES)).isEqualTo(-1);
        assertThat(SqlReadOnlyChecker.lastTopLevelSemicolon("SELECT 'x; ", POSTGRES)).isEqualTo(-1);
    }

    @Test
    void strip_trailing_semicolon_keeps_body() {
        assertThat(SqlReadOnlyChecker.stripTrailingSemicolon("SELECT 1 FROM dual; -- 끝", ORACLE))
                .isEqualTo("SELECT 1 FROM dual");
        assertThat(SqlReadOnlyChecker.stripTrailingSemicolon("SELECT 1", ORACLE)).isEqualTo("SELECT 1");
    }

    @Test
    void dialect_of_db_type() {
        assertThat(SqlDialect.of(com.hamonsoft.netismaker.entity.DbType.POSTGRESQL)).isEqualTo(POSTGRES);
        assertThat(SqlDialect.of(com.hamonsoft.netismaker.entity.DbType.MARIADB)).isEqualTo(MYSQL);
        assertThat(SqlDialect.of(com.hamonsoft.netismaker.entity.DbType.ORACLE)).isEqualTo(ORACLE);
    }
}
