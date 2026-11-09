package com.hamonsoft.netismaker.dbmcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 쓰기 SQL 토큰 분석 — 주석은 버리고, 인용 여부와 원문 위치를 남긴다.
 * 거부 규칙은 읽기 스캐너(SqlReadOnlyChecker.scan)와 같다: MySQL 실행 주석, Oracle q'' 인용, 백슬래시 든 문자열·식별자,
 * 닫히지 않은 따옴표·주석·달러 인용. 쓰기에는 더 엄하게 다음도 거부한다 — DB마다 경계를 다르게 볼 수 있는 것들이다.
 * <ul>
 *   <li>중첩 블록 주석(PostgreSQL은 중첩을 세고 MySQL·Oracle은 세지 않는다)</li>
 *   <li>PostgreSQL·MySQL 힌트 주석 {@code /*+ … *}{@code /}(pg_hint_plan의 Set, MySQL SET_VAR가 설정을 바꾼다)</li>
 *   <li>따옴표 밖의 ASCII가 아닌 문자·제어 문자(유니코드 공백을 DB마다 다르게 본다)</li>
 *   <li>따옴표 밖의 단독 CR(뒤에 LF가 없는 \r) — PostgreSQL 서버와 pgjdbc는 {@code --} 주석을 CR에서도 끝내고
 *       MySQL은 LF에서만 끝낸다. CR에서 끊든 넘기든 어느 한 DB와는 문장이 달라지므로(주석 뒤의 {@code ;}·이름이 살아나거나
 *       WHERE가 숨는다) 끊지 않고 거부한다. 공백 자리·블록 주석 안도 같은 규칙으로 막는다.</li>
 * </ul>
 */
final class SqlWriteTokenizer {

    enum Type { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL, DOLLAR_BODY }

    /** text: 단어는 원문, 인용 식별자·문자열은 겹따옴표를 푼 내용, 기호는 한 글자. start·end는 원문 위치. */
    record Token(Type type, String text, int start, int end) {

        boolean is(String upperWord) {
            return type == Type.WORD && text.equalsIgnoreCase(upperWord);
        }

        boolean isSymbol(char c) {
            return type == Type.SYMBOL && text.charAt(0) == c;
        }

        boolean isIdent() {
            return type == Type.WORD || type == Type.QUOTED_IDENT;
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    private static final String LONE_CR ="따옴표 밖의 단독 CR(뒤에 LF가 없는 \\r)은 허용하지 않습니다 — 줄 끝은 LF나 CRLF로 쓰세요";

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    private SqlWriteTokenizer() {}

    static List<Token> tokenize(String sql, SqlDialect d) throws SqlWriteRejected {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char ch = sql.charAt(i);
            int next = at(sql, i + 1);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                if (ch == '\r' && next != '\n') throw new SqlWriteRejected(LONE_CR);
                i++;
                continue;
            }
            // -- 주석 (MySQL은 뒤에 공백·제어 문자가 있어야 주석)
            if (ch == '-' && next == '-') {
                int after = at(sql, i + 2);
                if (d != SqlDialect.MYSQL || after < 0 || after <= ' ') {
                    i = lineEnd(sql, i);
                    continue;
                }
            }
            if (ch == '#' && d == SqlDialect.MYSQL) {
                i = lineEnd(sql, i);
                continue;
            }
            if (ch == '/' && next == '*') {
                i = skipBlockComment(sql, i, d);
                continue;
            }
            if (d == SqlDialect.ORACLE && ch == '\'' && isOracleAltQuote(sql, i)) {
                throw new SqlWriteRejected("Oracle q'[...'] 대안 인용은 허용하지 않습니다");
            }
            if (ch == '\'' || (ch == '"' && d == SqlDialect.MYSQL)) {
                int end = quotedEnd(sql, i, ch);
                out.add(new Token(Type.STRING, unquote(sql, i, end, ch), i, end));
                i = end;
                continue;
            }
            if (ch == '"' || (ch == '`' && d == SqlDialect.MYSQL)) {
                int end = quotedEnd(sql, i, ch);
                String text = unquote(sql, i, end, ch);
                if (text.isEmpty()) throw new SqlWriteRejected("빈 따옴표 식별자는 허용하지 않습니다");
                out.add(new Token(Type.QUOTED_IDENT, text, i, end));
                i = end;
                continue;
            }
            if (ch == '$' && d == SqlDialect.POSTGRES && !isIdentChar(at(sql, i - 1))) {
                Matcher m = DOLLAR_TAG.matcher(sql).region(i, n);
                if (m.lookingAt()) {
                    String tag = m.group();
                    int close = sql.indexOf(tag, i + tag.length());
                    if (close == -1) throw new SqlWriteRejected("닫히지 않은 달러 인용");
                    int end = close + tag.length();
                    out.add(new Token(Type.DOLLAR_BODY, sql.substring(i + tag.length(), close), i, end));
                    i = end;
                    continue;
                }
            }
            if (isWordStart(ch)) {
                int j = i + 1;
                while (j < n && isWordPart(sql.charAt(j), d)) j++;
                out.add(new Token(Type.WORD, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (isDigit(ch) || (ch == '.' && isDigit(next))) {
                int j = i + 1;
                while (j < n && (isAsciiLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '.')) j++;
                out.add(new Token(Type.NUMBER, sql.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (ch >= 0x80) {
                throw new SqlWriteRejected("따옴표 밖에는 ASCII 문자만 쓸 수 있습니다 — 한글 등 다른 문자 이름은 따옴표로 감싸세요");
            }
            if (ch < 0x20 || ch == 0x7F) throw new SqlWriteRejected("제어 문자는 허용하지 않습니다");
            out.add(new Token(Type.SYMBOL, String.valueOf(ch), i, i + 1));
            i++;
        }
        return out;
    }

    private static int skipBlockComment(String sql, int i, SqlDialect d) throws SqlWriteRejected {
        int c2 = at(sql, i + 2);
        if (d == SqlDialect.MYSQL && (c2 == '!' || (c2 == 'M' && at(sql, i + 3) == '!'))) {
            throw new SqlWriteRejected("MySQL 실행 주석(/*! */)은 허용하지 않습니다");
        }
        if (c2 == '+' && d != SqlDialect.ORACLE) {
            throw new SqlWriteRejected("힌트 주석(/*+ */)은 허용하지 않습니다");
        }
        int close = sql.indexOf("*/", i + 2);
        if (close == -1) throw new SqlWriteRejected("닫히지 않은 주석");
        int nested = sql.indexOf("/*", i + 2);
        if (nested != -1 && nested < close) throw new SqlWriteRejected("중첩 주석은 허용하지 않습니다");
        rejectLoneCr(sql, i + 2, close);
        return close + 2;
    }

    /** 읽기 스캐너와 같은 판정: 앞 글자가 q(또는 nq)이고 그 앞이 식별자 글자가 아니면 대안 인용. */
    private static boolean isOracleAltQuote(String sql, int i) {
        int prev = at(sql, i - 1);
        if (prev != 'q' && prev != 'Q') return false;
        int pp = at(sql, i - 2);
        if (pp == 'n' || pp == 'N') return !isIdentChar(at(sql, i - 3));
        return !isIdentChar(pp);
    }

    /** 닫는 따옴표 다음 위치. 겹따옴표는 이스케이프, 백슬래시는 거부(이스케이프로 읽는 DB·설정이 있다). */
    private static int quotedEnd(String sql, int start, char quote) throws SqlWriteRejected {
        int j = start + 1;
        while (j < sql.length()) {
            char c = sql.charAt(j);
            if (c == '\\') throw new SqlWriteRejected("백슬래시가 든 문자열/식별자는 허용하지 않습니다");
            if (c == quote) {
                if (at(sql, j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        throw new SqlWriteRejected("닫히지 않은 문자열/식별자");
    }

    private static String unquote(String sql, int start, int end, char quote) {
        String q = String.valueOf(quote);
        return sql.substring(start + 1, end - 1).replace(q + q, q);
    }

    /** 줄 주석의 끝(LF 위치, 없으면 문자열 끝). 그 사이에 단독 CR이 있으면 DB마다 주석 끝이 달라 거부한다. */
    private static int lineEnd(String sql, int i) throws SqlWriteRejected {
        int e = sql.indexOf('\n', i);
        int end = e == -1 ? sql.length() : e;
        rejectLoneCr(sql, i, end);
        return end;
    }

    /** [from, to) 안에 뒤에 LF가 없는 CR이 있으면 거부. */
    private static void rejectLoneCr(String sql, int from, int to) throws SqlWriteRejected {
        for (int k = sql.indexOf('\r', from); k != -1 && k < to; k = sql.indexOf('\r', k + 1)) {
            if (at(sql, k + 1) != '\n') throw new SqlWriteRejected(LONE_CR);
        }
    }

    /** JS 문자열 인덱스처럼 범위 밖은 -1. */
    private static int at(String s, int i) {
        return i >= 0 && i < s.length() ? s.charAt(i) : -1;
    }

    /** 읽기 스캐너(SqlReadOnlyChecker.isIdentChar)와 같은 집합 — 달러 인용·q'' 판정에 쓴다. */
    private static boolean isIdentChar(int c) {
        if (c < 0) return false;
        return isAsciiLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c >= 0x80;
    }

    private static boolean isWordStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    /** 비인용 식별자 글자: 영숫자·_·$, Oracle은 #도(MySQL에서 #는 주석, PostgreSQL에서는 연산자). */
    private static boolean isWordPart(char c, SqlDialect d) {
        return isAsciiLetterOrDigit(c) || c == '_' || c == '$' || (c == '#' && d == SqlDialect.ORACLE);
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetterOrDigit(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isDigit(c);
    }
}
