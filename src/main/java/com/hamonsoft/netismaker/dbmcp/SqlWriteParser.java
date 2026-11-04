package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dbmcp.SqlWriteTokenizer.Token;
import com.hamonsoft.netismaker.dbmcp.SqlWriteTokenizer.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 쓰기 SQL 한 문장 분석기 (스펙 2026-11-03 §6.2·§10).
 * SQL 파서 라이브러리 대신 자체 토큰 분석으로 <b>받는 문법만</b> 읽고, 그 밖은 전부 거부한다(fail closed) —
 * 모르는 문법을 아는 문장으로 잘못 읽으면 남의 테이블을 소유 테이블로, 승인 대기를 바로 실행으로 분류할 수 있다.
 * 트리거·프로시저·함수·패키지는 머리만 보고 본문은 분석하지 않는다(늘 승인 대기 — 관리자가 원문을 보고 실행).
 */
public final class SqlWriteParser {

    public static final int MAX_SQL_CHARS = 100_000;

    static final String EMPTY = "빈 문장";
    static final String MULTI_STATEMENT = "여러 문장은 실행할 수 없습니다";
    static final String UNBALANCED = "괄호가 맞지 않습니다";
    static final String CASCADE = "CASCADE는 다른 객체까지 지울 수 있어 받지 않습니다 — CASCADE 없이 실행하세요";
    static final String USER_ROLE = "사용자·역할 변경(USER/ROLE)은 허용하지 않습니다";
    static final String MULTI_TABLE = "다중 테이블 UPDATE/DELETE는 분석할 수 없습니다 — WHERE … IN (SELECT …)로 바꿔 쓰세요";
    static final String ATTACHED_TABLE = "다른 테이블에 붙는 테이블(PARTITION OF·INHERITS)은 만들 수 없습니다";
    static final String ADD_INDEX = "ALTER TABLE … ADD INDEX는 받지 않습니다 — CREATE INDEX를 쓰세요";
    static final String DEFINER = "DEFINER 지정은 트리거·프로시저·함수에만 받습니다 — 빼고 실행하세요";
    static final String UNKNOWN_ALTER_ACTION = "분석할 수 없는 ALTER TABLE 동작: ";

    private static final int PG_MAX_IDENT_BYTES = 63;

    /** DML·CTAS·뷰 본문 안에 나오면 다른 문장을 품은 것으로 보고 거부하는 단어. */
    private static final Set<String> NESTED_STATEMENT_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT",
            "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "CALL", "EXEC", "EXECUTE", "COPY", "LOCK", "UNLOCK", "LOAD",
            "HANDLER", "PREPARE", "DEALLOCATE", "LISTEN", "NOTIFY", "VACUUM", "REINDEX", "CLUSTER", "REFRESH",
            "DISCARD", "KILL", "SHUTDOWN", "PURGE", "FLUSH", "OPTIMIZE", "REPAIR", "OUTFILE", "DUMPFILE");

    /**
     * 남의 테이블에 바로 실행할 수 있는 ADD COLUMN의 타입(첫 단어). 이 밖의 단어면 컬럼 추가로 읽지 않는다 —
     * ADD PARTITION·ADD SYSTEM VERSIONING·ADD SUPPLEMENTAL LOG처럼 컬럼이 아닌 ADD를 컬럼 추가로 오분류하지 않게.
     */
    private static final Set<String> COLUMN_TYPES = Set.of(
            "INT", "INTEGER", "SMALLINT", "BIGINT", "TINYINT", "MEDIUMINT", "INT2", "INT4", "INT8",
            "SERIAL", "SMALLSERIAL", "BIGSERIAL", "SERIAL2", "SERIAL4", "SERIAL8",
            "NUMERIC", "DECIMAL", "DEC", "NUMBER", "FLOAT", "FLOAT4", "FLOAT8", "REAL", "DOUBLE",
            "BINARY_FLOAT", "BINARY_DOUBLE", "MONEY", "BOOLEAN", "BOOL", "BIT", "VARBIT",
            "CHAR", "CHARACTER", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "BPCHAR", "CITEXT",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "LONG",
            "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BYTEA", "RAW", "BINARY", "VARBINARY", "BFILE",
            "DATE", "TIME", "TIMETZ", "TIMESTAMP", "TIMESTAMPTZ", "DATETIME", "YEAR", "INTERVAL",
            "UUID", "JSON", "JSONB", "XML", "XMLTYPE", "ENUM", "INET", "CIDR", "MACADDR", "TSVECTOR",
            "GEOMETRY", "POINT", "ROWID", "UROWID");

    private static final Set<String> ADD_CONSTRAINT_WORDS = Set.of("CONSTRAINT", "UNIQUE", "PRIMARY", "FOREIGN", "CHECK", "EXCLUDE");
    private static final Set<String> ADD_INDEX_WORDS = Set.of("INDEX", "KEY", "FULLTEXT", "SPATIAL");
    private static final Set<String> INLINE_CONSTRAINT_WORDS = Set.of("UNIQUE", "REFERENCES", "CHECK", "PRIMARY", "CONSTRAINT", "KEY");
    /** ADD 컬럼 정의 안에 나오면 쉼표 없이 이어 붙인 다른 동작으로 보고 거부한다(Oracle은 동작을 쉼표 없이 잇는다). */
    private static final Set<String> ALTER_ACTION_WORDS = Set.of("ADD", "DROP", "MODIFY", "RENAME", "ALTER", "CHANGE");
    private static final Set<String> DROP_CONSTRAINT_WORDS = Set.of("CONSTRAINT", "INDEX", "KEY", "PRIMARY", "FOREIGN", "CHECK", "UNIQUE");
    private static final Set<String> MODIFY_NOT_COLUMN = Set.of("CONSTRAINT", "PARTITION", "SUBPARTITION", "PRIMARY", "UNIQUE", "DEFAULT", "LOB", "NESTED");
    private static final Set<String> TRUNCATE_TAIL = Set.of(
            "RESTART", "CONTINUE", "IDENTITY", "RESTRICT", "DROP", "REUSE", "STORAGE", "ALL", "PRESERVE", "PURGE",
            "MATERIALIZED", "VIEW", "LOG");
    private static final Set<String> INSERT_BODY_STARTS = Set.of("VALUES", "VALUE", "SELECT", "WITH", "DEFAULT", "SET", "OVERRIDING");
    private static final Set<String> DELETE_NEXT = Set.of("WHERE", "RETURNING", "ORDER", "LIMIT");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "STRAIGHT_JOIN", "OUTER", "USING");
    /** 대상 테이블 뒤에 오는 이 단어는 별칭이 아니다. */
    private static final Set<String> ALIAS_STOP = Set.of(
            "SET", "WHERE", "FROM", "USING", "RETURNING", "ORDER", "LIMIT", "PARTITION", "ON",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "STRAIGHT_JOIN", "OUTER");
    /** MySQL CREATE TABLE에서 받는 저장 엔진 — MERGE(UNION으로 남의 테이블에 씀)·FEDERATED·CONNECT 등은 거부. */
    private static final Set<String> MYSQL_ENGINES = Set.of("INNODB", "MYISAM", "ARIA", "MEMORY", "HEAP");

    private SqlWriteParser() {}

    public static WriteStatement parse(String sql, SqlDialect d) throws SqlWriteRejected {
        if (sql == null || sql.isBlank()) throw new SqlWriteRejected(EMPTY);
        if (sql.length() > MAX_SQL_CHARS) throw new SqlWriteRejected("SQL은 " + MAX_SQL_CHARS + "자까지 받습니다");
        List<Token> tokens = SqlWriteTokenizer.tokenize(sql, d);
        if (tokens.isEmpty()) throw new SqlWriteRejected(EMPTY);

        Routine routine = new Parser(tokens, d, sql).routine();
        if (routine != null) {
            // PostgreSQL 본문은 $$·'…' 안에 있으므로 최상위 ';'는 다음 문장이다(JDBC가 이어서 실행한다).
            if (d == SqlDialect.POSTGRES) withoutTrailingSemicolon(tokens);
            return new WriteStatement(routine.kind(), List.of(), List.of(), false, routine.orReplace(), false, null, sql);
        }

        List<Token> body = withoutTrailingSemicolon(tokens);
        if (body.isEmpty()) throw new SqlWriteRejected(EMPTY);
        requireBalancedParens(body);
        String executable = body.size() < tokens.size()
                ? sql.substring(0, tokens.get(body.size()).start()).strip()
                : sql.strip();
        WriteStatement st = new Parser(body, d, executable).statement();
        rejectForbiddenFunctions(body);
        return st;
    }

    private record Routine(WriteKind kind, boolean orReplace) {}

    private record CreateHead(boolean orReplace, boolean definer, String object) {}

    private record Part(String text, boolean quoted) {}

    /** ALTER TABLE 동작을 모은다 — 이름 변경은 한 번만. */
    private static final class AlterBuilder {
        final List<AlterAction> actions = new ArrayList<>();
        DbObjectRef renameTo;

        void rename(DbObjectRef to) throws SqlWriteRejected {
            if (renameTo != null) throw new SqlWriteRejected("테이블 이름 변경은 한 번만 할 수 있습니다");
            renameTo = to;
            actions.add(AlterAction.RENAME_TABLE);
        }
    }

    /** 끝 ';' 하나를 뗀 토큰. 그 밖의 최상위 ';'가 있으면 여러 문장이다. */
    private static List<Token> withoutTrailingSemicolon(List<Token> tokens) throws SqlWriteRejected {
        int end = tokens.get(tokens.size() - 1).isSymbol(';') ? tokens.size() - 1 : tokens.size();
        for (int k = 0; k < end; k++) {
            if (tokens.get(k).isSymbol(';')) throw new SqlWriteRejected(MULTI_STATEMENT);
        }
        return tokens.subList(0, end);
    }

    private static void requireBalancedParens(List<Token> tokens) throws SqlWriteRejected {
        int depth = 0;
        for (Token x : tokens) {
            if (x.isSymbol('(')) depth++;
            else if (x.isSymbol(')') && --depth < 0) throw new SqlWriteRejected(UNBALANCED);
        }
        if (depth != 0) throw new SqlWriteRejected(UNBALANCED);
    }

    /** 읽기 경로와 같은 금지 함수 목록 — 쓰기에 쓰는 nextval·setval만 뺀다. 인용 이름으로 부르는 것도 막는다. */
    private static void rejectForbiddenFunctions(List<Token> tokens) throws SqlWriteRejected {
        for (Token x : tokens) {
            if (!x.isIdent()) continue;
            String lower = x.text().toLowerCase(Locale.ROOT);
            if (lower.equals("nextval") || lower.equals("setval")) continue;
            if (SqlReadOnlyChecker.isForbiddenFunction(lower)) throw new SqlWriteRejected("금지 함수 " + lower);
        }
    }

    private static void rejectNestedStatements(List<Token> tokens, boolean allowUpsertUpdate) throws SqlWriteRejected {
        for (int k = 0; k < tokens.size(); k++) {
            Token x = tokens.get(k);
            if (x.type() != Type.WORD) continue;
            String w = x.upper();
            if (w.equals("LOG") && k + 1 < tokens.size() && tokens.get(k + 1).is("ERRORS")) {
                throw new SqlWriteRejected("LOG ERRORS(오류 행을 다른 테이블에 기록)는 받지 않습니다");
            }
            if (!NESTED_STATEMENT_WORDS.contains(w)) continue;
            // INSERT … ON CONFLICT … DO UPDATE (PostgreSQL) · ON DUPLICATE KEY UPDATE (MySQL)
            if (allowUpsertUpdate && w.equals("UPDATE") && k > 0 && (tokens.get(k - 1).is("DO") || tokens.get(k - 1).is("KEY"))) {
                continue;
            }
            throw new SqlWriteRejected("한 문장 안에 다른 문장(" + w + ")은 넣을 수 없습니다");
        }
    }

    private static boolean hasTopLevelWord(List<Token> tokens, String word) {
        int depth = 0;
        for (Token x : tokens) {
            if (x.isSymbol('(')) depth++;
            else if (x.isSymbol(')')) depth--;
            else if (depth == 0 && x.is(word)) return true;
        }
        return false;
    }

    private static List<List<Token>> splitTopLevel(List<Token> tokens) throws SqlWriteRejected {
        List<List<Token>> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int k = 0; k < tokens.size(); k++) {
            Token x = tokens.get(k);
            if (x.isSymbol('(')) depth++;
            else if (x.isSymbol(')')) depth--;
            else if (depth == 0 && x.isSymbol(',')) {
                out.add(tokens.subList(from, k));
                from = k + 1;
            }
        }
        out.add(tokens.subList(from, tokens.size()));
        for (List<Token> seg : out) {
            if (seg.isEmpty()) throw new SqlWriteRejected("쉼표 사이가 비었습니다");
        }
        return out;
    }

    static String describe(Token x) {
        if (x == null) return "문장 끝";
        String s = switch (x.type()) {
            case WORD -> x.upper();
            case STRING -> "'" + x.text() + "'";
            case QUOTED_IDENT -> "\"" + x.text() + "\"";
            case DOLLAR_BODY -> "$$…$$";
            default -> x.text();
        };
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static final class Parser {
        private final List<Token> t;
        private final SqlDialect d;
        private final String executableSql;
        private int p;

        Parser(List<Token> tokens, SqlDialect d, String executableSql) {
            this.t = tokens;
            this.d = d;
            this.executableSql = executableSql;
        }

        // ---- 커서 ----

        private Token peek() {
            return peek(0);
        }

        private Token peek(int k) {
            int i = p + k;
            return i < t.size() ? t.get(i) : null;
        }

        private boolean atEnd() {
            return p >= t.size();
        }

        private boolean peekIs(String word) {
            return peekIs(0, word);
        }

        private boolean peekIs(int k, String word) {
            Token x = peek(k);
            return x != null && x.is(word);
        }

        private boolean peekSymbol(char c) {
            Token x = peek();
            return x != null && x.isSymbol(c);
        }

        /** 다음 토큰이 단어면 대문자, 아니면 describe. */
        private String peekWord() {
            Token x = peek();
            return x != null && x.type() == Type.WORD ? x.upper() : describe(x);
        }

        private boolean peekWordIn(Set<String> words) {
            Token x = peek();
            return x != null && x.type() == Type.WORD && words.contains(x.upper());
        }

        private boolean accept(String word) {
            if (!peekIs(word)) return false;
            p++;
            return true;
        }

        private boolean acceptAll(String... words) {
            for (int k = 0; k < words.length; k++) {
                if (!peekIs(k, words[k])) return false;
            }
            p += words.length;
            return true;
        }

        private boolean acceptSymbol(char c) {
            if (!peekSymbol(c)) return false;
            p++;
            return true;
        }

        private void expect(String word, String failMessage) throws SqlWriteRejected {
            if (!accept(word)) throw new SqlWriteRejected(failMessage);
        }

        private void expectWord(String failMessage) throws SqlWriteRejected {
            Token x = peek();
            if (x == null || x.type() != Type.WORD) throw new SqlWriteRejected(failMessage);
            p++;
        }

        private List<Token> rest() {
            return t.subList(p, t.size());
        }

        private void requireEnd(String what) throws SqlWriteRejected {
            if (!atEnd()) throw new SqlWriteRejected("분석할 수 없는 " + what + " 형식: " + describe(peek()));
        }

        /** '(' 위에서 짝 ')'까지 넘기고 그 안 토큰을 돌려준다. */
        private List<Token> parenContent() throws SqlWriteRejected {
            int open = p;
            int depth = 0;
            for (int k = p; k < t.size(); k++) {
                if (t.get(k).isSymbol('(')) {
                    depth++;
                } else if (t.get(k).isSymbol(')') && --depth == 0) {
                    p = k + 1;
                    return t.subList(open + 1, k);
                }
            }
            throw new SqlWriteRejected(UNBALANCED);
        }

        // ---- 이름 ----

        private Part ident() throws SqlWriteRejected {
            Token x = peek();
            if (x == null || !x.isIdent()) throw new SqlWriteRejected("이름을 읽을 수 없습니다: " + describe(x));
            // PostgreSQL은 63바이트를 넘는 이름을 조용히 잘라 다른 객체를 가리킬 수 있다.
            if (d == SqlDialect.POSTGRES && x.text().getBytes(StandardCharsets.UTF_8).length > PG_MAX_IDENT_BYTES) {
                throw new SqlWriteRejected("이름이 PostgreSQL 한도 " + PG_MAX_IDENT_BYTES + "바이트를 넘습니다: " + describe(x));
            }
            p++;
            return new Part(x.text(), x.type() == Type.QUOTED_IDENT);
        }

        private List<Part> qualifiedParts(int max) throws SqlWriteRejected {
            List<Part> parts = new ArrayList<>();
            parts.add(ident());
            while (acceptSymbol('.')) {
                if (parts.size() == max) throw new SqlWriteRejected("이름은 " + (max == 2 ? "스키마.이름" : "스키마.테이블.컬럼") + "까지만 받습니다");
                parts.add(ident());
            }
            return parts;
        }

        private DbObjectRef objectName() throws SqlWriteRejected {
            List<Part> parts = qualifiedParts(2);
            Part name = parts.get(parts.size() - 1);
            Part schema = parts.size() == 2 ? parts.get(0) : null;
            return schema == null
                    ? new DbObjectRef(null, false, name.text(), name.quoted())
                    : new DbObjectRef(schema.text(), schema.quoted(), name.text(), name.quoted());
        }

        private List<DbObjectRef> objectNames() throws SqlWriteRejected {
            List<DbObjectRef> names = new ArrayList<>();
            do {
                names.add(objectName());
            } while (acceptSymbol(','));
            return names;
        }

        /** PostgreSQL ONLY(상속 자식 제외) — 뒤에 이름이 올 때만 키워드로 본다. */
        private void skipOnly() {
            Token next = peek(1);
            if (d == SqlDialect.POSTGRES && peekIs("ONLY") && next != null && next.isIdent()) p++;
        }

        private void skipAlias() throws SqlWriteRejected {
            if (accept("AS")) {
                ident();
                return;
            }
            Token x = peek();
            if (x != null && x.isIdent() && !(x.type() == Type.WORD && ALIAS_STOP.contains(x.upper()))) p++;
        }

        // ---- 문장 ----

        Routine routine() throws SqlWriteRejected {
            if (!peekIs("CREATE")) return null;
            p++;
            CreateHead h = createHead();
            return switch (h.object()) {
                case "TRIGGER" -> new Routine(WriteKind.CREATE_TRIGGER, h.orReplace());
                case "PROCEDURE", "FUNCTION", "PACKAGE" -> new Routine(WriteKind.CREATE_ROUTINE, h.orReplace());
                default -> null;
            };
        }

        WriteStatement statement() throws SqlWriteRejected {
            String head = peekWord();
            return switch (head) {
                case "CREATE" -> create();
                case "ALTER" -> alter();
                case "DROP" -> drop();
                case "TRUNCATE" -> truncate();
                case "RENAME" -> rename();
                case "COMMENT" -> comment();
                case "INSERT" -> insert();
                case "UPDATE" -> update();
                case "DELETE" -> delete();
                case "GRANT", "REVOKE" -> throw new SqlWriteRejected("권한 부여·회수(GRANT/REVOKE)는 허용하지 않습니다");
                case "SET", "RESET" -> throw new SqlWriteRejected("서버·세션 설정 변경(SET)은 허용하지 않습니다");
                case "WITH" -> throw new SqlWriteRejected("WITH로 시작하는 문장은 분석할 수 없습니다 — WITH 없이 한 문장으로 쓰세요");
                case "MERGE" -> throw new SqlWriteRejected("MERGE는 분석할 수 없습니다 — INSERT와 UPDATE로 나눠 쓰세요");
                case "CALL" -> throw new SqlWriteRejected("프로시저 호출(CALL)은 실행할 수 없습니다");
                case "DO" -> throw new SqlWriteRejected("익명 블록(DO)은 실행할 수 없습니다");
                case "SELECT" -> throw new SqlWriteRejected("조회문은 쓰기 도구로 실행하지 않습니다 — query 도구를 쓰세요");
                default -> throw new SqlWriteRejected("분석할 수 없는 문장입니다(시작 키워드 " + head + ")");
            };
        }

        /** CREATE 다음의 OR REPLACE·DEFINER·ALGORITHM·SQL SECURITY·[NO] FORCE·EDITIONABLE 등을 넘기고 대상 단어를 읽는다. */
        private CreateHead createHead() throws SqlWriteRejected {
            boolean orReplace = acceptAll("OR", "REPLACE");
            boolean definer = false;
            while (true) {
                if (accept("DEFINER")) {
                    if (!acceptSymbol('=')) throw new SqlWriteRejected("DEFINER를 읽을 수 없습니다");
                    skipAccount();
                    definer = true;
                } else if (accept("ALGORITHM")) {
                    if (!acceptSymbol('=')) throw new SqlWriteRejected("ALGORITHM을 읽을 수 없습니다");
                    expectWord("ALGORITHM을 읽을 수 없습니다");
                } else if (peekIs("SQL") && peekIs(1, "SECURITY")) {
                    p += 2;
                    expectWord("SQL SECURITY를 읽을 수 없습니다");
                } else if (acceptAll("NO", "FORCE") || accept("FORCE") || accept("NOFORCE") || accept("EDITIONABLE")
                        || accept("NONEDITIONABLE") || accept("EDITIONING")) {
                    // Oracle 뷰·루틴 수식어
                } else if ((peekIs("CONSTRAINT") && peekIs(1, "TRIGGER")) || (peekIs("AGGREGATE") && peekIs(1, "FUNCTION"))) {
                    p++;   // PostgreSQL CONSTRAINT TRIGGER · MySQL AGGREGATE FUNCTION
                } else {
                    break;
                }
            }
            Token obj = peek();
            if (obj == null || obj.type() != Type.WORD) throw new SqlWriteRejected("분석할 수 없는 CREATE 대상: " + describe(obj));
            p++;
            return new CreateHead(orReplace, definer, obj.upper());
        }

        /** MySQL 계정 표기: user, 'user', `user`, CURRENT_USER[()] 다음 선택적 @host. */
        private void skipAccount() throws SqlWriteRejected {
            Token u = peek();
            if (u == null || !(u.isIdent() || u.type() == Type.STRING)) throw new SqlWriteRejected("DEFINER를 읽을 수 없습니다");
            p++;
            Token close = peek(1);
            if (peekSymbol('(') && close != null && close.isSymbol(')')) p += 2;
            if (acceptSymbol('@')) {
                Token h = peek();
                if (h == null || !(h.isIdent() || h.type() == Type.STRING || h.type() == Type.NUMBER)) {
                    throw new SqlWriteRejected("DEFINER를 읽을 수 없습니다");
                }
                p++;
            }
        }

        private WriteStatement create() throws SqlWriteRejected {
            p++;
            CreateHead h = createHead();
            if (h.definer()) throw new SqlWriteRejected(DEFINER);
            String obj = h.object();
            return switch (obj) {
                case "TABLE" -> createTable(h);
                case "SEQUENCE" -> createSequence(h);
                case "VIEW" -> createView(h);
                case "INDEX" -> createIndex(h, false);
                case "UNIQUE", "BITMAP", "FULLTEXT", "SPATIAL" -> {
                    expect("INDEX", "분석할 수 없는 CREATE 대상: " + obj);
                    yield createIndex(h, obj.equals("UNIQUE"));
                }
                case "USER", "ROLE" -> throw new SqlWriteRejected(USER_ROLE);
                default -> throw new SqlWriteRejected("분석할 수 없는 CREATE 대상: " + obj);
            };
        }

        private WriteStatement createTable(CreateHead h) throws SqlWriteRejected {
            boolean ifNotExists = acceptAll("IF", "NOT", "EXISTS");
            DbObjectRef name = objectName();
            Token next = peek();
            List<Token> tail;
            if (next != null && next.isSymbol('(')) {
                parenContent();
                tail = rest();
            } else if (next != null && next.is("AS")) {
                tail = rest();
            } else if (next != null && next.is("LIKE")) {
                p++;
                objectName();
                requireEnd("CREATE TABLE … LIKE");
                tail = List.of();
            } else if (next != null && next.is("PARTITION")) {
                throw new SqlWriteRejected(ATTACHED_TABLE);
            } else {
                throw new SqlWriteRejected("분석할 수 없는 CREATE TABLE 형식: " + describe(next));
            }
            for (Token x : tail) {
                if (x.is("INHERITS")) throw new SqlWriteRejected(ATTACHED_TABLE);
            }
            if (d == SqlDialect.MYSQL) requireKnownEngine(tail);
            rejectNestedStatements(tail, false);
            return new WriteStatement(WriteKind.CREATE_TABLE, List.of(name), List.of(), ifNotExists, h.orReplace(),
                    false, null, executableSql);
        }

        private void requireKnownEngine(List<Token> tail) throws SqlWriteRejected {
            for (int k = 0; k < tail.size(); k++) {
                if (!tail.get(k).is("ENGINE")) continue;
                int v = k + 1 < tail.size() && tail.get(k + 1).isSymbol('=') ? k + 2 : k + 1;
                Token engine = v < tail.size() ? tail.get(v) : null;
                if (engine == null || engine.type() != Type.WORD || !MYSQL_ENGINES.contains(engine.upper())) {
                    throw new SqlWriteRejected("받지 않는 저장 엔진입니다: " + describe(engine) + " — InnoDB를 쓰세요");
                }
            }
        }

        private WriteStatement createSequence(CreateHead h) throws SqlWriteRejected {
            boolean ifNotExists = acceptAll("IF", "NOT", "EXISTS");
            DbObjectRef name = objectName();
            Token next = peek();
            if (next != null && next.type() != Type.WORD) {
                throw new SqlWriteRejected("분석할 수 없는 CREATE SEQUENCE 형식: " + describe(next));
            }
            return new WriteStatement(WriteKind.CREATE_SEQUENCE, List.of(name), List.of(), ifNotExists, h.orReplace(),
                    false, null, executableSql);
        }

        private WriteStatement createView(CreateHead h) throws SqlWriteRejected {
            boolean ifNotExists = acceptAll("IF", "NOT", "EXISTS");
            DbObjectRef name = objectName();
            if (peekSymbol('(')) parenContent();
            expect("AS", "분석할 수 없는 CREATE VIEW 형식 — CREATE VIEW 이름 AS SELECT …만 받습니다");
            rejectNestedStatements(rest(), false);
            return new WriteStatement(WriteKind.CREATE_VIEW, List.of(name), List.of(), ifNotExists, h.orReplace(),
                    false, null, executableSql);
        }

        private WriteStatement createIndex(CreateHead h, boolean unique) throws SqlWriteRejected {
            if (h.orReplace()) throw new SqlWriteRejected("CREATE OR REPLACE INDEX는 받지 않습니다 — 기존 인덱스를 지울 수 있습니다");
            accept("CONCURRENTLY");
            boolean ifNotExists = acceptAll("IF", "NOT", "EXISTS");
            if (!peekIs("ON")) objectName();   // 인덱스 이름(PostgreSQL은 생략 가능)
            if (accept("USING")) expectWord("분석할 수 없는 CREATE INDEX 형식 — USING 뒤가 비었습니다");
            expect("ON", "분석할 수 없는 CREATE INDEX 형식 — ON 테이블이 없습니다");
            skipOnly();
            DbObjectRef table = objectName();
            Token next = peek();
            if (next == null || !(next.isSymbol('(') || next.is("USING"))) {
                throw new SqlWriteRejected("분석할 수 없는 CREATE INDEX 형식: " + describe(next));
            }
            return new WriteStatement(unique ? WriteKind.CREATE_UNIQUE_INDEX : WriteKind.CREATE_INDEX, List.of(table),
                    List.of(), ifNotExists, false, false, null, executableSql);
        }

        private WriteStatement alter() throws SqlWriteRejected {
            p++;
            String obj = peekWord();
            switch (obj) {
                case "TABLE" -> p++;
                case "USER", "ROLE" -> throw new SqlWriteRejected(USER_ROLE);
                case "SYSTEM", "SESSION" -> throw new SqlWriteRejected("서버·세션 설정 변경(ALTER SYSTEM/SESSION)은 허용하지 않습니다");
                default -> throw new SqlWriteRejected("분석할 수 없는 ALTER 대상: " + obj);
            }
            acceptAll("IF", "EXISTS");
            skipOnly();
            DbObjectRef table = objectName();
            if (atEnd()) throw new SqlWriteRejected("ALTER TABLE 동작이 없습니다");
            AlterBuilder out = new AlterBuilder();
            for (List<Token> segment : splitTopLevel(rest())) {
                new Parser(segment, d, executableSql).alterAction(out);
            }
            return new WriteStatement(WriteKind.ALTER_TABLE, List.of(table), out.actions, false, false, false,
                    out.renameTo, executableSql);
        }

        private void alterAction(AlterBuilder out) throws SqlWriteRejected {
            String w = peekWord();
            p++;
            switch (w) {
                case "ADD" -> addAction(out);
                case "DROP" -> dropAction(out);
                case "RENAME" -> renameAction(out);
                case "ALTER" -> alterColumnAction(out);
                case "MODIFY" -> modifyAction(out);
                case "CHANGE" -> changeAction(out);
                default -> throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + w);
            }
        }

        private void addAction(AlterBuilder out) throws SqlWriteRejected {
            boolean columnKeyword = accept("COLUMN");
            acceptAll("IF", "NOT", "EXISTS");
            if (atEnd()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "ADD");
            if (peekSymbol('(')) {   // ADD (컬럼, 컬럼, 제약) — 항목마다
                List<Token> inner = parenContent();
                requireEnd("ALTER TABLE … ADD (…)");
                if (inner.isEmpty()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "ADD ()");
                for (List<Token> item : splitTopLevel(inner)) out.actions.add(addItem(item, columnKeyword));
            } else {
                out.actions.add(addItem(rest(), columnKeyword));
            }
        }

        private AlterAction addItem(List<Token> item, boolean columnKeyword) throws SqlWriteRejected {
            Token first = item.get(0);
            if (!columnKeyword && first.type() == Type.WORD) {
                if (ADD_CONSTRAINT_WORDS.contains(first.upper())) return AlterAction.ADD_CONSTRAINT;
                if (ADD_INDEX_WORDS.contains(first.upper())) throw new SqlWriteRejected(ADD_INDEX);
            }
            Parser c = new Parser(item, d, executableSql);
            Part column = c.ident();
            Token type = c.peek();
            if (type == null || type.type() != Type.WORD || !COLUMN_TYPES.contains(type.upper())) {
                throw new SqlWriteRejected("분석할 수 없는 컬럼 정의: ADD " + column.text() + " " + describe(type)
                        + " — 기본 타입만 받습니다");
            }
            List<Token> options = item.subList(c.p + 1, item.size());
            boolean inlineConstraint = false;
            for (Token o : options) {
                if (o.type() != Type.WORD) continue;
                if (ALTER_ACTION_WORDS.contains(o.upper())) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "ADD … " + o.upper());
                if (INLINE_CONSTRAINT_WORDS.contains(o.upper())) inlineConstraint = true;
            }
            if (inlineConstraint) return AlterAction.ADD_COLUMN_WITH_CONSTRAINT;
            return notNullWithoutDefault(options) ? AlterAction.ADD_COLUMN_NOT_NULL_NO_DEFAULT : AlterAction.ADD_COLUMN;
        }

        /** 최상위 NOT NULL이 있고 DEFAULT가 없는지 — DEFAULT NULL은 기본값이 없는 것과 같다. */
        private static boolean notNullWithoutDefault(List<Token> options) {
            boolean notNull = false;
            boolean hasDefault = false;
            int depth = 0;
            for (int k = 0; k < options.size(); k++) {
                Token o = options.get(k);
                Token next = k + 1 < options.size() ? options.get(k + 1) : null;
                boolean nextIsNull = next != null && next.is("NULL");
                if (o.isSymbol('(')) depth++;
                else if (o.isSymbol(')')) depth--;
                else if (depth == 0 && o.is("NOT") && nextIsNull) notNull = true;
                else if (depth == 0 && o.is("DEFAULT") && next != null && !nextIsNull) hasDefault = true;
            }
            return notNull && !hasDefault;
        }

        private void dropAction(AlterBuilder out) throws SqlWriteRejected {
            if (accept("COLUMN")) {
                acceptAll("IF", "EXISTS");
                if (peekSymbol('(')) parenContent();
                else ident();
                dropTail("DROP COLUMN");
                out.actions.add(AlterAction.DROP_COLUMN);
            } else if (peekSymbol('(')) {   // Oracle DROP (a, b)
                parenContent();
                dropTail("DROP (…)");
                out.actions.add(AlterAction.DROP_COLUMN);
            } else if (peekWordIn(DROP_CONSTRAINT_WORDS)) {
                for (Token x : rest()) {
                    if (x.is("CASCADE")) throw new SqlWriteRejected(CASCADE);
                }
                out.actions.add(AlterAction.DROP_CONSTRAINT);
            } else if (d == SqlDialect.ORACLE) {   // Oracle은 DROP COLUMN·DROP (…)만 컬럼 삭제다(DROP UNUSED COLUMNS 등)
                throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "DROP " + peekWord());
            } else {   // PostgreSQL·MySQL의 COLUMN 생략형 — DROP PARTITION p0처럼 이름 뒤에 더 있으면 컬럼이 아니다
                acceptAll("IF", "EXISTS");
                String column = peekWord();
                ident();
                dropTail("DROP " + column);
                out.actions.add(AlterAction.DROP_COLUMN);
            }
        }

        private void dropTail(String what) throws SqlWriteRejected {
            while (!atEnd()) {
                if (accept("CASCADE")) throw new SqlWriteRejected(CASCADE);
                if (accept("RESTRICT") || accept("INVALIDATE")) continue;
                if (accept("CHECKPOINT")) {
                    Token n = peek();
                    if (n != null && n.type() == Type.NUMBER) p++;
                    continue;
                }
                throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + what + " " + describe(peek()));
            }
        }

        private void renameAction(AlterBuilder out) throws SqlWriteRejected {
            if (accept("COLUMN")) {
                renameColumn(out);
            } else if (accept("TO") || accept("AS")) {
                out.rename(objectName());
                requireEnd("ALTER TABLE … RENAME");
            } else if (peekWordIn(Set.of("CONSTRAINT", "INDEX", "KEY"))) {
                throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "RENAME " + peekWord());
            } else if (d == SqlDialect.POSTGRES) {   // RENAME a TO b
                renameColumn(out);
            } else if (d == SqlDialect.MYSQL) {      // RENAME new_name
                out.rename(objectName());
                requireEnd("ALTER TABLE … RENAME");
            } else {
                throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "RENAME " + peekWord());
            }
        }

        private void renameColumn(AlterBuilder out) throws SqlWriteRejected {
            ident();
            expect("TO", "분석할 수 없는 RENAME COLUMN 형식 — RENAME COLUMN a TO b만 받습니다");
            ident();
            requireEnd("RENAME COLUMN");
            out.actions.add(AlterAction.RENAME_COLUMN);
        }

        private void alterColumnAction(AlterBuilder out) throws SqlWriteRejected {
            if (peekWordIn(Set.of("CONSTRAINT", "INDEX", "CHECK"))) {
                throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "ALTER " + peekWord());
            }
            accept("COLUMN");
            ident();
            if (atEnd()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "ALTER");
            out.actions.add(AlterAction.ALTER_COLUMN);
        }

        private void modifyAction(AlterBuilder out) throws SqlWriteRejected {
            accept("COLUMN");
            if (atEnd()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "MODIFY");
            if (peekWordIn(MODIFY_NOT_COLUMN)) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "MODIFY " + peekWord());
            if (peekSymbol('(')) {   // Oracle MODIFY (a …, b …)
                parenContent();
                requireEnd("ALTER TABLE … MODIFY (…)");
            } else {
                ident();
                if (atEnd()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "MODIFY");
            }
            out.actions.add(AlterAction.ALTER_COLUMN);
        }

        private void changeAction(AlterBuilder out) throws SqlWriteRejected {
            accept("COLUMN");
            ident();
            ident();
            if (atEnd()) throw new SqlWriteRejected(UNKNOWN_ALTER_ACTION + "CHANGE");
            out.actions.add(AlterAction.ALTER_COLUMN);
        }

        private WriteStatement drop() throws SqlWriteRejected {
            p++;
            String obj = peekWord();
            WriteKind kind = switch (obj) {
                case "TABLE" -> WriteKind.DROP_TABLE;
                case "VIEW" -> WriteKind.DROP_VIEW;
                case "SEQUENCE" -> WriteKind.DROP_SEQUENCE;
                case "USER", "ROLE" -> throw new SqlWriteRejected(USER_ROLE);
                case "DATABASE", "SCHEMA" -> throw new SqlWriteRejected("DROP DATABASE/SCHEMA는 허용하지 않습니다");
                default -> throw new SqlWriteRejected("분석할 수 없는 DROP 대상: " + obj);
            };
            p++;
            acceptAll("IF", "EXISTS");
            List<DbObjectRef> names = objectNames();
            while (!atEnd()) {
                if (accept("CASCADE")) throw new SqlWriteRejected(CASCADE);
                if (!(accept("RESTRICT") || accept("PURGE"))) {
                    throw new SqlWriteRejected("분석할 수 없는 DROP 형식: " + describe(peek()));
                }
            }
            return new WriteStatement(kind, names, List.of(), false, false, false, null, executableSql);
        }

        private WriteStatement truncate() throws SqlWriteRejected {
            p++;
            accept("TABLE");
            List<DbObjectRef> names = new ArrayList<>();
            do {
                skipOnly();
                names.add(objectName());
            } while (acceptSymbol(','));
            while (!atEnd()) {
                if (accept("CASCADE")) throw new SqlWriteRejected(CASCADE);
                if (!peekWordIn(TRUNCATE_TAIL)) throw new SqlWriteRejected("분석할 수 없는 TRUNCATE 형식: " + describe(peek()));
                p++;
            }
            return new WriteStatement(WriteKind.TRUNCATE, names, List.of(), false, false, false, null, executableSql);
        }

        /** MySQL RENAME TABLE a TO b · Oracle RENAME a TO b — 한 쌍만. */
        private WriteStatement rename() throws SqlWriteRejected {
            p++;
            if (d == SqlDialect.MYSQL) {
                String obj = peekWord();
                if (obj.equals("USER")) throw new SqlWriteRejected(USER_ROLE);
                if (!obj.equals("TABLE")) throw new SqlWriteRejected("분석할 수 없는 RENAME 대상: " + obj);
                p++;
            } else if (d == SqlDialect.POSTGRES) {
                throw new SqlWriteRejected("분석할 수 없는 문장입니다(시작 키워드 RENAME)");
            }
            DbObjectRef from = objectName();
            expect("TO", "분석할 수 없는 RENAME 형식 — RENAME … TO …만 받습니다");
            DbObjectRef to = objectName();
            if (peekSymbol(',')) throw new SqlWriteRejected("테이블 이름 변경은 한 번에 하나만 받습니다");
            requireEnd("RENAME");
            return new WriteStatement(WriteKind.RENAME_TABLE, List.of(from), List.of(), false, false, false, to, executableSql);
        }

        private WriteStatement comment() throws SqlWriteRejected {
            if (d == SqlDialect.MYSQL) throw new SqlWriteRejected("MySQL에는 COMMENT ON 문이 없습니다");
            p++;
            expect("ON", "분석할 수 없는 COMMENT 형식 — COMMENT ON …만 받습니다");
            String obj = peekWord();
            DbObjectRef target;
            switch (obj) {
                case "TABLE", "VIEW" -> {
                    p++;
                    target = objectName();
                }
                case "COLUMN" -> {
                    p++;
                    List<Part> parts = qualifiedParts(3);
                    if (parts.size() < 2) throw new SqlWriteRejected("COMMENT ON COLUMN은 테이블.컬럼으로 적어 주세요");
                    Part table = parts.get(parts.size() - 2);
                    Part schema = parts.size() == 3 ? parts.get(0) : null;
                    target = schema == null
                            ? new DbObjectRef(null, false, table.text(), table.quoted())
                            : new DbObjectRef(schema.text(), schema.quoted(), table.text(), table.quoted());
                }
                default -> throw new SqlWriteRejected("분석할 수 없는 COMMENT ON 대상: " + obj);
            }
            expect("IS", "분석할 수 없는 COMMENT ON 형식 — … IS '설명'만 받습니다");
            Token value = peek();
            if (value == null || !(value.type() == Type.STRING || value.is("NULL"))) {
                throw new SqlWriteRejected("분석할 수 없는 COMMENT ON 형식 — … IS '설명'만 받습니다");
            }
            p++;
            requireEnd("COMMENT ON");
            return new WriteStatement(WriteKind.COMMENT_ON, List.of(target), List.of(), false, false, false, null, executableSql);
        }

        private WriteStatement insert() throws SqlWriteRejected {
            p++;
            if (d == SqlDialect.MYSQL) {
                while (accept("LOW_PRIORITY") || accept("DELAYED") || accept("HIGH_PRIORITY") || accept("IGNORE")) {
                    // MySQL 수식어
                }
            }
            if (peekIs("ALL") || peekIs("FIRST")) {
                throw new SqlWriteRejected("다중 테이블 INSERT(INSERT ALL/FIRST)는 분석할 수 없습니다 — 테이블마다 INSERT를 나눠 쓰세요");
            }
            expect("INTO", "INSERT INTO 형식만 받습니다");
            DbObjectRef target = objectName();
            if (accept("AS")) ident();
            Token next = peek();
            if (next == null || !(next.isSymbol('(') || (next.type() == Type.WORD && INSERT_BODY_STARTS.contains(next.upper())))) {
                throw new SqlWriteRejected("분석할 수 없는 INSERT 형식: " + describe(next));
            }
            rejectNestedStatements(rest(), true);
            return new WriteStatement(WriteKind.INSERT, List.of(target), List.of(), false, false, false, null, executableSql);
        }

        private WriteStatement update() throws SqlWriteRejected {
            p++;
            if (d == SqlDialect.MYSQL) {
                while (accept("LOW_PRIORITY") || accept("IGNORE")) {
                    // MySQL 수식어
                }
            }
            skipOnly();
            DbObjectRef target = objectName();
            skipAlias();
            if (!accept("SET")) throw notSingleTable("UPDATE");
            List<Token> body = rest();
            rejectNestedStatements(body, false);
            int depth = 0;
            for (int k = 0; k < body.size(); k++) {
                Token x = body.get(k);
                if (x.isSymbol('(')) depth++;
                if (x.isSymbol(')')) depth--;
                if (depth != 0) continue;
                if (x.is("WHERE")) break;
                // PostgreSQL UPDATE … FROM(다른 테이블과 조인) — IS DISTINCT FROM은 식이다
                if (x.is("FROM") && !(k > 0 && body.get(k - 1).is("DISTINCT"))) throw new SqlWriteRejected(MULTI_TABLE);
            }
            return new WriteStatement(WriteKind.UPDATE, List.of(target), List.of(), false, false,
                    hasTopLevelWord(body, "WHERE"), null, executableSql);
        }

        private WriteStatement delete() throws SqlWriteRejected {
            p++;
            if (d == SqlDialect.MYSQL) {
                while (accept("LOW_PRIORITY") || accept("QUICK") || accept("IGNORE")) {
                    // MySQL 수식어
                }
            }
            // Oracle은 FROM을 생략할 수 있다. MySQL DELETE t1 FROM …(다중 테이블)은 여기서 걸린다.
            if (!accept("FROM") && d != SqlDialect.ORACLE) throw new SqlWriteRejected("DELETE FROM 한 테이블 형식만 받습니다");
            skipOnly();
            DbObjectRef target = objectName();
            skipAlias();
            if (!atEnd() && !peekWordIn(DELETE_NEXT)) throw notSingleTable("DELETE");
            List<Token> body = rest();
            rejectNestedStatements(body, false);
            return new WriteStatement(WriteKind.DELETE, List.of(target), List.of(), false, false,
                    hasTopLevelWord(body, "WHERE"), null, executableSql);
        }

        private SqlWriteRejected notSingleTable(String statement) {
            Token x = peek();
            if (x != null && (x.isSymbol(',') || (x.type() == Type.WORD && JOIN_WORDS.contains(x.upper())))) {
                return new SqlWriteRejected(MULTI_TABLE);
            }
            return new SqlWriteRejected("분석할 수 없는 " + statement + " 형식: " + describe(x));
        }
    }
}
