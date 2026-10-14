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

    /** 바인드 파라미터가 있는 경로(카탈로그 조회) — PreparedStatement. */
    @Test
    void always_rolls_back_and_sets_read_only_even_on_failure() throws SQLException {
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.createStatement()).thenReturn(st);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenThrow(new SQLException("boom"));
        JdbcReadOnlyQueryExecutor ex = new JdbcReadOnlyQueryExecutor(ref -> c, true);

        assertThatThrownBy(() -> ex.run(REF, "SELECT 1 FROM t WHERE a = ?", List.of("x"), 10))
                .isInstanceOf(SQLException.class);

        InOrder o = inOrder(c, st, ps);
        o.verify(c).setAutoCommit(false);
        o.verify(c).setReadOnly(true);
        o.verify(st).execute("SET TRANSACTION READ ONLY");
        o.verify(ps).setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
        o.verify(ps).setMaxRows(11);
        o.verify(ps).setString(1, "x");
        o.verify(c).rollback();
        verify(c).close();
    }

    /**
     * 파라미터 없는 경로(사용자 query) — PostgreSQL jsonb 연산자 ?·?|·?&가 바인드 표식으로 해석되지 않게
     * prepareStatement를 쓰지 않고 Statement로 실행한다. 읽기 전용·타임아웃·상한·ROLLBACK은 같다.
     */
    @Test
    void paramless_sql_runs_via_plain_statement_with_same_guards() throws SQLException {
        Connection c = mock(Connection.class);
        Statement txSt = mock(Statement.class);
        Statement qSt = mock(Statement.class);
        when(c.createStatement()).thenReturn(txSt, qSt);
        String sql = "SELECT * FROM t WHERE doc ? 'k' AND doc ?| array['a']";
        when(qSt.execute(sql)).thenThrow(new SQLException("boom"));
        JdbcReadOnlyQueryExecutor ex = new JdbcReadOnlyQueryExecutor(ref -> c, true);

        assertThatThrownBy(() -> ex.run(REF, sql, List.of(), 10)).isInstanceOf(SQLException.class);

        verify(c, never()).prepareStatement(anyString());
        InOrder o = inOrder(c, txSt, qSt);
        o.verify(c).setAutoCommit(false);
        o.verify(c).setReadOnly(true);
        o.verify(txSt).execute("SET TRANSACTION READ ONLY");
        o.verify(qSt).setQueryTimeout(DbMcpLimits.STATEMENT_TIMEOUT_SECONDS);
        o.verify(qSt).setMaxRows(11);
        o.verify(qSt).execute(sql);
        o.verify(c).rollback();
        verify(c).close();
    }

    @Test
    void paramless_select_reads_rows_on_h2() throws SQLException {
        QueryResult r = h2().run(REF, "SELECT id FROM t WHERE id < 2 ORDER BY id", List.of(), 10);
        assertThat(r.rows()).containsExactly(List.of(0), List.of(1));
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void rollback_failure_does_not_mask_result() throws SQLException {
        Connection c = spy(DriverManager.getConnection(url));
        doThrow(new SQLException("rollback failed")).when(c).rollback();
        JdbcReadOnlyQueryExecutor ex = new JdbcReadOnlyQueryExecutor(ref -> c, false);

        QueryResult r = ex.run(REF, "SELECT id FROM t WHERE id = 3", List.of(), 10);
        assertThat(r.rows()).containsExactly(List.of(3));
        verify(c).close();
    }
}
