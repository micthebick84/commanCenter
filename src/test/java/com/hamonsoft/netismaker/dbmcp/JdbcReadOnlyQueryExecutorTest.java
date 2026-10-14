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
