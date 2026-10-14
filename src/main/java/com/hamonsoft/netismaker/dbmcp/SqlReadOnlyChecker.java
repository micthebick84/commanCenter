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

    private record Scan(String out, int lastSemicolon, String error) {
        static Scan err(String e) { return new Scan(null, -1, e); }
    }

    private SqlReadOnlyChecker() {}

    public static Check check(String sql, SqlDialect d) {
        Scan s = scan(sql == null ? "" : sql, d);
        if (s.error() != null) return Check.fail(s.error());
        String body = jsTrim(stripTrailingSemi(jsTrim(s.out())));
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
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '$' || c == '#' || c >= 0x80;
    }

    /** JS 정규식 \s / String.trim과 같은 공백 집합 (Java strip·\s와 달리 NBSP·BOM 등 유니코드 공백 포함). */
    private static boolean isJsSpace(int c) {
        return c >= 0 && ((c >= 9 && c <= 13) || c == 32 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
                || c == 0x205F || c == 0x3000 || c == 0xFEFF);
    }

    private static String jsTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isJsSpace(s.charAt(a))) a++;
        while (b > a && isJsSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** JS replace(/;\s*$/, '') — 끝의 ';'와 그 뒤 공백을 한 번 뗀다. */
    private static String stripTrailingSemi(String s) {
        int b = s.length();
        while (b > 0 && isJsSpace(s.charAt(b - 1))) b--;
        return b > 0 && s.charAt(b - 1) == ';' ? s.substring(0, b - 1) : s;
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
