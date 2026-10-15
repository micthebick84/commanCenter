# 작업 DB 연결 선택 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 승인 때 레포 DB 연결을 고르면 분석 인터뷰·디자인·구현 단계의 에이전트가 그 DB를 읽기 전용으로 조회할 수 있게 한다.

**Architecture:** 인터뷰는 인터뷰 서비스의 기존 내장 SDK DB MCP(`db-<id>`)를 INTERVIEW 세션까지 넓혀 재사용한다. 디자인·구현(`claude -p`)은 API 서버가 `POST /mcp/db/{connectionId}`로 stateless JSON MCP를 호스팅하고, 워커는 작업 전용 단기 토큰만 담은 http MCP 설정을 claude에 넘긴다. 읽기 전용(SQL 검사·READ ONLY 트랜잭션·ROLLBACK·상한)은 API 서버(Java, `dbmcp` 패키지)에서 강제한다.

**Tech Stack:** Spring Boot 3 / Java 21 / JPA·Flyway(PostgreSQL) / JDBC(pg·mysql·mariadb·ojdbc) / JUnit5·Mockito·H2(테스트) · Node 20 TS + Claude Agent SDK + vitest · Nuxt 3 + Quasar + vitest

**Spec:** `docs/superpowers/specs/2026-10-14-task-db-connection-design.md` (선행: `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md`)

## Global Constraints

- DB 비밀번호 평문은 API 서버 메모리(와 인터뷰 서비스 메모리) 밖으로 나가지 않는다 — 워커 claim 응답·mcp-config 파일·자식 env·로그에 비밀번호 금지.
- 선택 상한 `MAX_SELECTION = 3`, 선택 검증 기준은 **작업 등록자**(`task.requesterId`).
- 상한: 문장 타임아웃 30초, 접속 타임아웃 10초, 결과 200행(카탈로그 1000행), 셀 2000자, 결과 60000자.
- MCP 서버 이름 `db-<connectionId>`, 도구 `query`·`list_tables`·`describe_table` — 인터뷰 서비스와 같은 이름·같은 결과 JSON 형식(`{"columns":[…],"rows":[…],"rowCount":n,"truncated":b}`).
- 작업 토큰: 32바이트 `SecureRandom` → base64url(패딩 없음), DB에는 SHA-256 hex만. TTL 설정 `netismaker.db-mcp.token-ttl`(기본 `2h`).
- `/mcp/**`는 워커 API 키(`X-Worker-API-Key`)를 쓰지 않는다 — `Authorization: Bearer <작업 토큰>`만.
- 새 Spring 빈은 모두 `@Profile("api")`(기존 DB 접속정보 빈과 같음). 워커 데몬 코드(`workerdaemon`)는 API 빈을 참조하지 않는다.
- 마이그레이션은 `V28__task_db_connection.sql`, 테이블은 `com` 스키마.
- 이 PC 테스트 사실: 백엔드 `gradlew test`는 원래 3건 실패(`AttachmentCleanupJobTest`·`AttachmentStorageTest` 심볼릭 링크) — 회귀 아님. Testcontainers 테스트는 skip. Git Bash에서 `--tests "*Interview*"`처럼 디렉터리명과 겹치는 패턴 금지 — 클래스명 명시.
- CRLF: 작업본은 CRLF(`core.autocrlf=true`). Write/Edit 후 기존 파일이 LF로 바뀌면 `file <경로>`로 확인하고 `sed -i 's/\r$//; s/$/\r/' <경로>`로 복구.
- 커밋 작성자: `git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit …`, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **토큰이 상태 전이 뒤에도 살아 있는 경우** — 구현이 PR생성·실패로 넘어간 뒤 같은 토큰으로 호출하면 401이어야 한다(Task 5 `authenticate_rejects_when_task_left_stage`).
2. **재claim 뒤 옛 토큰** — 같은 작업을 다시 claim하면 이전 토큰은 401(Task 5 `older_token_is_rejected_after_reissue`).
3. **선택하지 않은 연결 id로 접근** — 토큰이 맞아도 그 작업이 고르지 않은 `connectionId`면 404(Task 6 `unselected_connection_is_404`).
4. **끝에 `;`가 붙은 SQL·주석으로 숨긴 두 번째 문장** — 허용·거부가 TS와 같아야 하고 Oracle에서 끝 `;`는 실행 전에 떼야 한다(Task 1 포팅 케이스, Task 4 `query_strips_trailing_semicolon`).
5. **DB 문제로 claim이 깨지는 경우** — 접속정보 조회가 던져도 구현·디자인 claim과 인터뷰 claim은 정상 응답(Task 8 `interview_claim_survives_db_resolution_failure`, Task 9 `claim_survives_grant_failure`).

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `src/main/java/com/hamonsoft/netismaker/dbmcp/SqlDialect.java` | DbType → 방언 |
| `.../dbmcp/SqlReadOnlyChecker.java` | TS `sqlReadOnly.ts` 이식 — 단일 조회문 판정·끝 `;` 위치 |
| `.../dbmcp/DbMcpLimits.java` | 상한 상수 |
| `.../dbmcp/QueryResult.java` | 실행 결과(columns·rows·truncated) |
| `.../dbmcp/ResultFormatter.java` | TS `db/result.ts` 이식 — 셀 정리·JSON 텍스트·잘림 |
| `.../dbmcp/CatalogSql.java` | TS `db/catalog.ts` 이식 — list_tables·describe_table SQL(JDBC `?`) |
| `.../dbmcp/ReadOnlyQueryExecutor.java` / `JdbcReadOnlyQueryExecutor.java` | JDBC 읽기 전용 실행(항상 ROLLBACK) |
| `.../dbmcp/DbMcpTools.java` | 도구 정의(tools/list)·호출 분기(tools/call) |
| `.../dbmcp/DbMcpTokenService.java` | 작업 토큰 발급·인증·정리 |
| `.../dbmcp/DbMcpGrantService.java` | 구현·디자인 claim 보강(서버 목록 + 토큰 + 안내) |
| `.../controller/DbMcpController.java` | `POST /mcp/db/{id}` JSON-RPC |
| `.../entity/TaskDbMcpToken.java`, `.../repository/TaskDbMcpTokenRepository.java` | 토큰 행 |
| `.../dto/DbMcpGrant.java` | 워커로 가는 DB MCP 지시(비밀번호 없음) |
| `.../workerdaemon/DbMcpWorkerSupport.java` | grant → http MCP 스펙·프롬프트 섹션 |
| `frontend/composables/taskDbChips.ts` | 작업 상세 DB 칩 라벨 |
| 수정 | `Task`, `ApproveRequest`, `TaskResponse`, `InterviewSummaryResponse`, `InterviewClaimResponse`, `WorkerTaskResponse`, `TaskMcpSpec`, `TaskService`, `InterviewService`, `InterviewWorkerController`, `WorkerController`, `SecurityConfig`, `WorkerMcpSupport`, `WorkerMainLoop`, 인터뷰 서비스 `interviewRunner.ts`·`sessionOptions.ts`·`permissions.ts`, 프론트 `ApproveDialog.vue`·`pages/tasks/[id].vue`·`TaskDetailMobile.vue` |

---

### Task 1: SQL 읽기 전용 검사기 (Java 이식)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/SqlDialect.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/SqlReadOnlyChecker.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/SqlReadOnlyCheckerTest.java`

**Interfaces:**
- Produces: `enum SqlDialect { POSTGRES, MYSQL, ORACLE; static SqlDialect of(DbType) }`
- Produces: `SqlReadOnlyChecker.check(String sql, SqlDialect d) → SqlReadOnlyChecker.Check(boolean ok, String reason)`, `lastTopLevelSemicolon(String, SqlDialect) → int`, `stripTrailingSemicolon(String, SqlDialect) → String`

- [ ] **Step 1: 실패하는 테스트 작성** — TS `netismaker-interview-service/test/sqlReadOnly.test.ts`의 케이스를 그대로 옮긴다.

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.SqlReadOnlyCheckerTest"`
Expected: 컴파일 실패(`SqlReadOnlyChecker` 없음)

- [ ] **Step 3: 구현**

`SqlDialect.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbType;

/** 읽기 전용 검사·카탈로그 SQL의 방언 (인터뷰 서비스 sqlReadOnly.ts의 SqlDialect와 같은 셋). */
public enum SqlDialect {
    POSTGRES, MYSQL, ORACLE;

    public static SqlDialect of(DbType t) {
        return switch (t) {
            case POSTGRESQL -> POSTGRES;
            case ORACLE -> ORACLE;
            case MYSQL, MARIADB -> MYSQL;
        };
    }
}
```

`SqlReadOnlyChecker.java` — `scanSql`·`scanQuoted`·`checkReadOnlySql`를 한 줄씩 옮긴다(인덱스는 둘 다 UTF-16 코드 유닛이라 같다):

```java
package com.hamonsoft.netismaker.dbmcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 읽기 전용 SQL 검사 — 인터뷰 서비스 src/sdk/sqlReadOnly.ts의 Java 이식 (스펙 2026-10-14 §6.3).
 * 원칙: 우리 파서와 DB 파서가 경계를 다르게 볼 수 있으면 항상 더 많이 보는 쪽(거부)을 택한다.
 * 규칙을 바꿀 때는 TS 쪽과 SqlReadOnlyCheckerTest·sqlReadOnly.test.ts를 같이 바꾼다. 최종 방어선은 읽기 전용 DB 계정.
 */
public final class SqlReadOnlyChecker {

    public record Check(boolean ok, String reason) {
        static Check pass() { return new Check(true, null); }
        static Check fail(String reason) { return new Check(false, reason); }
    }

    private static final Set<String> LEADING =
            Set.of("SELECT", "WITH", "EXPLAIN", "SHOW", "DESC", "DESCRIBE", "VALUES", "TABLE");

    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT",
            "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "CALL", "EXEC", "EXECUTE", "DO", "COPY", "LOCK", "UNLOCK",
            "SET", "RESET", "INTO", "LOAD", "HANDLER", "PREPARE", "DEALLOCATE", "LISTEN", "NOTIFY", "VACUUM",
            "ANALYZE", "ANALYSE", "REINDEX", "CLUSTER", "REFRESH", "DISCARD", "KILL", "SHUTDOWN", "PURGE", "FLUSH",
            "OPTIMIZE", "REPAIR");

    private static final Set<String> FORBIDDEN_FUNCS = Set.of(
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile", "set_config",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file", "pg_sleep", "pg_sleep_for",
            "pg_sleep_until", "nextval", "setval", "load_file", "sleep", "benchmark", "get_lock", "release_lock",
            "ts_stat", "ts_rewrite", "pg_notify");

    private static final List<String> FORBIDDEN_PREFIXES =
            List.of("lo_", "dblink", "pg_advisory", "pg_logical_", "dbms_", "utl_", "query_to_", "cursor_to_");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");
    private static final Pattern IDENT_ASCII = Pattern.compile("[A-Za-z0-9_$#]");

    private record Scan(String out, int lastSemicolon, String error) {
        static Scan err(String e) { return new Scan(null, -1, e); }
    }

    private SqlReadOnlyChecker() {}

    public static Check check(String sql, SqlDialect d) {
        Scan s = scan(sql == null ? "" : sql, d);
        if (s.error() != null) return Check.fail(s.error());
        String body = s.out().strip().replaceFirst(";\\s*$", "").strip();
        if (body.isEmpty()) return Check.fail("빈 문장");
        if (body.indexOf(';') >= 0) return Check.fail("여러 문장은 실행할 수 없습니다");
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(body);
        while (m.find()) words.add(m.group());
        String first = words.isEmpty() ? "" : words.get(0).toUpperCase(Locale.ROOT);
        if (!LEADING.contains(first)) {
            return Check.fail("조회문만 허용됩니다(시작 키워드 " + (first.isEmpty() ? "없음" : first) + ")");
        }
        for (String w : words) {
            String upper = w.toUpperCase(Locale.ROOT);
            if (FORBIDDEN_WORDS.contains(upper)) return Check.fail("금지 키워드 " + upper);
            String lower = w.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_FUNCS.contains(lower) || FORBIDDEN_PREFIXES.stream().anyMatch(lower::startsWith)) {
                return Check.fail("금지 함수 " + lower);
            }
        }
        return Check.pass();
    }

    /** 문자열·주석·인용 식별자 밖 마지막 ';' 위치. 없거나 스캐너가 거부하면 -1. */
    public static int lastTopLevelSemicolon(String sql, SqlDialect d) {
        Scan s = scan(sql, d);
        return s.error() != null ? -1 : s.lastSemicolon();
    }

    /** check를 통과한 SQL의 끝 ';'(뒤에는 공백·주석만)를 뗀다 — Oracle은 ';'가 있으면 ORA-00933. */
    public static String stripTrailingSemicolon(String sql, SqlDialect d) {
        int i = lastTopLevelSemicolon(sql, d);
        return i < 0 ? sql : sql.substring(0, i);
    }

    /** JS 문자열 인덱스처럼 범위 밖은 -1. */
    private static int at(String s, int i) {
        return i >= 0 && i < s.length() ? s.charAt(i) : -1;
    }

    private static boolean isIdentChar(int c) {
        if (c < 0) return false;
        return c >= 0x80 || IDENT_ASCII.matcher(String.valueOf((char) c)).matches();
    }

    /** JS 정규식 \s와 같은 범위(유니코드 공백 포함). */
    private static boolean isJsSpace(int c) {
        return c >= 0 && (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF);
    }

    private static Scan scan(String sql, SqlDialect d) {
        StringBuilder out = new StringBuilder();
        int lastSemicolon = -1;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            int next = at(sql, i + 1);
            // -- 주석 (mysql은 뒤에 공백 필요)
            if (ch == '-' && next == '-') {
                int after = at(sql, i + 2);
                if (d != SqlDialect.MYSQL || after < 0 || isJsSpace(after)) {
                    int e = sql.indexOf('\n', i);
                    i = e == -1 ? n : e;
                    out.append(' ');
                    continue;
                }
            }
            if (ch == '#' && d == SqlDialect.MYSQL) {
                int e = sql.indexOf('\n', i);
                i = e == -1 ? n : e;
                out.append(' ');
                continue;
            }
            if (ch == '/' && next == '*') {
                if (d == SqlDialect.MYSQL && (at(sql, i + 2) == '!' || (at(sql, i + 2) == 'M' && at(sql, i + 3) == '!'))) {
                    return Scan.err("MySQL 실행 주석(/*! */)은 허용하지 않습니다");
                }
                int e = sql.indexOf("*/", i + 2);
                if (e == -1) return Scan.err("닫히지 않은 주석");
                i = e + 2;
                out.append(' ');
                continue;
            }
            if (d == SqlDialect.ORACLE && ch == '\'' && i > 0) {
                int prev = at(sql, i - 1);
                if (prev == 'q' || prev == 'Q') {
                    int pp = at(sql, i - 2);
                    if (pp == 'n' || pp == 'N') {
                        if (!isIdentChar(at(sql, i - 3))) return Scan.err("Oracle q'[...'] 대안 인용은 허용하지 않습니다");
                    } else if (!isIdentChar(pp)) {
                        return Scan.err("Oracle q'[...'] 대안 인용은 허용하지 않습니다");
                    }
                }
            }
            if (ch == '\'' || (ch == '"' && d == SqlDialect.MYSQL)) {
                int end = scanQuoted(sql, i, ch);
                if (end < 0) return Scan.err(quotedError(end));
                i = end;
                out.append("''");
                continue;
            }
            if (ch == '"' || (ch == '`' && d == SqlDialect.MYSQL)) {
                int end = scanQuoted(sql, i, ch);
                if (end < 0) return Scan.err(quotedError(end));
                i = end;
                out.append(" x ");
                continue;
            }
            if (ch == '$' && d == SqlDialect.POSTGRES && !isIdentChar(at(sql, i - 1))) {
                Matcher m = DOLLAR_TAG.matcher(sql).region(i, n);
                if (m.lookingAt()) {
                    String tag = m.group();
                    int e = sql.indexOf(tag, i + tag.length());
                    if (e == -1) return Scan.err("닫히지 않은 달러 인용");
                    i = e + tag.length();
                    out.append("''");
                    continue;
                }
            }
            if (ch == ';') lastSemicolon = i;
            out.append(ch);
            i++;
        }
        return new Scan(out.toString(), lastSemicolon, null);
    }

    /** 성공이면 닫는 따옴표 다음 인덱스, 백슬래시면 -1, 닫히지 않았으면 -2. 따옴표 두 개는 이스케이프. */
    private static int scanQuoted(String sql, int start, char quote) {
        int j = start + 1;
        while (j < sql.length()) {
            char c = sql.charAt(j);
            if (c == '\\') return -1;
            if (c == quote) {
                if (at(sql, j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return -2;
    }

    private static String quotedError(int code) {
        return code == -1 ? "백슬래시가 든 문자열/식별자는 허용하지 않습니다" : "닫히지 않은 문자열/식별자";
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.SqlReadOnlyCheckerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dbmcp src/test/java/com/hamonsoft/netismaker/dbmcp
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): 읽기 전용 SQL 검사기 Java 이식 (TS sqlReadOnly와 같은 케이스)"
```

---

### Task 2: 결과 형식·상한·카탈로그 SQL

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/DbMcpLimits.java`, `QueryResult.java`, `ResultFormatter.java`, `CatalogSql.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/ResultFormatterTest.java`, `CatalogSqlTest.java`

**Interfaces:**
- Produces: `DbMcpLimits.{STATEMENT_TIMEOUT_SECONDS=30, CONNECT_TIMEOUT_SECONDS=10, MAX_ROWS=200, MAX_CATALOG_ROWS=1000, MAX_CELL_CHARS=2000, MAX_RESULT_CHARS=60000}`
- Produces: `record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated)`, `record QueryResult.PreClipped(String text)`
- Produces: `ResultFormatter.format(QueryResult) → String`, `format(QueryResult, int maxRows, int maxChars) → String`
- Produces: `CatalogSql.of(SqlDialect, CatalogSql.Kind {TABLES, COLUMNS, INDEXES}, String schema, String table) → CatalogSql.Query(String sql, List<String> params)`

- [ ] **Step 1: 실패하는 테스트 작성**

`ResultFormatterTest.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 인터뷰 서비스 db/result.ts와 같은 JSON 모양·잘림 규칙 (스펙 2026-10-14 §6.3). */
class ResultFormatterTest {

    @Test
    void renders_same_json_shape_as_ts() {
        QueryResult r = new QueryResult(List.of("id", "name"), List.of(List.of(1, "a")), false);
        assertThat(ResultFormatter.format(r))
                .isEqualTo("{\"columns\":[\"id\",\"name\"],\"rows\":[[1,\"a\"]],\"rowCount\":1,\"truncated\":false}");
    }

    @Test
    void nulls_and_big_numbers() {
        QueryResult r = new QueryResult(List.of("a", "b", "c"),
                List.of(Arrays.asList(null, 9007199254740993L, new BigDecimal("1.50"))), false);
        assertThat(ResultFormatter.format(r)).contains("[[null,\"9007199254740993\",\"1.50\"]]");
    }

    @Test
    void clips_long_cells() {
        String big = "x".repeat(DbMcpLimits.MAX_CELL_CHARS + 5);
        QueryResult r = new QueryResult(List.of("t"), List.of(List.of(big)), false);
        assertThat(ResultFormatter.format(r)).contains("…(+5자)");
    }

    @Test
    void pre_clipped_text_passes_through() {
        QueryResult r = new QueryResult(List.of("t"), List.of(List.of(new QueryResult.PreClipped("ab…(+9자)"))), false);
        assertThat(ResultFormatter.format(r)).contains("\"ab…(+9자)\"");
    }

    @Test
    void row_cap_marks_truncated() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(List.of(i));
        String text = ResultFormatter.format(new QueryResult(List.of("i"), rows, false), 3, DbMcpLimits.MAX_RESULT_CHARS);
        assertThat(text).contains("\"rowCount\":3").contains("\"truncated\":true");
    }

    @Test
    void char_cap_drops_rows_until_it_fits() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(List.of("y".repeat(100)));
        String text = ResultFormatter.format(new QueryResult(List.of("v"), rows, false), 200, 1000);
        assertThat(text.length()).isLessThanOrEqualTo(1000);
        assertThat(text).contains("\"truncated\":true");
    }
}
```

`CatalogSqlTest.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSqlTest {

    @Test
    void placeholder_count_matches_params_for_every_dialect_and_kind() {
        for (SqlDialect d : SqlDialect.values()) {
            for (CatalogSql.Kind k : CatalogSql.Kind.values()) {
                CatalogSql.Query q = CatalogSql.of(d, k, null, "users");
                long marks = q.sql().chars().filter(c -> c == '?').count();
                assertThat(marks).as(d + " " + k).isEqualTo(q.params().size());
            }
        }
    }

    @Test
    void user_values_are_bound_not_concatenated() {
        CatalogSql.Query q = CatalogSql.of(SqlDialect.MYSQL, CatalogSql.Kind.COLUMNS, "app'; DROP", "t");
        assertThat(q.sql()).doesNotContain("DROP");
        assertThat(q.params()).containsExactly("app'; DROP", "t");
    }

    @Test
    void pg_tables_binds_schema_twice() {
        CatalogSql.Query q = CatalogSql.of(SqlDialect.POSTGRES, CatalogSql.Kind.TABLES, "public", null);
        assertThat(q.params()).containsExactly("public", "public");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.ResultFormatterTest" --tests "com.hamonsoft.netismaker.dbmcp.CatalogSqlTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`DbMcpLimits.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

/** DB 도구 상한 — 인터뷰 서비스 db/limits.ts와 같은 값 (스펙 2026-10-02 §6.2·§6.3, 2026-10-14 §6.3). 바꿀 땐 양쪽 같이. */
public final class DbMcpLimits {
    public static final int STATEMENT_TIMEOUT_SECONDS = 30;
    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int MAX_ROWS = 200;
    public static final int MAX_CATALOG_ROWS = 1000;
    public static final int MAX_CELL_CHARS = 2000;
    public static final int MAX_RESULT_CHARS = 60_000;

    private DbMcpLimits() {}
}
```

`QueryResult.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import java.util.List;

/** 실행 결과. rows는 상한까지만, 넘쳤으면 truncated. 셀은 JDBC 값 또는 PreClipped. */
public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
    /** 실행기가 이미 잘라 둔 텍스트(CLOB 등) — 포매터가 다시 자르지 않는다. */
    public record PreClipped(String text) {}
}
```

`ResultFormatter.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 도구 결과 텍스트 — 인터뷰 서비스 db/result.ts의 Java 이식. 형식이 같아야 단계마다 에이전트가 같은 모양을 본다. */
public final class ResultFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResultFormatter() {}

    static String clip(String s) {
        int max = DbMcpLimits.MAX_CELL_CHARS;
        return s.length() > max ? s.substring(0, max) + "…(+" + (s.length() - max) + "자)" : s;
    }

    /** JSON에 안전한 셀 값. 큰 정수·소수는 정밀도 보존을 위해 문자열(TS의 bigint 처리와 같은 이유). */
    static Object toCell(Object v) {
        if (v == null) return null;
        if (v instanceof QueryResult.PreClipped p) return p.text();
        if (v instanceof String s) return clip(s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Short || v instanceof Byte
                || v instanceof Double || v instanceof Float) return v;
        if (v instanceof Long || v instanceof BigInteger || v instanceof BigDecimal) return v.toString();
        if (v instanceof Timestamp ts) return ts.toInstant().toString();
        if (v instanceof byte[] b) return "<binary " + b.length + " bytes>";
        return clip(String.valueOf(v));
    }

    public static String format(QueryResult r) {
        return format(r, DbMcpLimits.MAX_ROWS, DbMcpLimits.MAX_RESULT_CHARS);
    }

    public static String format(QueryResult r, int maxRows, int maxChars) {
        boolean truncated = r.truncated() || r.rows().size() > maxRows;
        List<List<Object>> rows = r.rows().subList(0, Math.min(maxRows, r.rows().size())).stream()
                .map(row -> row.stream().map(ResultFormatter::toCell).toList())
                .toList();
        String text = render(r.columns(), rows, truncated);
        while (text.length() > maxChars && !rows.isEmpty()) {
            int keep = (int) Math.floor(rows.size() * ((double) maxChars / text.length()));
            rows = rows.subList(0, Math.min(rows.size() - 1, Math.max(0, keep)));
            truncated = true;
            text = render(r.columns(), rows, truncated);
        }
        return text;
    }

    private static String render(List<String> columns, List<List<Object>> rows, boolean truncated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", columns);
        m.put("rows", rows);
        m.put("rowCount", rows.size());
        m.put("truncated", truncated);
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결과 직렬화 실패", e);
        }
    }
}
```

`CatalogSql.java` — TS `db/catalog.ts`의 SQL을 JDBC `?`로 옮긴다(PG의 `$1` 두 번 = 파라미터 두 번):

```java
package com.hamonsoft.netismaker.dbmcp;

import java.util.Arrays;
import java.util.List;

/**
 * list_tables·describe_table용 방언별 SQL — 인터뷰 서비스 db/catalog.ts 이식.
 * 사용자 값(schema·table)은 바인딩 파라미터로만. schema가 null이면 기본 스키마.
 */
public final class CatalogSql {

    public enum Kind { TABLES, COLUMNS, INDEXES }

    public record Query(String sql, List<String> params) {}

    private static final int LIMIT = DbMcpLimits.MAX_CATALOG_ROWS + 1;

    private CatalogSql() {}

    public static Query of(SqlDialect d, Kind k, String schema, String table) {
        String t = table == null ? "" : table;
        return switch (d) {
            case POSTGRES -> switch (k) {
                case TABLES -> new Query(
                        "SELECT table_schema, table_name, table_type FROM information_schema.tables "
                                + "WHERE (CAST(? AS text) IS NULL AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                                + "AND table_schema NOT LIKE 'pg_toast%') OR table_schema = ? "
                                + "ORDER BY table_schema, table_name LIMIT " + LIMIT,
                        Arrays.asList(schema, schema));
                case COLUMNS -> new Query(
                        "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, "
                                + "col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS comment "
                                + "FROM information_schema.columns c WHERE c.table_schema = COALESCE(CAST(? AS text), current_schema()) "
                                + "AND c.table_name = ? ORDER BY c.ordinal_position LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT indexname, indexdef FROM pg_indexes "
                                + "WHERE schemaname = COALESCE(CAST(? AS text), current_schema()) AND tablename = ? "
                                + "ORDER BY indexname LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
            };
            case MYSQL -> switch (k) {
                case TABLES -> new Query(
                        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) ORDER BY TABLE_NAME LIMIT " + LIMIT,
                        Arrays.asList(schema));
                case COLUMNS -> new Query(
                        "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
                                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) "
                                + "AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? "
                                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
            };
            // Oracle: 비인용 식별자는 대문자 저장 — UPPER로 비교. 행 상한은 실행기 setMaxRows가 건다.
            case ORACLE -> switch (k) {
                case TABLES -> new Query(
                        "SELECT owner, object_name, object_type FROM all_objects "
                                + "WHERE owner = NVL(UPPER(?), USER) AND object_type IN ('TABLE', 'VIEW') ORDER BY object_name",
                        Arrays.asList(schema));
                case COLUMNS -> new Query(
                        "SELECT c.column_name, c.data_type, c.data_length, c.nullable, c.data_default, m.comments "
                                + "FROM all_tab_columns c LEFT JOIN all_col_comments m ON m.owner = c.owner "
                                + "AND m.table_name = c.table_name AND m.column_name = c.column_name "
                                + "WHERE c.owner = NVL(UPPER(?), USER) AND c.table_name = UPPER(?) ORDER BY c.column_id",
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT i.index_name, i.uniqueness, ic.column_position, ic.column_name FROM all_indexes i "
                                + "JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name "
                                + "WHERE i.table_owner = NVL(UPPER(?), USER) AND i.table_name = UPPER(?) "
                                + "ORDER BY i.index_name, ic.column_position",
                        Arrays.asList(schema, t));
            };
        };
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.ResultFormatterTest" --tests "com.hamonsoft.netismaker.dbmcp.CatalogSqlTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dbmcp src/test/java/com/hamonsoft/netismaker/dbmcp
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): 결과 형식·상한·카탈로그 SQL (인터뷰 서비스와 같은 형식)"
```

---

### Task 3: JDBC 읽기 전용 실행기

**Files:**
- Modify: `build.gradle` (테스트 의존성 `testRuntimeOnly 'com.h2database:h2'`)
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/ReadOnlyQueryExecutor.java`, `JdbcReadOnlyQueryExecutor.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/JdbcReadOnlyQueryExecutorTest.java`

**Interfaces:**
- Consumes: `QueryResult`, `DbMcpLimits` (Task 2), `InterviewClaimResponse.DbConnectionRef(serverName, label, dbType, host, port, database, username, password)`, `DbType.jdbcUrl(host, port, db)`
- Produces: `interface ReadOnlyQueryExecutor { QueryResult run(DbConnectionRef ref, String sql, List<String> params, int maxRows) throws SQLException; }`
- Produces: `@Component @Profile("api") JdbcReadOnlyQueryExecutor implements ReadOnlyQueryExecutor` (+ 패키지 전용 생성자 `JdbcReadOnlyQueryExecutor(ConnectionOpener, boolean setTransactionReadOnly)`)

- [ ] **Step 1: 실패하는 테스트 작성**

`build.gradle`의 `testImplementation 'commons-io:commons-io:2.22.0'` 다음 줄에 추가:

```groovy
    testRuntimeOnly 'com.h2database:h2'   // dbmcp 실행기 행 읽기 테스트 — 버전은 Spring Boot BOM 관리
```

`JdbcReadOnlyQueryExecutorTest.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcReadOnlyQueryExecutorTest {

    private static final DbConnectionRef REF =
            new DbConnectionRef("db-1", "테스트 (PostgreSQL)", "POSTGRESQL", "h", 5432, "app", "u", "pw");

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:ro" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INT, name VARCHAR(10))");
            for (int i = 0; i < 5; i++) st.execute("INSERT INTO t VALUES (" + i + ", 'n" + i + "')");
        }
    }

    private JdbcReadOnlyQueryExecutor h2() {
        return new JdbcReadOnlyQueryExecutor(ref -> DriverManager.getConnection(url), false);
    }

    @Test
    void reads_rows_up_to_cap_and_marks_truncated() throws SQLException {
        QueryResult r = h2().run(REF, "SELECT id, name FROM t ORDER BY id", List.of(), 3);
        assertThat(r.columns()).containsExactly("ID", "NAME");
        assertThat(r.rows()).hasSize(3);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void binds_params_including_null() throws SQLException {
        QueryResult r = h2().run(REF, "SELECT id FROM t WHERE name = ? OR CAST(? AS VARCHAR) IS NOT NULL",
                java.util.Arrays.asList("n2", null), 10);
        assertThat(r.rows()).containsExactly(List.of(2));
    }

    @Test
    void always_rolls_back_and_sets_read_only_even_on_failure() throws SQLException {
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.createStatement()).thenReturn(st);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenThrow(new SQLException("boom"));
        JdbcReadOnlyQueryExecutor ex = new JdbcReadOnlyQueryExecutor(ref -> c, true);

        assertThatThrownBy(() -> ex.run(REF, "SELECT 1", List.of(), 10)).isInstanceOf(SQLException.class);

        InOrder o = inOrder(c, st, ps);
        o.verify(c).setAutoCommit(false);
        o.verify(c).setReadOnly(true);
        o.verify(st).execute("SET TRANSACTION READ ONLY");
        o.verify(ps).setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
        o.verify(ps).setMaxRows(11);
        o.verify(c).rollback();
        verify(c).close();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.JdbcReadOnlyQueryExecutorTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`ReadOnlyQueryExecutor.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;

import java.sql.SQLException;
import java.util.List;

/** 읽기 전용으로 한 문장을 실행한다. 호출자가 SqlReadOnlyChecker를 먼저 통과시킨다. ⚠️ ref에 평문 비밀번호. */
public interface ReadOnlyQueryExecutor {
    QueryResult run(DbConnectionRef ref, String sql, List<String> params, int maxRows) throws SQLException;
}
```

`JdbcReadOnlyQueryExecutor.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * JDBC 읽기 전용 실행 (스펙 2026-10-14 §6.3). 호출당 1연결(풀 없음 — 호출 빈도가 낮다).
 * autocommit 끔 + setReadOnly + SET TRANSACTION READ ONLY(PG·MySQL·Oracle 공통 문장) → 실행 → 성공·실패와 무관하게 ROLLBACK.
 * 비밀번호는 URL이 아니라 Properties로만 넘긴다.
 */
@Component
@Profile("api")
public class JdbcReadOnlyQueryExecutor implements ReadOnlyQueryExecutor {

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(DbConnectionRef ref) throws SQLException;
    }

    private final ConnectionOpener opener;
    private final boolean setTransactionReadOnly;

    public JdbcReadOnlyQueryExecutor() {
        this(JdbcReadOnlyQueryExecutor::openJdbc, true);
    }

    /** 테스트용 — H2는 SET TRANSACTION READ ONLY를 모른다. */
    JdbcReadOnlyQueryExecutor(ConnectionOpener opener, boolean setTransactionReadOnly) {
        this.opener = opener;
        this.setTransactionReadOnly = setTransactionReadOnly;
    }

    @Override
    public QueryResult run(DbConnectionRef ref, String sql, List<String> params, int maxRows) throws SQLException {
        try (Connection c = opener.open(ref)) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try {
                if (setTransactionReadOnly) {
                    try (Statement st = c.createStatement()) {
                        st.execute("SET TRANSACTION READ ONLY");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                    ps.setMaxRows(maxRows + 1);
                    for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
                    if (!ps.execute()) return new QueryResult(List.of(), List.of(), false);
                    try (ResultSet rs = ps.getResultSet()) {
                        return read(rs, maxRows);
                    }
                }
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // close가 어차피 트랜잭션을 버린다
                }
            }
        }
    }

    static QueryResult read(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(readCell(rs, i));
            rows.add(row);
        }
        return new QueryResult(cols, rows, truncated);
    }

    private static Object readCell(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v instanceof Clob clob) {
            long len = clob.length();
            int max = DbMcpLimits.MAX_CELL_CHARS;
            String head = clob.getSubString(1, (int) Math.min(len, max));
            return new QueryResult.PreClipped(len > max ? head + "…(+" + (len - max) + "자)" : head);
        }
        if (v instanceof Blob blob) return new QueryResult.PreClipped("<binary " + blob.length() + " bytes>");
        if (v instanceof SQLXML xml) return xml.getString();
        if (v instanceof Array arr) return String.valueOf(arr);
        return v;
    }

    static Connection openJdbc(DbConnectionRef ref) throws SQLException {
        DbType type = DbType.valueOf(ref.dbType());
        return DriverManager.getConnection(type.jdbcUrl(ref.host(), ref.port(), ref.database()), propsFor(type, ref));
    }

    /** 접속 10초, 소켓 40초(문장 타임아웃 30초보다 길게 — 서버 쪽 취소가 먼저 오도록). */
    static Properties propsFor(DbType type, DbConnectionRef ref) {
        Properties p = new Properties();
        p.setProperty("user", ref.username());
        p.setProperty("password", ref.password());
        int connect = DbMcpLimits.CONNECT_TIMEOUT_SECONDS;
        int socket = DbMcpLimits.STATEMENT_TIMEOUT_SECONDS + 10;
        switch (type) {
            case POSTGRESQL -> {
                p.setProperty("connectTimeout", String.valueOf(connect));
                p.setProperty("loginTimeout", String.valueOf(connect));
                p.setProperty("socketTimeout", String.valueOf(socket));
            }
            case MYSQL, MARIADB -> {
                p.setProperty("connectTimeout", String.valueOf(connect * 1000));
                p.setProperty("socketTimeout", String.valueOf(socket * 1000));
            }
            case ORACLE -> {
                p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connect * 1000));
                p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(socket * 1000));
            }
        }
        return p;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.JdbcReadOnlyQueryExecutorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add build.gradle src/main/java/com/hamonsoft/netismaker/dbmcp src/test/java/com/hamonsoft/netismaker/dbmcp
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): JDBC 읽기 전용 실행기 (READ ONLY 트랜잭션·항상 ROLLBACK·행 상한)"
```

---

### Task 4: DB MCP 도구 (정의·호출)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/DbMcpTools.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/DbMcpToolsTest.java`

**Interfaces:**
- Consumes: `SqlReadOnlyChecker`, `SqlDialect`, `CatalogSql`, `ResultFormatter`, `ReadOnlyQueryExecutor`, `DbMcpLimits`
- Produces: `@Component @Profile("api") DbMcpTools(ReadOnlyQueryExecutor)`, `List<Map<String,Object>> toolDefinitions(String label)`, `DbMcpTools.ToolResult call(DbConnectionRef ref, String tool, Map<String,Object> args)`, `record ToolResult(String text, boolean isError)`, `static String safeMessage(Throwable, String password)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpToolsTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```java
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
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpToolsTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dbmcp src/test/java/com/hamonsoft/netismaker/dbmcp
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): query·list_tables·describe_table 도구 (서버 측 읽기 전용 검사)"
```

---

### Task 5: 데이터(V28)·작업 토큰 서비스

**Files:**
- Create: `src/main/resources/db/migration/V28__task_db_connection.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (필드 추가 — `mcpsExtra` 필드 바로 아래)
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskDbMcpToken.java`, `src/main/java/com/hamonsoft/netismaker/repository/TaskDbMcpTokenRepository.java`, `src/main/java/com/hamonsoft/netismaker/dbmcp/DbMcpTokenService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/DbMcpTokenServiceTest.java`

**Interfaces:**
- Produces: `Task.getDbConnectionIds() / setDbConnectionIds(List<Long>)` (기본 `[]`)
- Produces: `DbMcpTokenService.issue(Task) → String`(평문 토큰), `authenticate(String token) → Optional<Task>`, `cleanup()`(@Scheduled), `static String sha256Hex(String)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskDbMcpTokenRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DbMcpTokenServiceTest {

    private final TaskDbMcpTokenRepository repo = mock(TaskDbMcpTokenRepository.class);
    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC);
    private final DbMcpTokenService svc = new DbMcpTokenService(repo, taskRepo, Duration.ofHours(2), clock);
    private Task task;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 42L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        when(taskRepo.findById(42L)).thenReturn(Optional.of(task));
        when(repo.save(any())).thenAnswer(inv -> {
            TaskDbMcpToken t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 7L);
            return t;
        });
    }

    private TaskDbMcpToken issued(String token) {
        ArgumentCaptor<TaskDbMcpToken> cap = ArgumentCaptor.forClass(TaskDbMcpToken.class);
        verify(repo).save(cap.capture());
        TaskDbMcpToken row = cap.getValue();
        when(repo.findByTokenHash(DbMcpTokenService.sha256Hex(token))).thenReturn(Optional.of(row));
        when(repo.findFirstByTaskIdOrderByIdDesc(42L)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void issue_stores_only_hash_with_stage_and_expiry() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        assertThat(token).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=");
        assertThat(row.getTokenHash()).isEqualTo(DbMcpTokenService.sha256Hex(token)).isNotEqualTo(token);
        assertThat(row.getStage()).isEqualTo("IMPLEMENTING");
        assertThat(row.getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plusHours(2));
    }

    @Test
    void authenticate_accepts_latest_token_in_same_stage() {
        String token = svc.issue(task);
        issued(token);
        assertThat(svc.authenticate(token)).contains(task);
    }

    @Test
    void authenticate_rejects_when_task_left_stage() {
        String token = svc.issue(task);
        issued(token);
        task.setStatus(TaskStatus.PR_CREATED);
        assertThat(svc.authenticate(token)).isEmpty();
    }

    @Test
    void older_token_is_rejected_after_reissue() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        TaskDbMcpToken newer = TaskDbMcpToken.issue(42L, "other", "IMPLEMENTING",
                OffsetDateTime.now(clock), OffsetDateTime.now(clock).plusHours(2));
        ReflectionTestUtils.setField(newer, "id", row.getId() + 1);
        when(repo.findFirstByTaskIdOrderByIdDesc(42L)).thenReturn(Optional.of(newer));
        assertThat(svc.authenticate(token)).isEmpty();
    }

    @Test
    void expired_or_unknown_or_blank_token_is_rejected() {
        String token = svc.issue(task);
        TaskDbMcpToken row = issued(token);
        ReflectionTestUtils.setField(row, "expiresAt", OffsetDateTime.now(clock));
        assertThat(svc.authenticate(token)).isEmpty();
        assertThat(svc.authenticate("nope")).isEmpty();
        assertThat(svc.authenticate("")).isEmpty();
        assertThat(svc.authenticate(null)).isEmpty();
    }

    @Test
    void deleted_task_is_rejected() {
        String token = svc.issue(task);
        issued(token);
        task.setDeletedAt(OffsetDateTime.now(clock));
        assertThat(svc.authenticate(token)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpTokenServiceTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`V28__task_db_connection.sql`:

```sql
-- 작업 DB 연결 선택 (스펙 2026-10-14 §3). id만 저장 — 접속정보는 실행 시점의 현재 행으로 해석한다.
ALTER TABLE com.task
    ADD COLUMN db_connection_ids JSONB NOT NULL DEFAULT '[]';

-- 디자인·구현 단계 DB MCP 작업 토큰. 평문은 저장하지 않는다(SHA-256 hex). stage = 발급 당시 TaskStatus.name().
CREATE TABLE com.task_db_mcp_token (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    stage       VARCHAR(32) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_task_db_mcp_token_task ON com.task_db_mcp_token(task_id, id DESC);
```

`Task.java` — `mcpsExtra` 필드 바로 아래에 추가(`@JdbcTypeCode`·`SqlTypes`·`ArrayList`는 이미 import됨):

```java
    /** 승인 때 고른 DB 연결 id (스펙 2026-10-14 §3). 접속정보는 실행 시점에 현재 행으로 해석한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "db_connection_ids", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<Long> dbConnectionIds = new ArrayList<>();
```

`TaskDbMcpToken.java`:

```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 디자인·구현 단계 DB MCP 작업 토큰 (스펙 2026-10-14 §6.2). 해시만 저장. */
@Entity
@Table(name = "task_db_mcp_token", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDbMcpToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public static TaskDbMcpToken issue(Long taskId, String tokenHash, String stage,
                                       OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        TaskDbMcpToken t = new TaskDbMcpToken();
        t.taskId = taskId;
        t.tokenHash = tokenHash;
        t.stage = stage;
        t.issuedAt = issuedAt;
        t.expiresAt = expiresAt;
        return t;
    }
}
```

`TaskDbMcpTokenRepository.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface TaskDbMcpTokenRepository extends JpaRepository<TaskDbMcpToken, Long> {

    Optional<TaskDbMcpToken> findByTokenHash(String tokenHash);

    Optional<TaskDbMcpToken> findFirstByTaskIdOrderByIdDesc(Long taskId);

    /** 만료됐거나 같은 작업의 더 새 토큰에 밀린 행 삭제. */
    @Modifying
    @Query(value = """
        DELETE FROM com.task_db_mcp_token t
        WHERE t.expires_at < :now
           OR t.id < (SELECT MAX(x.id) FROM com.task_db_mcp_token x WHERE x.task_id = t.task_id)
        """, nativeQuery = true)
    int deleteExpiredOrSuperseded(@Param("now") OffsetDateTime now);
}
```

`DbMcpTokenService.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import com.hamonsoft.netismaker.repository.TaskDbMcpTokenRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 디자인·구현 단계 DB MCP 작업 토큰 (스펙 2026-10-14 §6.2).
 * 유효 = 그 작업의 가장 최근 토큰 + 작업이 발급 당시 상태 그대로 + 만료 전 + 삭제되지 않은 작업.
 */
@Service
@Profile("api")
public class DbMcpTokenService {

    private static final Logger log = LoggerFactory.getLogger(DbMcpTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TaskDbMcpTokenRepository repo;
    private final TaskRepository taskRepo;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public DbMcpTokenService(TaskDbMcpTokenRepository repo, TaskRepository taskRepo,
                             @Value("${netismaker.db-mcp.token-ttl:2h}") Duration ttl) {
        this(repo, taskRepo, ttl, Clock.systemDefaultZone());
    }

    DbMcpTokenService(TaskDbMcpTokenRepository repo, TaskRepository taskRepo, Duration ttl, Clock clock) {
        this.repo = repo;
        this.taskRepo = taskRepo;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 새 토큰 발급 — 평문은 반환값으로만 나간다(저장·로그 금지). 같은 작업의 이전 토큰은 이 시점에 무효. */
    @Transactional
    public String issue(Task t) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        OffsetDateTime now = OffsetDateTime.now(clock);
        repo.save(TaskDbMcpToken.issue(t.getId(), sha256Hex(token), t.getStatus().name(), now, now.plus(ttl)));
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<Task> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<TaskDbMcpToken> row = repo.findByTokenHash(sha256Hex(token));
        if (row.isEmpty()) return Optional.empty();
        TaskDbMcpToken r = row.get();
        if (!r.getExpiresAt().isAfter(OffsetDateTime.now(clock))) return Optional.empty();
        Optional<TaskDbMcpToken> latest = repo.findFirstByTaskIdOrderByIdDesc(r.getTaskId());
        if (latest.isEmpty() || !latest.get().getId().equals(r.getId())) return Optional.empty();
        return taskRepo.findById(r.getTaskId())
                .filter(t -> t.getDeletedAt() == null && t.getStatus().name().equals(r.getStage()));
    }

    @Scheduled(cron = "${netismaker.db-mcp.token-cleanup-cron:0 40 3 * * *}")
    @Transactional
    public void cleanup() {
        int n = repo.deleteExpiredOrSuperseded(OffsetDateTime.now(clock));
        if (n > 0) log.info("DB MCP 작업 토큰 정리 {}건", n);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpTokenServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration/V28__task_db_connection.sql src/main/java/com/hamonsoft/netismaker/entity src/main/java/com/hamonsoft/netismaker/repository/TaskDbMcpTokenRepository.java src/main/java/com/hamonsoft/netismaker/dbmcp src/test/java/com/hamonsoft/netismaker/dbmcp
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): V28 작업 DB 연결 컬럼·작업 토큰(해시 저장, 최신·상태·만료 검증)"
```

---

### Task 6: `POST /mcp/db/{connectionId}` 컨트롤러·보안 체인

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/DbMcpController.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java` (새 체인 빈)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/DbMcpControllerTest.java`

**Interfaces:**
- Consumes: `DbMcpTokenService.authenticate(String) → Optional<Task>`, `DbConnectionService.resolveForClaim(List<? extends Number>) → ClaimDb(refs, notices)`, `DbMcpTools.toolDefinitions(String)`, `DbMcpTools.call(ref, tool, args) → ToolResult`
- Produces: HTTP 계약 — 인증 실패 401, 선택 안 된 연결 404, notification 202, GET 405, JSON-RPC 응답 `{"jsonrpc":"2.0","id":…,"result":…}` / `{"jsonrpc":"2.0","id":…,"error":{"code":…,"message":…}}`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.controller.DbMcpControllerTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`DbMcpController.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dbmcp.DbMcpTokenService;
import com.hamonsoft.netismaker.dbmcp.DbMcpTools;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 디자인·구현 단계 DB MCP (스펙 2026-10-14 §6.1). MCP Streamable HTTP의 stateless JSON 응답만 — SSE·세션 id 없음.
 * 인증은 Authorization: Bearer <작업 토큰> 하나(워커 API 키 아님). 보안 체인에서는 permitAll(SecurityConfig).
 * ⚠️ 접속정보(평문 비밀번호)와 결과값을 로그로 남기지 않는다 — 감사 로그는 메타데이터만.
 */
@RestController
@RequestMapping("/mcp/db")
@Profile("api")
public class DbMcpController {

    private static final Logger log = LoggerFactory.getLogger(DbMcpController.class);
    static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final Set<String> KNOWN_PROTOCOLS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DbMcpTokenService tokens;
    private final DbConnectionService dbConnections;
    private final DbMcpTools tools;

    public DbMcpController(DbMcpTokenService tokens, DbConnectionService dbConnections, DbMcpTools tools) {
        this.tokens = tokens;
        this.dbConnections = dbConnections;
        this.tools = tools;
    }

    @PostMapping("/{connectionId}")
    public ResponseEntity<Object> handle(@PathVariable long connectionId,
                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                         @RequestBody JsonNode body) {
        Optional<Task> task = tokens.authenticate(bearer(auth));
        if (task.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Long> ids = task.get().getDbConnectionIds();
        boolean selected = ids != null && ids.stream().anyMatch(n -> n != null && ((Number) n).longValue() == connectionId);
        if (!selected) return ResponseEntity.notFound().build();
        if (body == null || !body.isObject()) {
            return ResponseEntity.badRequest().body(error(null, -32600, "Invalid Request"));
        }
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        if (id == null || id.isNull()) return ResponseEntity.accepted().build(); // notification

        Object result = switch (method) {
            case "initialize" -> initialize(connectionId, body.path("params").path("protocolVersion").asText(""));
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", tools.toolDefinitions(labelOf(connectionId)));
            case "tools/call" -> callTool(task.get(), connectionId, body.path("params"));
            default -> null;
        };
        if (result == null) return ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("jsonrpc", "2.0");
        ok.put("id", id);
        ok.put("result", result);
        return ResponseEntity.ok(ok);
    }

    @RequestMapping(value = "/{connectionId}", method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> notAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    private Map<String, Object> initialize(long connectionId, String requested) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", KNOWN_PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL);
        r.put("capabilities", Map.of("tools", Map.of()));
        r.put("serverInfo", Map.of("name", "netismaker-db-" + connectionId, "version", "1.0.0"));
        return r;
    }

    private String labelOf(long connectionId) {
        DbConnectionService.ClaimDb db = dbConnections.resolveForClaim(List.of(connectionId));
        return db.refs().isEmpty() ? "DB #" + connectionId : db.refs().get(0).label();
    }

    private Map<String, Object> callTool(Task task, long connectionId, JsonNode params) {
        String name = params.path("name").asText("");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.path("arguments").isObject()
                ? JSON.convertValue(params.path("arguments"), Map.class) : Map.of();
        long start = System.currentTimeMillis();
        DbConnectionService.ClaimDb db = dbConnections.resolveForClaim(List.of(connectionId));
        DbMcpTools.ToolResult r;
        if (db.refs().isEmpty()) {
            r = new DbMcpTools.ToolResult(db.notices().isEmpty()
                    ? "DB 연결 #" + connectionId + "를 사용할 수 없습니다" : String.join(" / ", db.notices()), true);
        } else {
            DbConnectionRef ref = db.refs().get(0);
            r = tools.call(ref, name, args);
        }
        Object sql = args.get("sql");
        log.info("db-mcp task={} conn={} tool={} sql={} error={} chars={} ms={}", task.getId(), connectionId, name,
                sql instanceof String s ? (s.length() > 200 ? s.substring(0, 200) + "…" : s) : "-",
                r.isError(), r.text().length(), System.currentTimeMillis() - start);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", r.text())));
        out.put("isError", r.isError());
        return out;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("jsonrpc", "2.0");
        e.put("id", id);
        e.put("error", Map.of("code", code, "message", message));
        return e;
    }

    private static String bearer(String auth) {
        if (auth == null) return null;
        String a = auth.strip();
        return a.regionMatches(true, 0, "Bearer ", 0, 7) ? a.substring(7).strip() : null;
    }
}
```

`SecurityConfig.java` — `workerFilterChain` 빈 바로 위에 추가(`HttpMethod`·`SessionCreationPolicy`·`Order`는 이미 import됨):

```java
    /**
     * 디자인·구현 단계 DB MCP (스펙 2026-10-14 §6.1). 인증은 컨트롤러의 작업 토큰(Bearer)만 —
     * 워커 API 키를 claude 프로세스에 넘기지 않으려고 /worker/** 체인과 분리한다. api 체인(anyRequest)보다 앞서야 한다.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain dbMcpFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
```

그리고 클래스 상단 주석의 체인 표에 한 줄 추가: ` *   /mcp/**     ──► 작업 토큰(컨트롤러 검증, 스펙 2026-10-14 §6.1)`.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.controller.DbMcpControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/DbMcpController.java src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java src/test/java/com/hamonsoft/netismaker/controller/DbMcpControllerTest.java
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(dbmcp): POST /mcp/db/{id} stateless MCP 엔드포인트·전용 보안 체인"
```

---

### Task 7: 승인 시 DB 선택 저장·응답 필드

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/ApproveRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (생성자 + `startInterview`)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`createForTask`)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java` (필드 2개 + 생성 3곳)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewSummaryResponse.java`
- Modify(테스트 생성자): `src/test/java/com/hamonsoft/netismaker/service/TaskServiceAttachmentRollbackTest.java`, `TaskServiceDeployEnvTest.java`, `TaskServiceDeployTest.java`, `TaskServiceRepoCatalogTest.java`, `TaskServiceRequesterEmailTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceApproveDbTest.java`

**Interfaces:**
- Consumes: `DbConnectionService.validateSelection(Long repoCatalogId, String ownerId, List<Long> ids) → List<Long>`(위반 시 400 `TaskException`), `Task.setDbConnectionIds` (Task 5)
- Produces: `ApproveRequest(List<Long> mcpCatalogIds, String model, String effort, List<Long> dbConnectionIds)` + 3인자 생성자, `TaskResponse.repoCatalogId()`, `TaskResponse.dbConnectionIds()`, `InterviewSummaryResponse.dbConnectionIds()`

- [ ] **Step 1: 실패하는 테스트 작성**

먼저 `TaskServiceRequesterEmailTest.java`의 `setUp`을 열어 TaskService 생성 방식을 확인하고, 같은 방식으로 새 테스트를 만든다(생성자 마지막 인자에 `db`).

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 승인 때 DB 연결 선택 (스펙 2026-10-14 §4) — 검증 기준은 작업 등록자, 세션 생성 전에 저장. */
class TaskServiceApproveDbTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final McpCatalogService mcpCatalog = mock(McpCatalogService.class);
    private TaskService service;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(TaskStatusHistoryRepository.class), mcpCatalog, mock(RepoCatalogService.class),
                new ObjectMapper(), interviewService, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class), db);
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        task.setRepoCatalogId(9L);
        when(taskRepo.findActiveByIdForUpdate(5L)).thenReturn(Optional.of(task));
        when(mcpCatalog.resolveExtras(any())).thenReturn(List.of());
    }

    @Test
    void approve_validates_against_requester_and_saves_before_session() {
        when(db.validateSelection(9L, "user1", List.of(3L, 4L))).thenReturn(List.of(3L, 4L));

        service.approve(5L, "admin", new ApproveRequest(List.of(), null, null, List.of(3L, 4L)));

        assertThat(task.getDbConnectionIds()).containsExactly(3L, 4L);
        InOrder o = inOrder(db, interviewService);
        o.verify(db).validateSelection(9L, "user1", List.of(3L, 4L));
        o.verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList());
    }

    @Test
    void invalid_selection_is_400_and_no_session() {
        when(db.validateSelection(anyLong(), anyString(), anyList()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 7"));

        assertThatThrownBy(() -> service.approve(5L, "admin", new ApproveRequest(List.of(), null, null, List.of(7L))))
                .isInstanceOf(TaskException.class);
        verify(interviewService, never()).createForTask(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void legacy_three_arg_request_means_no_db() {
        when(db.validateSelection(9L, "user1", null)).thenReturn(new java.util.ArrayList<>());
        service.approve(5L, "admin", new ApproveRequest(List.of(), null, null));
        assertThat(task.getDbConnectionIds()).isEmpty();
    }
}
```

`TaskException`·`AttachmentStorage`·`DbConnectionService`·`McpCatalogService`·`RepoCatalogService`·`InterviewService`는 테스트와 같은 `service` 패키지라 import가 필요 없다. 리포지토리는 `com.hamonsoft.netismaker.repository.*`, 엔티티는 `entity.*`로 import된다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceApproveDbTest"`
Expected: 컴파일 실패(4인자 `ApproveRequest`·12인자 생성자 없음)

- [ ] **Step 3: 구현**

`ApproveRequest.java` 전체 교체:

```java
package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 관리자 승인 요청 바디. 승인 = 인터뷰 시작이므로 인터뷰가 쓸 모델/effort/MCP/DB 연결을 여기서 정한다.
 * 전부 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음 + DB 없음.
 * 레거시 경로(분석완료 → 구현대기) 승인에서는 무시된다.
 * dbConnectionIds: 스펙 2026-10-14 §4 — 검증 기준은 작업 등록자(최대 3개, 같은 레포, 활성, 공용이거나 등록자 본인 것).
 */
public record ApproveRequest(
        List<Long> mcpCatalogIds,
        String model,
        String effort,
        List<Long> dbConnectionIds
) {
    /** DB 선택 도입 전 3인자 — 기존 호출부·테스트 호환. */
    public ApproveRequest(List<Long> mcpCatalogIds, String model, String effort) {
        this(mcpCatalogIds, model, effort, null);
    }
}
```

`TaskService.java`:
1. 필드 `private final TaskStageUsageRepository stageUsageRepo;` 아래에 `private final DbConnectionService dbConnectionService;`
2. 생성자 마지막 인자로 `DbConnectionService dbConnectionService` 추가, 본문 끝에 `this.dbConnectionService = dbConnectionService;`
3. `startInterview`를 아래로 교체:

```java
    /** 승인대기 → 인터뷰중. 세션을 만들고 모델/effort/MCP/DB 선택을 task에도 박제한다. */
    private void startInterview(Task t, String adminId, ApproveRequest req) {
        List<TaskMcpSpec> extras = resolveMcpExtras(req == null ? null : req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req == null ? null : req.model());
        String effort = ModelEffortPolicy.resolveEffort(req == null ? null : req.effort());
        ModelEffortPolicy.validate(model, effort);   // 검증이 먼저 — 실패 시 세션이 생기면 안 된다
        // DB 연결 검증도 세션 생성 전 — 기준은 작업 등록자 (스펙 2026-10-14 §4)
        List<Long> dbIds = dbConnectionService.validateSelection(t.getRepoCatalogId(), t.getRequesterId(),
                req == null ? null : req.dbConnectionIds());

        t.setModel(model);
        t.setEffort(effort);
        t.setMcpsExtra(new ArrayList<>(extras));
        t.setDbConnectionIds(new ArrayList<>(dbIds));
        t.setFailureReason(null);
        interviewService.createForTask(t, model, effort, extras);

        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.INTERVIEWING);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.INTERVIEWING,
                "user", adminId, "관리자 승인 → 인터뷰 시작"));
    }
```

4. 5개 기존 테스트의 `new TaskService(…)` 호출 끝에 `, mock(DbConnectionService.class)` 추가(같은 패키지라 import 불필요, `mock`은 이미 static import). `TaskServiceApproveInterviewTest`는 `@SpringBootTest`라 수정 불필요.

`InterviewService.createForTask` — `s.setTaskId(t.getId());` 다음 줄에 추가:

```java
        // 작업 DB 연결 선택(스펙 2026-10-14 §5) — 인터뷰 claim이 이 id로 접속정보를 싣는다
        s.setDbConnectionIds(new ArrayList<>(t.getDbConnectionIds() == null ? List.of() : t.getDbConnectionIds()));
```

`TaskResponse.java`:
1. 레코드 컴포넌트 끝(`CloseView close` 다음)에 추가:

```java
        CloseView close,
        /** 레포 카탈로그 id — 승인 다이얼로그 DB 피커가 목록을 부를 때 쓴다 (스펙 2026-10-14 §4). 레거시 작업은 null. */
        Long repoCatalogId,
        /** 승인 때 고른 DB 연결 id (스펙 2026-10-14 §4). */
        List<Long> dbConnectionIds
```

(기존 `CloseView close` 줄의 `)`를 위처럼 바꾼다.)
2. `withTotalCost`의 `r.close()` 뒤에 `, r.repoCatalogId(), r.dbConnectionIds()` 추가.
3. `redactSecretValues`의 `attachments, stageUsage, totalCostUsd, totalTokens, close)` → `attachments, stageUsage, totalCostUsd, totalTokens, close, repoCatalogId, dbConnectionIds)`.
4. `build`의 `return new TaskResponse(…)` 마지막 인자(close 계산식) 뒤에 추가:

```java
                t.getRepoCatalogId(),
                t.getDbConnectionIds() == null ? List.of() : List.copyOf(t.getDbConnectionIds())
```

`InterviewSummaryResponse.java` 전체 레코드를 아래로 교체(필드 끝에 추가):

```java
public record InterviewSummaryResponse(
        Long id,
        String status,        // 한글 dbValue (사용자 표시)
        String statusName,    // 영문 enum name (InterviewStatus.name())
        String currentPhase,
        String model,
        String effort,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** 세션 DB 연결 id — 승인 다이얼로그가 마지막 설정을 프리필할 때 쓴다 (스펙 2026-10-14 §7). */
        List<Long> dbConnectionIds
) {
    public static InterviewSummaryResponse of(InterviewSession s) {
        return new InterviewSummaryResponse(
                s.getId(), s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(),
                s.getModel(), s.getEffort(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getDbConnectionIds() == null ? List.of() : List.copyOf(s.getDbConnectionIds()));
    }
}
```

(`import java.util.List;`이 없으면 추가.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceApproveDbTest" --tests "com.hamonsoft.netismaker.service.TaskServiceRequesterEmailTest" --tests "com.hamonsoft.netismaker.service.TaskServiceDeployTest" --tests "com.hamonsoft.netismaker.service.TaskServiceDeployEnvTest" --tests "com.hamonsoft.netismaker.service.TaskServiceRepoCatalogTest" --tests "com.hamonsoft.netismaker.service.TaskServiceAttachmentRollbackTest"`
Expected: PASS. 이어서 `./gradlew compileTestJava`로 `new TaskResponse(`·`new InterviewSummaryResponse(`를 쓰는 다른 테스트가 없는지 확인(있으면 끝 인자 추가).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker src/test/java/com/hamonsoft/netismaker/service
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(task): 승인 때 DB 연결 선택 저장(등록자 기준 검증)·응답에 repoCatalogId·dbConnectionIds"
```

---

### Task 8: 분석 인터뷰 claim에 DB 싣기

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java` (`of()`의 dbConnectionIds, 필드 주석)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java` (`withDb`)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerClaimDbTest.java`

**Interfaces:**
- Produces: INTERVIEW claim 응답의 `dbConnectionIds`·`dbConnections`·`dbNotices`가 QUESTION과 같은 의미로 채워진다(인터뷰 서비스 Task 11이 소비).

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 `InterviewWorkerControllerClaimDbTest` 클래스 끝에 추가:

```java
    @Test
    void interview_claim_with_db_ids_is_enriched() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(false, List.of(7L))));
        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(7L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnectionIds()).containsExactly(7L);
        assertThat(body.dbConnections()).containsExactly(ref);
    }

    @Test
    void interview_claim_survives_db_resolution_failure() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(false, List.of(7L))));
        when(db.resolveForClaim(any())).thenThrow(new RuntimeException("db down"));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).isEmpty();
        assertThat(body.dbNotices()).containsExactly("DB 접속정보를 불러오지 못해 DB 도구 없이 진행합니다");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest"`
Expected: 새 테스트 2건 FAIL(INTERVIEW는 `dbConnectionIds=[]`)

- [ ] **Step 3: 구현**

`InterviewClaimResponse.of(...)` 마지막 인자 줄을 바꾼다:

```java
                s.getDbConnectionIds() == null ? List.of() : List.copyOf(s.getDbConnectionIds()), List.of(), List.of());
```

필드 주석 `/** 세션이 선택한 DB 접속정보 id (스펙 2026-10-02 §5.4). QUESTION만, INTERVIEW는 []. */` → `/** 세션이 선택한 DB 접속정보 id (스펙 2026-10-02 §5.4, 2026-10-14 §5 — INTERVIEW도). */`. `DbConnectionRef` 주석의 "질문 세션 claim에서만" → "질문·인터뷰 세션 claim에서만".

`InterviewWorkerController.withDb` 교체:

```java
    /**
     * 질문·인터뷰 세션이면 DB 접속정보를 복호화해 싣는다 (스펙 2026-10-02 §5.4, 2026-10-14 §5). claim 트랜잭션은 이미
     * 커밋됐으므로 여기서 실패해도 응답을 깨지 않는다 — 500이면 세션이 RUNNING에 묶여 stale 회수까지 멈춘다.
     * ⚠️ 이 응답 본문(평문 비밀번호 포함)을 로그로 남기지 말 것.
     */
    private InterviewClaimResponse withDb(InterviewClaimResponse c) {
        if (c.dbConnectionIds().isEmpty()) return c;
        try {
            DbConnectionService.ClaimDb db = dbConnectionService.resolveForClaim(c.dbConnectionIds());
            return c.withDb(db.refs(), db.notices());
        } catch (RuntimeException e) {
            log.warn("claim DB 접속정보 조회 실패 session={}: {}", c.sessionId(), e.getClass().getSimpleName());
            String notice = "QUESTION".equals(c.kind())
                    ? "DB 접속정보를 불러오지 못해 DB 도구 없이 답합니다"
                    : "DB 접속정보를 불러오지 못해 DB 도구 없이 진행합니다";
            return c.withDb(List.of(), List.of(notice));
        }
    }
```

그다음 `grep -rn "INTERVIEW는 \[\]\|isQuestion() ? s.getDbConnectionIds" src/test`로 INTERVIEW가 `[]`라고 단언하는 기존 테스트를 찾아, 있으면 "INTERVIEW도 세션 값"으로 고친다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest" --tests "com.hamonsoft.netismaker.entity.InterviewSessionTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java src/test/java/com/hamonsoft/netismaker
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(interview): 분석 인터뷰 claim에도 DB 접속정보 싣기"
```

---

### Task 9: 구현·디자인 claim 보강(작업 토큰·서버 목록)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/DbMcpGrant.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java` (필드 `dbMcp` + `withDbMcp`)
- Create: `src/main/java/com/hamonsoft/netismaker/dbmcp/DbMcpGrantService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/WorkerController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dbmcp/DbMcpGrantServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/WorkerControllerDbMcpTest.java`

**Interfaces:**
- Consumes: `DbMcpTokenService.issue(Task)`, `DbConnectionService.resolveForClaim`, `DbConnectionService.SERVER_PREFIX = "db-"`
- Produces: `record DbMcpGrant(String token, List<Server> servers, List<String> notices)`, `record DbMcpGrant.Server(String name, String path, String label, String dbType)`; `WorkerTaskResponse.dbMcp()`, `WorkerTaskResponse.withDbMcp(DbMcpGrant)`; `DbMcpGrantService.grantFor(Long taskId) → DbMcpGrant`(선택 없음·대상 상태 아님이면 null)

- [ ] **Step 1: 실패하는 테스트 작성**

`DbMcpGrantServiceTest.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DbMcpGrantServiceTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final DbMcpTokenService tokens = mock(DbMcpTokenService.class);
    private final TaskStatusHistoryRepository history = mock(TaskStatusHistoryRepository.class);
    private final DbMcpGrantService svc = new DbMcpGrantService(taskRepo, db, tokens, history);
    private Task task;

    @BeforeEach
    void setUp() {
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.IMPLEMENTING);
        task.setDbConnectionIds(new ArrayList<>(List.of(12L, 13L)));
        when(taskRepo.findById(5L)).thenReturn(Optional.of(task));
        when(tokens.issue(task)).thenReturn("tok");
    }

    @Test
    void grant_lists_servers_without_password_and_issues_token() {
        var ref = new DbConnectionRef("db-12", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(12L, 13L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref),
                List.of("DB 연결 #13를 사용할 수 없습니다 — 삭제되었거나 비활성화됨")));

        DbMcpGrant g = svc.grantFor(5L);

        assertThat(g.token()).isEqualTo("tok");
        assertThat(g.servers()).containsExactly(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영 (MySQL)", "MYSQL"));
        assertThat(g.notices()).hasSize(1);
        assertThat(g.toString()).doesNotContain("tok").doesNotContain("pw");
        verify(history).save(any(TaskStatusHistory.class));
    }

    @Test
    void no_usable_connection_means_no_token() {
        when(db.resolveForClaim(any())).thenReturn(new DbConnectionService.ClaimDb(List.of(), List.of("꺼짐")));
        DbMcpGrant g = svc.grantFor(5L);
        assertThat(g.token()).isNull();
        assertThat(g.servers()).isEmpty();
        verify(tokens, never()).issue(any());
    }

    @Test
    void no_selection_or_other_stage_is_null() {
        task.setDbConnectionIds(new ArrayList<>());
        assertThat(svc.grantFor(5L)).isNull();
        task.setDbConnectionIds(new ArrayList<>(List.of(12L)));
        task.setStatus(TaskStatus.DEPLOYING);
        assertThat(svc.grantFor(5L)).isNull();
        verifyNoInteractions(db, tokens);
    }
}
```

`WorkerControllerDbMcpTest.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class WorkerControllerDbMcpTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final DbMcpGrantService grants = mock(DbMcpGrantService.class);
    private final WorkerController controller = new WorkerController(workerService, grants);

    private static WorkerTaskResponse implementation() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 5L);
        return WorkerTaskResponse.forImplementation(t, null, null);
    }

    @Test
    void implementation_claim_carries_grant() {
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(implementation()));
        DbMcpGrant g = new DbMcpGrant("tok", List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영", "MYSQL")), List.of());
        when(grants.grantFor(5L)).thenReturn(g);
        assertThat(controller.nextTask("w1").getBody().dbMcp()).isEqualTo(g);
    }

    @Test
    void claim_survives_grant_failure() {
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(implementation()));
        when(grants.grantFor(anyLong())).thenThrow(new RuntimeException("db down"));
        var body = controller.nextTask("w1").getBody();
        assertThat(body.dbMcp().token()).isNull();
        assertThat(body.dbMcp().notices()).containsExactly("DB 연결을 준비하지 못해 DB 도구 없이 진행합니다");
    }

    @Test
    void analysis_claim_is_untouched() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 6L);
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(WorkerTaskResponse.forAnalysis(t)));
        assertThat(controller.nextTask("w1").getBody().dbMcp()).isNull();
        verifyNoInteractions(grants);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpGrantServiceTest" --tests "com.hamonsoft.netismaker.controller.WorkerControllerDbMcpTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`DbMcpGrant.java`:

```java
package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 구현·디자인 claim의 DB MCP 지시 (스펙 2026-10-14 §6.4). 비밀번호 없음 — 워커는 path·token으로 API의 /mcp/db/{id}에 붙는다.
 * token이 null이면 쓸 수 있는 연결이 없다(notices만 프롬프트에). ⚠️ token은 로그 금지 — toString이 가린다.
 */
public record DbMcpGrant(String token, List<Server> servers, List<String> notices) {

    public record Server(String name, String path, String label, String dbType) {}

    public DbMcpGrant {
        servers = servers == null ? List.of() : List.copyOf(servers);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    @Override
    public String toString() {
        return "DbMcpGrant[token=" + (token == null ? "null" : "****") + ", servers=" + servers + ", notices=" + notices + "]";
    }
}
```

`WorkerTaskResponse.java`:
1. 컴포넌트 끝 `CloseArtifactSpec closeArtifact` 다음에 추가:

```java
        CloseArtifactSpec closeArtifact,
        /** IMPLEMENTATION·DESIGN 전용: DB MCP 지시 (스펙 2026-10-14 §6.4). WorkerController가 claim 커밋 뒤 채운다. */
        DbMcpGrant dbMcp
```

2. 기존 27인자 생성자 아래에 28인자 호환 생성자 추가(기존 23·27인자 생성자는 이 생성자로 이어진다):

```java
    /** DB MCP(dbMcp) 도입 전 28개 인자 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, null);
    }

    /** DB MCP 지시만 채운 사본. */
    public WorkerTaskResponse withDbMcp(DbMcpGrant grant) {
        return new WorkerTaskResponse(id, githubRepo, githubBranch, title, description, kind, mcpsExtra,
                analysisMarkdown, subtasksJson, headBranch, headSha, envVars, model, effort, designMarkdown,
                mockupFilesJson, feedbackHistoryJson, designUrl, designSystemProjectId, designOutputProjectId,
                gitUrl, repoHost, planMarkdown, prNumber, prUrl, closeProgress, closeContext, closeArtifact, grant);
    }
```

3. 클래스 주석의 생성자 설명 "Jackson은 레코드의 정규 생성자(28개)" → "(29개)".

`DbMcpGrantService.java`:

```java
package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.service.DbConnectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 구현·디자인 claim 보강 (스펙 2026-10-14 §6.2·§6.4). 쓸 수 있는 연결만 서버 목록에 넣고 그때만 토큰을 발급한다.
 * 비밀번호는 여기서 복호화되지만 응답(DbMcpGrant)에는 담지 않는다.
 */
@Service
@Profile("api")
public class DbMcpGrantService {

    private final TaskRepository taskRepo;
    private final DbConnectionService dbConnectionService;
    private final DbMcpTokenService tokenService;
    private final TaskStatusHistoryRepository historyRepo;

    public DbMcpGrantService(TaskRepository taskRepo, DbConnectionService dbConnectionService,
                             DbMcpTokenService tokenService, TaskStatusHistoryRepository historyRepo) {
        this.taskRepo = taskRepo;
        this.dbConnectionService = dbConnectionService;
        this.tokenService = tokenService;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public DbMcpGrant grantFor(Long taskId) {
        Task t = taskRepo.findById(taskId).orElse(null);
        if (t == null || t.getDbConnectionIds() == null || t.getDbConnectionIds().isEmpty()) return null;
        if (t.getStatus() != TaskStatus.IMPLEMENTING && t.getStatus() != TaskStatus.DESIGNING) return null;

        DbConnectionService.ClaimDb db = dbConnectionService.resolveForClaim(t.getDbConnectionIds());
        List<DbMcpGrant.Server> servers = db.refs().stream()
                .map(r -> new DbMcpGrant.Server(r.serverName(),
                        "/mcp/db/" + r.serverName().substring(DbConnectionService.SERVER_PREFIX.length()),
                        r.label(), r.dbType()))
                .toList();
        String token = servers.isEmpty() ? null : tokenService.issue(t);
        if (!db.notices().isEmpty()) {
            String reason = "DB 연결 일부 사용 불가: " + String.join(" / ", db.notices());
            historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(), "system", null,
                    reason.length() > 500 ? reason.substring(0, 500) : reason));
        }
        return new DbMcpGrant(token, servers, db.notices());
    }
}
```

`WorkerController.java`:
1. 필드·생성자에 `DbMcpGrantService` 추가, 로거 추가:

```java
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerController.class);

    private final WorkerService workerService;
    private final DbMcpGrantService dbMcpGrantService;

    public WorkerController(WorkerService workerService, DbMcpGrantService dbMcpGrantService) {
        this.workerService = workerService;
        this.dbMcpGrantService = dbMcpGrantService;
    }
```

2. `nextTask` 교체 + 보조 메서드:

```java
    @GetMapping("/next-task")
    public ResponseEntity<WorkerTaskResponse> nextTask(@RequestParam String workerId) {
        Optional<WorkerTaskResponse> claimed = workerService.claimNextTask(workerId).map(this::withDbMcp);
        return claimed.map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 구현·디자인 claim에 DB MCP 지시를 붙인다 (스펙 2026-10-14 §6.4). claim 트랜잭션은 이미 커밋됐으므로
     * 여기서 실패해도 claim 응답을 깨지 않는다 — DB 없이 진행.
     */
    private WorkerTaskResponse withDbMcp(WorkerTaskResponse r) {
        if (r.kind() != WorkerTaskResponse.Kind.IMPLEMENTATION && r.kind() != WorkerTaskResponse.Kind.DESIGN) return r;
        try {
            DbMcpGrant g = dbMcpGrantService.grantFor(r.id());
            return g == null ? r : r.withDbMcp(g);
        } catch (RuntimeException e) {
            log.warn("claim DB MCP 준비 실패 task={}: {}", r.id(), e.getClass().getSimpleName());
            return r.withDbMcp(new DbMcpGrant(null, List.of(), List.of("DB 연결을 준비하지 못해 DB 도구 없이 진행합니다")));
        }
    }
```

(import `com.hamonsoft.netismaker.dbmcp.DbMcpGrantService`, `com.hamonsoft.netismaker.dto.DbMcpGrant`, `java.util.List`.) `new WorkerController(` 를 쓰는 다른 테스트가 있으면 `grep -rn "new WorkerController(" src/test`로 찾아 `mock(DbMcpGrantService.class)`를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dbmcp.DbMcpGrantServiceTest" --tests "com.hamonsoft.netismaker.controller.WorkerControllerDbMcpTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceDesignClaimTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationPlanTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker src/test/java/com/hamonsoft/netismaker
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(worker-api): 구현·디자인 claim에 DB MCP 지시(서버 목록·작업 토큰) 보강"
```

---

### Task 10: 워커 데몬 — http DB MCP 연결·프롬프트 섹션

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskMcpSpec.java` (`headers`)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMcpSupport.java` (`headers` 기록)
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DbMcpWorkerSupport.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` (구현·디자인 exec 2곳)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DbMcpWorkerSupportTest.java`, `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMcpSupportHeadersTest.java`

**Interfaces:**
- Consumes: `WorkerTaskResponse.dbMcp()`, `DbMcpGrant` (Task 9), `WorkerProperties.apiBaseUrl()`
- Produces: `TaskMcpSpec(String name, String url, String transport, Map<String,String> headers)` + 3인자 생성자; `DbMcpWorkerSupport.withDbServers(List<TaskMcpSpec>, DbMcpGrant, String apiBaseUrl) → List<TaskMcpSpec>`, `DbMcpWorkerSupport.promptSection(DbMcpGrant) → String`

- [ ] **Step 1: 실패하는 테스트 작성**

`DbMcpWorkerSupportTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbMcpWorkerSupportTest {

    private final DbMcpGrant grant = new DbMcpGrant("tok",
            List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영 (MySQL)", "MYSQL")),
            List.of("DB 연결 #13를 사용할 수 없습니다"));

    @Test
    void appends_http_servers_with_bearer_header() {
        List<TaskMcpSpec> out = DbMcpWorkerSupport.withDbServers(
                List.of(new TaskMcpSpec("ctx7", "https://c", "http")), grant, "http://localhost:8090/");
        assertThat(out).hasSize(2);
        assertThat(out.get(1)).isEqualTo(new TaskMcpSpec("db-12", "http://localhost:8090/mcp/db/12", "http",
                Map.of("Authorization", "Bearer tok")));
    }

    @Test
    void no_grant_or_no_token_keeps_extras() {
        List<TaskMcpSpec> extras = List.of(new TaskMcpSpec("ctx7", "https://c", "http"));
        assertThat(DbMcpWorkerSupport.withDbServers(extras, null, "http://x")).isEqualTo(extras);
        assertThat(DbMcpWorkerSupport.withDbServers(null, new DbMcpGrant(null, List.of(), List.of("꺼짐")), "http://x")).isEmpty();
    }

    @Test
    void prompt_section_lists_tools_and_notices_without_token() {
        String s = DbMcpWorkerSupport.promptSection(grant);
        assertThat(s).contains("## 데이터베이스 조회 (읽기 전용)")
                .contains("- mcp__db-12__query / list_tables / describe_table : 운영 (MySQL)")
                .contains("참고: DB 연결 #13를 사용할 수 없습니다")
                .doesNotContain("tok");
        assertThat(DbMcpWorkerSupport.promptSection(null)).isEmpty();
    }
}
```

`WorkerMcpSupportHeadersTest.java` — `WorkerMcpSupport`의 생성자를 먼저 열어 확인하고(베이스 설정 경로를 받는 형태), 베이스 MCP가 없는 인스턴스를 만드는 기존 방식(`WorktreeImmediateDiscardTest`·`WorkerMainLoopDeployEnvTest`에서 `new WorkerMcpSupport(` 또는 `mock(` 사용 방식)을 따른다:

```java
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
```

`WorkerMcpSupport`는 `@PostConstruct init()`에서 `~/.claude.json`을 합치므로, 테스트에서 `init()`을 부르지 않으면 베이스 MCP가 없는 상태다(`mergedConfigPath == null`).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.DbMcpWorkerSupportTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMcpSupportHeadersTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`TaskMcpSpec.java` 전체 교체:

```java
package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 작업별 MCP 스펙 (snapshot). task.mcps_extra (jsonb) 배열의 한 요소.
 *
 * 카탈로그에서 선택된 시점의 name/url/transport를 박제 — 카탈로그가 나중에
 * 변경/삭제돼도 분석 시 사용된 정확한 설정이 audit/재현 가능.
 * headers: 워커가 실행 직전에만 만드는 http 서버용(DB MCP 작업 토큰, 스펙 2026-10-14 §6.4) — DB에는 저장하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskMcpSpec(
        String name,
        String url,
        String transport,
        Map<String, String> headers
) {
    public TaskMcpSpec(String name, String url, String transport) {
        this(name, url, transport, null);
    }

    /** ⚠️ headers에 토큰 — 가린다. */
    @Override
    public String toString() {
        return "TaskMcpSpec[name=" + name + ", url=" + url + ", transport=" + transport
                + (headers == null ? "" : ", headers=****") + "]";
    }
}
```

`WorkerMcpSupport.buildClaudeArgsForTask`의 extras 주입 루프를 교체:

```java
            // extras 주입 — name 충돌 시 extras 우선. headers는 DB MCP 작업 토큰(스펙 2026-10-14 §6.4)
            for (TaskMcpSpec spec : extras) {
                ObjectNode server = json.createObjectNode();
                server.put("type", spec.transport());
                server.put("url", spec.url());
                if (spec.headers() != null && !spec.headers().isEmpty()) {
                    server.set("headers", json.valueToTree(spec.headers()));
                }
                merged.set(spec.name(), server);
            }
```

`DbMcpWorkerSupport.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구현·디자인 단계 DB MCP (스펙 2026-10-14 §6.4). grant를 http MCP 스펙(토큰 헤더)으로 바꾸고 프롬프트 안내를 만든다.
 * 토큰은 임시 mcp-config 파일에만 실리고 ClaudeExecAdapter가 exec 뒤 지운다. 프롬프트에는 토큰을 넣지 않는다.
 */
final class DbMcpWorkerSupport {

    private DbMcpWorkerSupport() {}

    static List<TaskMcpSpec> withDbServers(List<TaskMcpSpec> extras, DbMcpGrant grant, String apiBaseUrl) {
        List<TaskMcpSpec> out = new ArrayList<>(extras == null ? List.of() : extras);
        if (grant == null || grant.token() == null || grant.servers().isEmpty()) return out;
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        for (DbMcpGrant.Server s : grant.servers()) {
            out.add(new TaskMcpSpec(s.name(), base + s.path(), "http",
                    Map.of("Authorization", "Bearer " + grant.token())));
        }
        return out;
    }

    static String promptSection(DbMcpGrant grant) {
        if (grant == null || (grant.servers().isEmpty() && grant.notices().isEmpty())) return "";
        StringBuilder sb = new StringBuilder("\n\n## 데이터베이스 조회 (읽기 전용)\n");
        if (!grant.servers().isEmpty() && grant.token() != null) {
            sb.append("이 작업에는 아래 DB 조회 도구가 붙어 있습니다. 스키마·데이터를 확인할 때 쓰세요. ")
              .append("SELECT 계열 단일 문장만 실행되고 최대 200행·30초 제한입니다. ")
              .append("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다.\n");
            for (DbMcpGrant.Server s : grant.servers()) {
                sb.append("- mcp__").append(s.name()).append("__query / list_tables / describe_table : ")
                  .append(s.label()).append('\n');
            }
        }
        for (String n : grant.notices()) sb.append("참고: ").append(n).append('\n');
        return sb.toString();
    }
}
```

`WorkerMainLoop.java`:
1. `processImplementation`의 `String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName());` →

```java
        String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName())
                + DbMcpWorkerSupport.promptSection(task.dbMcp());
```

그 아래 `claude.exec(prompt, wt.dir(), props.implementationTimeout(), task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(), …)`의 extras 인자를 교체:

```java
            exec = claude.exec(prompt, wt.dir(), props.implementationTimeout(),
                    DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl()),
                    true, task.model(), task.effort());
```

2. 디자인 쪽 `String prompt = renderDesignPrompt(task);` → `String prompt = renderDesignPrompt(task) + DbMcpWorkerSupport.promptSection(task.dbMcp());`, `claude.exec(prompt, wt, props.designTimeout(), …)`의 extras 인자도 같은 `DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl())`로 교체.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.DbMcpWorkerSupportTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMcpSupportHeadersTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDesignDirTest" --tests "com.hamonsoft.netismaker.service.QuestionServiceTest"`
Expected: PASS (QuestionServiceTest는 `TaskMcpSpec` 3인자 호환 확인용)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker src/test/java/com/hamonsoft/netismaker/workerdaemon
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(worker): 구현·디자인 claude에 API DB MCP(토큰 헤더) 연결·프롬프트 DB 안내"
```

---

### Task 11: 인터뷰 서비스 — INTERVIEW 세션 DB MCP

**Files:**
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts` (`allowedTools`에서 DB 서버 제외)
- Modify: `netismaker-interview-service/src/sdk/permissions.ts` (INTERVIEW 게이트 DB 분기)
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts` (INTERVIEW 분기 createDbMcp·close·프롬프트)
- Test: `netismaker-interview-service/test/sessionOptions.test.ts`, `test/permissions.test.ts`, `test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: INTERVIEW claim의 `dbConnections`·`dbNotices` (Task 8), 기존 `createDbMcp`, `dbSection`, `dbToolGate`
- Produces: INTERVIEW 세션 옵션 — `mcpServers`에 `db-N`, `allowedTools`에 `mcp__db-N` **없음**(canUseTool 경유), canUseTool이 `db-N`을 `dbToolGate`로

- [ ] **Step 1: 실패하는 테스트 작성**

`test/sessionOptions.test.ts`의 `describe('buildOptions', …)` 안에 추가:

```ts
  it('INTERVIEW: DB 서버는 사전승인하지 않는다 — canUseTool(dbToolGate)을 거쳐야 한다', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      dbMcpServers: { 'db-7': { type: 'sdk', name: 'db-7', instance: {} } as never },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect((o.mcpServers as Record<string, unknown>)['db-7']).toBeDefined();
    expect(o.allowedTools as string[]).not.toContain('mcp__db-7');
  });
```

`test/permissions.test.ts` 끝에 추가:

```ts
describe('INTERVIEW 게이트 — 작업 DB 도구 (스펙 2026-10-14 §5)', () => {
  const gate = buildCanUseTool('/tmp/repo', 'INTERVIEW', null, { 'db-7': 'mysql' });
  const call = (name: string, input: Record<string, unknown>) =>
    gate(name, input, { signal: new AbortController().signal } as never);

  it('조회문은 허용', async () => {
    expect((await call('mcp__db-7__query', { sql: 'SELECT 1' })).behavior).toBe('allow');
    expect((await call('mcp__db-7__list_tables', {})).behavior).toBe('allow');
  });

  it('쓰기 SQL·미등록 db-N은 거부', async () => {
    expect((await call('mcp__db-7__query', { sql: 'DELETE FROM t' })).behavior).toBe('deny');
    expect((await call('mcp__db-9__query', { sql: 'SELECT 1' })).behavior).toBe('deny');
  });

  it('다른 MCP는 기존대로 허용', async () => {
    expect((await call('mcp__ctx7__resolve', {})).behavior).toBe('allow');
  });
});
```

(파일 상단 import에 `buildCanUseTool`이 이미 있다. 세 번째 인자 시그니처가 `(toolName, input, options)`가 아니면 파일의 기존 호출 방식을 따른다.)

`test/interviewRunner.test.ts`의 `describe('DB MCP (스펙 2026-10-02 §6.4)', …)` 블록 안 끝에 추가:

```ts
    it('INTERVIEW에도 DB 서버를 붙이고 첫 프롬프트에 안내를 넣고, 끝나면 close한다 (스펙 2026-10-14 §5)', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => planCompleteStream());
      const prepared = preparedFor();
      const createDbMcp = vi.fn().mockReturnValue(prepared);
      const interviewDbClaim = { ...freshClaim, dbConnections: (dbClaim as { dbConnections: unknown }).dbConnections } as never;
      const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, createDbMcp } as never);
      await runner.run(interviewDbClaim);

      expect(createDbMcp).toHaveBeenCalledWith(interviewDbClaim);
      expect((captured.options.mcpServers as Record<string, unknown>)['db-7']).toBe(prepared.servers['db-7']);
      expect(captured.options.allowedTools as string[]).not.toContain('mcp__db-7');
      expect(captured.prompt.startsWith('사용 가능한 DB 도구')).toBe(true);
      expect(captured.prompt).toContain('PLANNING-ONLY');
      expect(prepared.close).toHaveBeenCalledTimes(1);
    });
```

- [ ] **Step 2: 실패 확인**

Run (in `netismaker-interview-service`): `npx vitest run test/sessionOptions.test.ts test/permissions.test.ts test/interviewRunner.test.ts`
Expected: 새 테스트 FAIL

- [ ] **Step 3: 구현**

`sessionOptions.ts` — `allowedTools` 식 교체:

```ts
    // INTERVIEW: mcp__<server>는 해당 서버의 모든 도구 매칭 — 워커의 --allowedTools 와일드카드와 동일.
    //   단 내장 DB 서버(db-<id>, 스펙 2026-10-14 §5)는 사전승인하지 않는다 — canUseTool의 dbToolGate(SQL 읽기 전용 검사)를 거치게.
    // QUESTION: 사전승인 없음 → Read/Grep/Glob/MCP 전부 canUseTool(default-deny + 경로 confinement) 단일 관문 경유.
    allowedTools: question
      ? []
      : [
          'Skill',
          'Read',
          'Grep',
          'Glob',
          ...Object.keys(merged)
            .filter((n) => !(input.dbMcpServers && n in input.dbMcpServers))
            .map((n) => `mcp__${n}`),
        ],
```

`permissions.ts`:
1. `dbToolGate`에 범위 문구 인자를 추가(기존 질문 메시지 유지):

```ts
function dbToolGate(
  toolName: string,
  tool: string,
  input: Record<string, unknown>,
  dialect: SqlDialect,
  scope = '질문 세션',
): PermissionResult {
  if (tool === 'query') {
    if (typeof input.sql !== 'string') return { behavior: 'deny', message: `${scope} DB 도구 입력에 SQL이 없습니다: ${toolName}` };
    const r = checkReadOnlySql(input.sql, dialect);
    return r.ok
      ? { behavior: 'allow' }
      : { behavior: 'deny', message: `${scope} DB 도구는 읽기 전용입니다 — ${r.reason}. SELECT 계열 단일 문장만 실행할 수 있습니다.` };
  }
  if (DB_READ_TOOLS.has(tool)) return { behavior: 'allow' };
  return { behavior: 'deny', message: `${scope}에서 허용되지 않은 DB 도구입니다: ${toolName}` };
}
```

2. `questionMcpGate` 위에 INTERVIEW용 보조 함수 추가:

```ts
/**
 * INTERVIEW 세션의 내장 DB 서버 게이트 (스펙 2026-10-14 §5). db-<id>가 아니면 null(기존 INTERVIEW 규칙 = allow).
 * 이번 턴에 붙이지 않은 db-N은 다른 소스가 이름을 흉내 낸 서버로 보고 deny.
 */
function interviewDbGate(
  toolName: string,
  input: Record<string, unknown>,
  dbServers: Record<string, SqlDialect>,
): PermissionResult | null {
  const rest = toolName.slice('mcp__'.length);
  const sepIdx = rest.indexOf('__');
  if (sepIdx === -1) return null;
  const server = rest.slice(0, sepIdx);
  const tool = rest.slice(sepIdx + 2);
  const dialect = dbServers[server];
  if (dialect) return dbToolGate(toolName, tool, input, dialect, '인터뷰');
  if (/^db-\d+$/.test(server)) return { behavior: 'deny', message: `이번 인터뷰에 붙이지 않은 DB 서버입니다: ${toolName}` };
  return null;
}
```

3. `buildCanUseTool`의 INTERVIEW 반환 함수에서 `if (toolName === 'Bash') return bashGate(input);` 다음 줄에 추가:

```ts
    if (toolName.startsWith('mcp__')) {
      const db = interviewDbGate(toolName, input, dbServers);
      if (db) return db;
    }
```

그리고 `questionMcpGate` 주석의 "INTERVIEW 게이트는 건드리지 않는다 — 여전히 `mcp__*` 무조건 allow." → "INTERVIEW 게이트는 내장 DB 서버(db-<id>)만 interviewDbGate로 검사하고 나머지 `mcp__*`는 allow."

`interviewRunner.ts`:
1. `promptFor`에 접두사 인자 추가 — 시그니처 `async function* promptFor(claim: InterviewClaimResponse, prefix = ''): AsyncIterable<UserTurn>`, fresh 분기 `yield userTurn(` 다음 첫 문자열 앞에 `prefix +`를 붙인다:

```ts
    yield userTurn(
      prefix +
        `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
```

2. `runTurn`(질문 조기 반환 `if (isQuestion) { … return; }` 바로 다음)에서 DB MCP를 만들고 그 아래 INTERVIEW 경로 전체를 `try { … } finally { await db.close(); }`로 감싼다:

```ts
      // 작업 DB MCP(스펙 2026-10-14 §5): 질문과 같은 내장 서버. 턴마다 새로 만들고 finally에서 닫는다.
      const db = (this.deps.createDbMcp ?? createDbMcp)(claim);
      try {
        const options = buildOptions({
          // …기존 인자 그대로…
          abortController: controller,
          dbMcpServers: db.servers,
          dbDialects: db.dialects,
        });
        // …기존 코드(첫 query·splice 재질의·harvest·postPlan/postQuestion)를 들여쓰기만 한 단계 늘려 그대로 둔다…
      } finally {
        await db.close();
      }
```

- 이 블록 안의 **나머지 `buildOptions({…})` 호출 2곳**(writing-plans splice 재질의, reformat 재질의)에도 `dbMcpServers: db.servers, dbDialects: db.dialects,`를 추가한다(`grep -n "buildOptions(" src/runner/interviewRunner.ts`로 위치 확인 — 질문 분기 것은 이미 있음).
- 첫 query의 `: promptFor(claim),`를 `: promptFor(claim, dbSection(db)),`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `npx vitest run` (in `netismaker-interview-service`)
Expected: 전부 PASS. 이어서 `npx tsc --noEmit`도 통과.

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src netismaker-interview-service/test
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(interview-service): 분석 인터뷰에 작업 DB MCP 연결(사전승인 제외·읽기 전용 게이트)"
```

---

### Task 12: 프론트 — 승인 다이얼로그 DB 피커·작업 상세 칩

**Files:**
- Modify: `frontend/components/tasks/ApproveTaskSummary.vue` (`SummaryTask` 타입)
- Modify: `frontend/components/ApproveDialog.vue`
- Create: `frontend/composables/taskDbChips.ts`
- Modify: `frontend/pages/tasks/[id].vue`, `frontend/components/tasks/TaskDetailMobile.vue`
- Test: `frontend/components/ApproveDialog.spec.ts`, `frontend/composables/taskDbChips.spec.ts`

**Interfaces:**
- Consumes: `TaskResponse.repoCatalogId`·`dbConnectionIds`, `InterviewSummaryResponse.dbConnectionIds` (Task 7), 기존 `fetchDbConnections`, `MAX_DB_SELECTION`, `dbTypeLabel`, `DbConnectionPicker`
- Produces: 승인 요청 바디 `{model, effort, mcpCatalogIds, dbConnectionIds?}` (기능 꺼짐이면 `dbConnectionIds` 없음); `dbChipLabels(ids: number[], items: DbConnectionView[]) → string[]`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/composables/taskDbChips.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { dbChipLabels } from './taskDbChips'

describe('dbChipLabels', () => {
  it('보이는 연결은 이름 · 종류, 안 보이는 연결은 DB #id', () => {
    const items = [
      { id: 3, name: '운영', dbType: 'MYSQL' },
      { id: 4, name: '분석', dbType: 'POSTGRESQL' },
    ] as never
    expect(dbChipLabels([4, 9, 3], items)).toEqual(['분석 · PostgreSQL', 'DB #9', '운영 · MySQL'])
  })
})
```

(`dbTypeLabel('MYSQL')`의 실제 라벨은 `DB_TYPES`를 보고 맞춘다 — 다르면 기대값을 그 라벨로.)

`frontend/components/ApproveDialog.spec.ts`:
1. `routeApi`의 opts에 `db?: unknown`을 추가하고 분기 추가:

```ts
    if (url.startsWith('/api/db-connections?repoCatalogId='))
      return Promise.resolve(opts.db ?? { enabled: false, items: [] })
```

2. describe 안 끝에 테스트 추가:

```ts
  const dbList = {
    enabled: true,
    items: [
      { id: 3, repoCatalogId: 9, scope: 'REPO', name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'ro', enabled: true, mine: false },
      { id: 4, repoCatalogId: 9, scope: 'USER', name: '내 개발', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'dev', username: 'me', enabled: true, mine: true },
    ],
  }

  it('DB 기능이 켜져 있으면 공용 연결을 기본 선택해 승인 바디에 싣는다', async () => {
    routeApi({ db: dbList })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-db"]')).not.toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([3])
    w.unmount()
  })

  it('마지막 인터뷰의 DB 선택이 있으면 그것을 프리필한다(목록에 있는 것만)', async () => {
    routeApi({
      db: dbList,
      interviews: [{ ...session(5, 'COMPLETED', '완료', 'claude-opus-5-5', 'high'), dbConnectionIds: [4, 99] }],
    })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([4])
    w.unmount()
  })

  it('DB 기능이 꺼져 있거나 레포 카탈로그가 없으면 피커가 없고 바디에도 없다', async () => {
    routeApi({ db: { enabled: false, items: [] } })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-db"]')).toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body).not.toHaveProperty('dbConnectionIds')
    w.unmount()
  })
```

(`session(…)`의 `statusName`이 `INTERVIEW_TERMINAL_STATUSES`에 들어가야 프리필된다 — 기존 프리필 테스트가 쓰는 값을 그대로 쓴다. `DbConnectionView` 필드명은 `composables/dbConnections.ts` 5행부터의 인터페이스에 맞춘다.)

- [ ] **Step 2: 실패 확인**

Run (in `frontend`): `npx vitest run components/ApproveDialog.spec.ts composables/taskDbChips.spec.ts`
Expected: 새 테스트 FAIL

- [ ] **Step 3: 구현**

`frontend/composables/taskDbChips.ts`:

```ts
import { dbTypeLabel, type DbConnectionView } from '~/composables/dbConnections'

/**
 * 작업 상세 DB 칩 라벨 (스펙 2026-10-14 §7). 보는 사람이 볼 수 없는 연결(남의 개인 접속정보 등)은 'DB #id'.
 */
export function dbChipLabels(ids: number[], items: Pick<DbConnectionView, 'id' | 'name' | 'dbType'>[]): string[] {
  const byId = new Map(items.map((i) => [i.id, i]))
  return ids.map((id) => {
    const c = byId.get(id)
    return c ? `${c.name} · ${dbTypeLabel(c.dbType)}` : `DB #${id}`
  })
}
```

`ApproveTaskSummary.vue`의 `SummaryTask`에 두 필드 추가:

```ts
  attachments: { id: number }[]
  /** 레포 카탈로그 id — DB 피커 목록 조회용 (스펙 2026-10-14 §7). 레거시 작업은 null/없음. */
  repoCatalogId?: number | null
  dbConnectionIds?: number[]
```

`ApproveDialog.vue`:
1. import 추가:

```ts
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import {
  MAX_DB_SELECTION,
  fetchDbConnections,
  type DbConnectionView,
} from '~/composables/dbConnections'
```

2. `SessionSummary`에 `dbConnectionIds?: number[] | null` 추가.
3. 상태 추가(`const mcpCatalogIds = ref<number[]>([])` 아래):

```ts
// DB 연결(스펙 2026-10-14 §7): 레포 공용 활성 연결을 기본 선택, 마지막 인터뷰 선택이 있으면 그것을 우선.
// 기능이 꺼져 있으면(enabled=false) 피커를 숨기고 승인 바디에도 싣지 않는다.
const db = reactive({ enabled: false, items: [] as DbConnectionView[] })
const dbConnectionIds = ref<number[]>([])

async function loadDb(mode: 'seed' | 'keep', preferred: number[] = []) {
  const repoId = props.task.repoCatalogId
  if (!repoId) {
    db.enabled = false
    db.items = []
    return
  }
  const r = await fetchDbConnections(repoId)
  db.enabled = r.enabled
  db.items = r.items
  const usable = new Set(r.items.filter((i) => i.enabled).map((i) => i.id))
  if (mode === 'keep') {
    dbConnectionIds.value = dbConnectionIds.value.filter((id) => usable.has(id))
  } else if (preferred.length) {
    dbConnectionIds.value = preferred.filter((id) => usable.has(id)).slice(0, MAX_DB_SELECTION)
  } else {
    dbConnectionIds.value = r.items
      .filter((i) => i.scope === 'REPO' && i.enabled)
      .slice(0, MAX_DB_SELECTION)
      .map((i) => i.id)
  }
}
function reloadDb() {
  loadDb('keep')
}
```

4. `init()` — 초기화 줄에 `dbConnectionIds.value = []` 추가하고, 지난 인터뷰 조회 try/catch **뒤**에 추가:

```ts
  await loadDb('seed', lastDbIds)
```

`lastDbIds`는 try 블록 안에서 채운다 — `init` 맨 위에 `let lastDbIds: number[] = []`를 두고, `const last = rows[0]` 다음 줄에 `lastDbIds = Array.isArray(last?.dbConnectionIds) ? last.dbConnectionIds : []`.

5. `approve()`의 body 교체:

```ts
      body: {
        model: model.value,
        effort: effort.value,
        mcpCatalogIds: mcpCatalogIds.value,
        ...(db.enabled ? { dbConnectionIds: dbConnectionIds.value } : {}),
      },
```

6. 템플릿 — `<McpPicker v-model="mcpCatalogIds" flat />`를 감싼 `div` 다음에 추가:

```html
          <div v-if="db.enabled && task.repoCatalogId" class="q-mt-md" data-test="approve-db">
            <div class="text-caption text-grey-7 q-mb-xs">
              DB 연결 — 인터뷰·디자인·구현에서 읽기 전용으로 조회합니다
            </div>
            <DbConnectionPicker
              v-model="dbConnectionIds"
              :items="db.items"
              :repo-catalog-id="task.repoCatalogId"
              @reload="reloadDb"
            />
          </div>
```

`pages/tasks/[id].vue`:
1. 작업 타입(99행 근처 `repoAlias: string | null`이 있는 인터페이스)에 `repoCatalogId?: number | null`, `dbConnectionIds?: number[]` 추가.
2. script에 칩 상태 추가:

```ts
import { fetchDbConnections } from '~/composables/dbConnections'
import { dbChipLabels } from '~/composables/taskDbChips'

const dbChips = ref<string[]>([])
watch(
  () => [task.value?.repoCatalogId, task.value?.dbConnectionIds?.join(',')] as const,
  async ([repoId]) => {
    const ids = task.value?.dbConnectionIds ?? []
    if (!ids.length) {
      dbChips.value = []
      return
    }
    const r = repoId ? await fetchDbConnections(repoId) : { enabled: false, items: [] }
    dbChips.value = dbChipLabels(ids, r.items)
  },
  { immediate: true },
)
```

(`task`가 `ref`가 아니라 다른 형태면 파일의 기존 `task` 접근 방식에 맞춘다.)
3. 템플릿 — MCP 칩 `q-card-section`이 끝나는 `</q-card-section>` 다음에 추가:

```html
        <q-separator v-if="dbChips.length" />
        <q-card-section v-if="dbChips.length" data-test="task-db-chips">
          <div class="text-caption q-mb-xs">DB 연결 (읽기 전용 조회)</div>
          <q-chip
            v-for="label in dbChips"
            :key="label"
            color="teal-1"
            text-color="teal-9"
            icon="storage"
            size="sm"
            dense
            :label="label"
            class="q-mr-xs q-mb-xs"
          />
        </q-card-section>
```

`TaskDetailMobile.vue`:
1. 작업 prop 타입(25행 `mcpsExtra` 근처)에 `dbConnectionIds?: number[]` 추가.
2. 229행 MCP 줄 다음에 추가:

```html
              <div v-if="task.dbConnectionIds?.length"><span class="k">DB</span>{{ task.dbConnectionIds.length }}개 연결 (읽기 전용)</div>
```

- [ ] **Step 4: 통과 확인**

Run (in `frontend`): `npx vitest run` 그리고 `npx nuxi typecheck`
Expected: PASS. (worktree에서 돌리려면 `frontend/node_modules`를 메인 체크아웃 `frontend/node_modules`로의 정션으로 만든다 — PowerShell `New-Item -ItemType Junction -Path frontend\node_modules -Target C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules`. `npm ci`·`npm install` 금지.)

- [ ] **Step 5: 커밋**

```bash
git add frontend/components frontend/composables frontend/pages
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "feat(front): 승인 다이얼로그 DB 연결 선택·작업 상세 DB 칩"
```

---

### Task 13: 전체 검증·터널 확인·스펙 정리

**Files:**
- Modify: `docs/superpowers/specs/2026-10-14-task-db-connection-design.md` (구현 중 확정된 세부 반영)

- [ ] **Step 1: 백엔드 전체 테스트**

Run: `./gradlew test`
Expected: 실패는 원래 실패 3건(`AttachmentCleanupJobTest`·`AttachmentStorageTest` 심볼릭 링크)뿐. 그 밖의 실패가 있으면 고치고 다시 돌린다.

- [ ] **Step 2: 인터뷰 서비스·프론트 전체 테스트**

Run: `cd netismaker-interview-service && npx vitest run && npx tsc --noEmit`
Run: `cd frontend && npx vitest run && npx nuxi typecheck`
Expected: PASS

- [ ] **Step 3: 터널 노출 확인(스펙 §6.5)** — 본 체크아웃 `C:\Users\mic\NetisMaker\commanCenter`의 cloudflared 설정을 찾는다:

Run: `grep -rn "ingress\|service:" C:/Users/mic/.cloudflared/*.yml C:/Users/mic/NetisMaker/commanCenter/scripts/*.yml 2>/dev/null`

- API(8090)가 경로 제한 없이 공개돼 있으면 결과를 PR 본문 "공개 노출" 항목에 적고, 터널 설정 변경은 **사용자 확인 후** 별도로 한다(라이브 설정이라 이 브랜치에서 바꾸지 않는다).
- API가 공개돼 있지 않으면(프론트만 공개 + 프록시 경로 제한) 그 사실을 PR에 적는다.

- [ ] **Step 4: 스펙 정리** — 구현과 달라진 세부를 스펙에 반영한다:
  - §3: `stage`는 `TaskStatus.name()`(예: `IMPLEMENTING`), 정리는 `DbMcpTokenService.cleanup()`(`netismaker.db-mcp.token-cleanup-cron`, 기본 매일 03:40).
  - §4: `TaskResponse`에는 `repoCatalogId`·`dbConnectionIds`만 추가하고, 칩 라벨은 프론트가 `/api/db-connections` 목록으로 만든다(보이지 않는 연결은 `DB #id`).
  - §6.3: 감사 로그는 행 수 대신 결과 길이(`chars`)를 남긴다. JDBC 실행은 3종 공통 `SET TRANSACTION READ ONLY` + `setReadOnly(true)`.
  - §6.4: 워커는 `TaskMcpSpec.headers`(DB에는 저장 안 함, `@JsonInclude(NON_NULL)`)로 토큰 헤더를 mcp-config에 쓴다.

- [ ] **Step 5: 커밋**

```bash
git add docs/superpowers/specs/2026-10-14-task-db-connection-design.md
git -c user.name=khlee84 -c user.email=khlee84@hamonsoft.co.kr commit -m "docs(spec): 작업 DB 연결 — 구현 중 확정된 세부 반영"
```

- [ ] **Step 6: 라이브 검증 계획 메모(병합 후, 사용자와 함께)** — PR 본문에 체크리스트로 남긴다:
  1. 실제 사내 DB 연결(레포 공용)로 작업 하나 승인 → 승인 다이얼로그에서 기본 선택 확인.
  2. 인터뷰 활동 스트림에서 `mcp__db-N__list_tables`·`query` 호출 확인.
  3. 구현 단계 워커 로그에서 `mcp__db-N__query` 호출과 API 로그 `db-mcp task=… conn=…` 확인.
  4. 프롬프트로 쓰기 SQL을 유도했을 때 `isError`로 거부되는지 API 로그에서 확인.
  5. 구현이 끝난 뒤(PR생성) 같은 토큰으로 호출하면 401인지 `curl`로 확인.
