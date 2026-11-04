package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 쓰기 실행기 (스펙 2026-11-03 §6.4) — 호출마다 연결, autocommit·readOnly 끔, 커밋하지 않고 닫으면 롤백을 close보다 먼저
 * (Oracle은 autocommit 꺼진 연결을 close할 때 커밋한다). H2로 실제 커밋·롤백을, mock Connection으로 호출 순서를 본다.
 */
class JdbcWriteExecutorTest {

    private static final DbConnectionRef PG =
            new DbConnectionRef("db-1", "개발 (PostgreSQL)", "POSTGRESQL", "h", 5432, "app", "u", "pw");
    private static final DbConnectionRef MY =
            new DbConnectionRef("db-2", "개발 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
    private static final DbConnectionRef ORA =
            new DbConnectionRef("db-3", "개발 (Oracle)", "ORACLE", "h", 1521, "XE", "u", "pw");

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:rw" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INT)");
        }
    }

    private JdbcWriteExecutor h2() {
        return new JdbcWriteExecutor(ref -> DriverManager.getConnection(url));
    }

    private long count() throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void 커밋하면_남고_커밋없이_닫으면_사라진다() throws SQLException {
        try (DbWriteSession s = h2().open(PG)) {
            assertThat(s.executeUpdate("INSERT INTO t VALUES (1), (2)")).isEqualTo(2L);
            s.commit();
        }
        assertThat(count()).isEqualTo(2L);

        try (DbWriteSession s = h2().open(PG)) {
            assertThat(s.executeUpdate("DELETE FROM t")).isEqualTo(2L);
        }
        assertThat(count()).isEqualTo(2L);
    }

    @Test
    void 커밋없이_닫으면_rollback이_close보다_먼저() throws SQLException {
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeUpdate("UPDATE t SET a = 1 WHERE b = 2")).thenReturn(5);

        DbWriteSession s = new JdbcWriteExecutor(ref -> c).open(PG);
        assertThat(s.executeUpdate("UPDATE t SET a = 1 WHERE b = 2")).isEqualTo(5L);
        s.close();

        InOrder o = inOrder(c, st);
        o.verify(c).setAutoCommit(false);
        o.verify(c).setReadOnly(false);
        o.verify(st).setEscapeProcessing(false);
        o.verify(st).setQueryTimeout(30);
        o.verify(st).executeUpdate("UPDATE t SET a = 1 WHERE b = 2");
        o.verify(c).rollback();
        o.verify(c).close();
        verify(c, never()).commit();
    }

    @Test
    void 커밋한_뒤_닫으면_rollback하지_않는다() throws SQLException {
        Connection c = mock(Connection.class);
        when(c.createStatement()).thenReturn(mock(Statement.class));

        DbWriteSession s = new JdbcWriteExecutor(ref -> c).open(PG);
        s.executeUpdate("UPDATE t SET a = 1 WHERE b = 2");
        s.commit();
        s.close();

        InOrder o = inOrder(c);
        o.verify(c).commit();
        o.verify(c).close();
        verify(c, never()).rollback();
    }

    @Test
    void 롤백이_실패해도_닫고_예외를_던지지_않는다() throws SQLException {
        Connection c = mock(Connection.class);
        doThrow(new SQLException("broken")).when(c).rollback();

        DbWriteSession s = new JdbcWriteExecutor(ref -> c).open(PG);
        s.close();

        verify(c).close();
    }

    /** 최종 리뷰 Important 3 — 드라이버가 rollback·close에서 비검사 예외를 던져도 close는 던지지 않는다(인터페이스 약속). */
    @Test
    void 닫기에서_비검사_예외가_나도_던지지_않는다() throws SQLException {
        Connection c = mock(Connection.class);
        doThrow(new IllegalStateException("driver bug")).when(c).rollback();
        doThrow(new IllegalStateException("driver bug")).when(c).close();

        DbWriteSession s = new JdbcWriteExecutor(ref -> c).open(PG);
        assertThatCode(s::close).doesNotThrowAnyException();
        verify(c).rollback();
        verify(c).close();

        // 커밋한 뒤 close만 던지는 경우도 같다
        Connection committed = mock(Connection.class);
        doThrow(new IllegalStateException("driver bug")).when(committed).close();
        DbWriteSession s2 = new JdbcWriteExecutor(ref -> committed).open(PG);
        s2.commit();
        assertThatCode(s2::close).doesNotThrowAnyException();
        verify(committed, never()).rollback();
    }

    /** 최종 리뷰 Important 1 — Oracle만 all_users.oracle_maintained를 바인드로 조회한다. 다른 방언은 조회하지 않고 false. */
    @Test
    void 관리_계정_확인은_Oracle만_조회한다() throws SQLException {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1L, 0L);

        DbWriteSession ora = new JdbcWriteExecutor(ref -> c).open(ORA);
        assertThat(ora.systemSchema("MDSYS")).isTrue();
        assertThat(ora.systemSchema("APP")).isFalse();
        verify(c, times(2)).prepareStatement(
                "SELECT COUNT(*) FROM all_users WHERE username = ? AND oracle_maintained = 'Y'");
        verify(ps).setString(1, "MDSYS");
        verify(ps).setString(1, "APP");
        verify(ps, times(2)).setQueryTimeout(30);

        Connection pgConn = mock(Connection.class);
        Connection myConn = mock(Connection.class);
        assertThat(new JdbcWriteExecutor(ref -> pgConn).open(PG).systemSchema("pg_catalog")).isFalse();
        assertThat(new JdbcWriteExecutor(ref -> myConn).open(MY).systemSchema("mysql")).isFalse();
        verify(pgConn, never()).prepareStatement(anyString());
        verify(myConn, never()).prepareStatement(anyString());
    }

    @Test
    void 연결_설정이_실패하면_연결을_닫고_예외() throws SQLException {
        Connection c = mock(Connection.class);
        doThrow(new SQLException("read only")).when(c).setReadOnly(false);

        assertThatThrownBy(() -> new JdbcWriteExecutor(ref -> c).open(PG)).isInstanceOf(SQLException.class);
        verify(c).close();
    }

    /** 기본 스키마·대소문자는 처음 물을 때 한 번만 조회한다. */
    private static Connection scalar(Statement st, String sql, Object value) throws SQLException {
        Connection c = mock(Connection.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeQuery(sql)).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        if (value instanceof String s) when(rs.getString(1)).thenReturn(s);
        if (value instanceof Long n) when(rs.getLong(1)).thenReturn(n);
        return c;
    }

    @Test
    void 기본스키마는_방언별로_한번_조회() throws SQLException {
        Statement pgSt = mock(Statement.class);
        DbWriteSession pg = new JdbcWriteExecutor(ref -> scalar(pgSt, "SELECT current_schema()", "public")).open(PG);
        assertThat(pg.defaultSchema()).isEqualTo("public");
        assertThat(pg.defaultSchema()).isEqualTo("public");
        verify(pgSt, times(1)).executeQuery("SELECT current_schema()");
        assertThat(pg.lowerCaseNames()).isFalse();

        Statement mySt = mock(Statement.class);
        DbWriteSession my = new JdbcWriteExecutor(ref -> scalar(mySt, "SELECT DATABASE()", "app")).open(MY);
        assertThat(my.defaultSchema()).isEqualTo("app");

        Statement oraSt = mock(Statement.class);
        DbWriteSession ora = new JdbcWriteExecutor(ref -> scalar(oraSt,
                "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL", "APP")).open(ORA);
        assertThat(ora.defaultSchema()).isEqualTo("APP");
        assertThat(ora.lowerCaseNames()).isFalse();
    }

    @Test
    void MySQL_lower_case_table_names가_0이_아니면_소문자서버() throws SQLException {
        Statement on = mock(Statement.class);
        DbWriteSession lower = new JdbcWriteExecutor(ref -> scalar(on, "SELECT @@lower_case_table_names", 1L)).open(MY);
        assertThat(lower.lowerCaseNames()).isTrue();
        assertThat(lower.lowerCaseNames()).isTrue();
        verify(on, times(1)).executeQuery("SELECT @@lower_case_table_names");

        Statement off = mock(Statement.class);
        DbWriteSession exact = new JdbcWriteExecutor(ref -> scalar(off, "SELECT @@lower_case_table_names", 0L)).open(MY);
        assertThat(exact.lowerCaseNames()).isFalse();
    }

    @Test
    void 존재확인은_카탈로그_조회를_바인드로() throws SQLException {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        CatalogSql.Query typed = CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t_new", DbObjectType.TABLE, false);
        CatalogSql.Query any = CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t_new", false);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1L, 0L);

        DbWriteSession s = new JdbcWriteExecutor(ref -> c).open(PG);
        assertThat(s.exists(new DbObjectName("public", "t_new"), DbObjectType.TABLE)).isTrue();
        assertThat(s.exists(new DbObjectName("public", "t_new"))).isFalse();

        verify(c).prepareStatement(typed.sql());
        verify(c).prepareStatement(any.sql());
        verify(ps, times(2)).setString(1, "public");
        verify(ps, times(2)).setString(2, "t_new");
        verify(ps, times(2)).setQueryTimeout(30);
    }
}
