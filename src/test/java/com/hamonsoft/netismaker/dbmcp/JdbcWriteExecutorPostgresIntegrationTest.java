package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;
import com.hamonsoft.netismaker.entity.DbObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쓰기 실행기를 실제 PostgreSQL에 (스펙 2026-11-03 §6.4, Task 7 리뷰 반영 ②) — 시험 컨테이너 DB의 별도 스키마 두 개에서
 * 커밋·롤백과 카탈로그 존재 확인(종류별 relkind)을 본다. 앱 스키마(com)는 건드리지 않는다.
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class JdbcWriteExecutorPostgresIntegrationTest {

    private static final DbConnectionRef REF =
            new DbConnectionRef("db-1", "시험 (PostgreSQL)", "POSTGRESQL", "h", 5432, "netismaker_test", "test", "test");

    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.username}") private String username;
    @Value("${spring.datasource.password}") private String password;

    private Connection raw() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private JdbcWriteExecutor executor() {
        return new JdbcWriteExecutor(ref -> raw());
    }

    private void sql(String... statements) throws SQLException {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private long scalar(String query) throws SQLException {
        try (Connection c = raw(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @BeforeEach
    void schemas() throws SQLException {
        dropSchemas();
        sql("CREATE SCHEMA dbw_a", "CREATE SCHEMA dbw_b");
    }

    @AfterEach
    void dropSchemas() throws SQLException {
        sql("DROP SCHEMA IF EXISTS dbw_a CASCADE", "DROP SCHEMA IF EXISTS dbw_b CASCADE");
    }

    @Test
    void CREATE_TABLE을_커밋하면_존재하고_상한초과_UPDATE는_close뒤_그대로() throws SQLException {
        try (DbWriteSession s = executor().open(REF)) {
            assertThat(s.exists(new DbObjectName("dbw_a", "t_new"))).isFalse();
            s.executeUpdate("CREATE TABLE dbw_a.t_new (id int)");
            assertThat(s.executeUpdate("INSERT INTO dbw_a.t_new SELECT g FROM generate_series(1, 5) g")).isEqualTo(5L);
            s.commit();
        }
        try (DbWriteSession s = executor().open(REF)) {
            assertThat(s.exists(new DbObjectName("dbw_a", "t_new"))).isTrue();
            assertThat(s.exists(new DbObjectName("dbw_a", "t_new"), DbObjectType.TABLE)).isTrue();
        }

        // 상한을 넘긴 UPDATE는 커밋하지 않고 닫는다 → 롤백
        try (DbWriteSession s = executor().open(REF)) {
            assertThat(s.executeUpdate("UPDATE dbw_a.t_new SET id = id + 100")).isEqualTo(5L);
        }
        assertThat(scalar("SELECT SUM(id) FROM dbw_a.t_new")).isEqualTo(15L);
    }

    @Test
    void 존재확인은_그_스키마_그_종류만() throws SQLException {
        sql("CREATE TABLE dbw_a.t (id int)", "CREATE VIEW dbw_b.t AS SELECT 1 AS x", "CREATE SEQUENCE dbw_b.s");

        try (DbWriteSession s = executor().open(REF)) {
            assertThat(s.exists(new DbObjectName("dbw_a", "t"), DbObjectType.TABLE)).isTrue();
            assertThat(s.exists(new DbObjectName("dbw_b", "t"), DbObjectType.TABLE)).isFalse();
            assertThat(s.exists(new DbObjectName("dbw_b", "t"), DbObjectType.VIEW)).isTrue();
            assertThat(s.exists(new DbObjectName("dbw_a", "t"), DbObjectType.VIEW)).isFalse();
            assertThat(s.exists(new DbObjectName("dbw_b", "s"), DbObjectType.SEQUENCE)).isTrue();
            assertThat(s.exists(new DbObjectName("dbw_b", "s"), DbObjectType.TABLE)).isFalse();
            // 종류와 무관한 확인 — 이름이 그 스키마에 쓰였는지
            assertThat(s.exists(new DbObjectName("dbw_b", "t"))).isTrue();
            assertThat(s.exists(new DbObjectName("dbw_b", "T"))).isFalse();   // 정규화한 이름을 그대로(정확 비교)
            // 쓰기 서비스가 PostgreSQL CREATE 전에 보는 시스템 카탈로그 이름
            assertThat(s.exists(new DbObjectName("pg_catalog", "pg_class"))).isTrue();
        }
    }

    @Test
    void 기본_스키마와_대소문자() throws SQLException {
        try (DbWriteSession s = executor().open(REF)) {
            assertThat(s.defaultSchema()).isNotBlank();
            assertThat(s.lowerCaseNames()).isFalse();
        }
    }
}
