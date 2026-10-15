package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * JDBC 읽기 전용 실행 (스펙 2026-10-14 §6.3). 호출당 1연결(풀 없음 — 호출 빈도가 낮다).
 * autocommit 끔 + setReadOnly + SET TRANSACTION READ ONLY(PG·MySQL·Oracle 공통 문장) → 실행 → 성공·실패와 무관하게 ROLLBACK.
 * 파라미터 없는 사용자 SQL은 Statement로(PG jsonb ?·?|·?& 연산자 보존), 파라미터 있는 카탈로그 조회는 PreparedStatement로 실행한다.
 * 비밀번호는 URL이 아니라 Properties로만 넘긴다.
 */
@Component
@Profile("api")
public class JdbcReadOnlyQueryExecutor implements ReadOnlyQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcReadOnlyQueryExecutor.class);

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
                if (params.isEmpty()) {
                    // 사용자 SQL(query) — prepareStatement는 PostgreSQL jsonb 연산자 ?·?|·?&를 바인드 표식으로 해석한다.
                    try (Statement st = c.createStatement()) {
                        st.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                        st.setMaxRows(maxRows + 1);
                        if (!st.execute(sql)) return new QueryResult(List.of(), List.of(), false);
                        try (ResultSet rs = st.getResultSet()) {
                            return read(rs, maxRows);
                        }
                    }
                }
                // 카탈로그 조회(파라미터 있음) — PreparedStatement로 바인드
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
                } catch (SQLException e) {
                    // close가 트랜잭션을 버린다고 가정할 수 없다 — Oracle JDBC는 autocommit 꺼진 연결을 close할 때 커밋한다.
                    // 읽기 전용 트랜잭션(setReadOnly + SET TRANSACTION READ ONLY)이라 바뀐 것은 없어야 하지만 흔적은 남긴다.
                    // SQL·접속정보(호스트·계정·비밀번호)는 남기지 않는다.
                    log.warn("DB MCP 롤백 실패 server={} sqlState={} code={}", ref.serverName(), e.getSQLState(), e.getErrorCode());
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
