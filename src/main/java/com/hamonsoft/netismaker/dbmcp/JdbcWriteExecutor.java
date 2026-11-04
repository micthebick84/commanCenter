package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbObjectType;
import com.hamonsoft.netismaker.entity.DbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 쓰기 실행 (스펙 2026-11-03 §6.4). 호출당 1연결(풀 없음), autocommit·readOnly 둘 다 끄고 한 트랜잭션으로 실행한다.
 * 접속은 읽기 실행기와 같은 규칙({@link JdbcReadOnlyQueryExecutor#openJdbc} — 비밀번호는 Properties로만, 접속 10초·소켓 40초).
 * 사용자 SQL은 Statement로, JDBC 이스케이프 처리({fn …}·{d …})는 끈다 — 분류한 원문과 서버가 받는 문장이 같아야 한다.
 */
@Component
@Profile("api")
public class JdbcWriteExecutor implements DbWriteExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcWriteExecutor.class);

    private final JdbcReadOnlyQueryExecutor.ConnectionOpener opener;

    public JdbcWriteExecutor() {
        this(JdbcReadOnlyQueryExecutor::openJdbc);
    }

    /** 시험용 — H2·mock 연결. */
    JdbcWriteExecutor(JdbcReadOnlyQueryExecutor.ConnectionOpener opener) {
        this.opener = opener;
    }

    @Override
    public DbWriteSession open(DbConnectionRef ref) throws SQLException {
        SqlDialect d = SqlDialect.of(DbType.valueOf(ref.dbType()));
        Connection c = opener.open(ref);
        try {
            c.setAutoCommit(false);
            c.setReadOnly(false);
            return new Session(c, d, ref.serverName());
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static final class Session implements DbWriteSession {

        private final Connection c;
        private final SqlDialect d;
        private final String serverName;
        private boolean defaultSchemaRead;
        private String defaultSchema;
        private Boolean lowerCaseNames;
        /** 마지막 executeUpdate 뒤에 커밋했는지 — 아니면 close가 먼저 롤백한다. */
        private boolean committed;

        Session(Connection c, SqlDialect d, String serverName) {
            this.c = c;
            this.d = d;
            this.serverName = serverName;
        }

        @Override
        public String defaultSchema() throws SQLException {
            if (!defaultSchemaRead) {
                defaultSchema = switch (d) {
                    case POSTGRES -> queryString("SELECT current_schema()");
                    case MYSQL -> queryString("SELECT DATABASE()");
                    case ORACLE -> queryString("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL");
                };
                defaultSchemaRead = true;
            }
            return defaultSchema;
        }

        @Override
        public boolean lowerCaseNames() throws SQLException {
            if (lowerCaseNames == null) {
                lowerCaseNames = d == SqlDialect.MYSQL && queryLong("SELECT @@lower_case_table_names") != 0;
            }
            return lowerCaseNames;
        }

        @Override
        public boolean exists(DbObjectName name) throws SQLException {
            return count(CatalogSql.objectExists(d, name.schema(), name.name(), lowerCaseNames())) > 0;
        }

        @Override
        public boolean exists(DbObjectName name, DbObjectType type) throws SQLException {
            return count(CatalogSql.objectExists(d, name.schema(), name.name(), type, lowerCaseNames())) > 0;
        }

        @Override
        public long executeUpdate(String sql) throws SQLException {
            committed = false;
            try (Statement st = c.createStatement()) {
                st.setEscapeProcessing(false);
                st.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                return st.executeUpdate(sql);
            }
        }

        @Override
        public void commit() throws SQLException {
            c.commit();
            committed = true;
        }

        @Override
        public void close() {
            try {
                if (!committed) c.rollback();
            } catch (SQLException e) {
                // SQL·접속정보(호스트·계정·비밀번호)는 남기지 않는다.
                log.warn("DB 쓰기 롤백 실패 server={} sqlState={} code={}", serverName, e.getSQLState(), e.getErrorCode());
            } finally {
                try {
                    c.close();
                } catch (SQLException e) {
                    log.warn("DB 쓰기 연결 닫기 실패 server={} sqlState={} code={}", serverName, e.getSQLState(), e.getErrorCode());
                }
            }
        }

        private long count(CatalogSql.Query q) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                ps.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                for (int i = 0; i < q.params().size(); i++) ps.setString(i + 1, q.params().get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }

        private String queryString(String sql) throws SQLException {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }

        private long queryLong(String sql) throws SQLException {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }
}
