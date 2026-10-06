package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.DbType;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDbConnectionTesterTest {

    private static DbConnectionTester.Target target(DbType t, int port) {
        return new DbConnectionTester.Target(t, "127.0.0.1", port, "app", "reader", "S3cr3t-pw");
    }

    @Test
    void props_carry_credentials_and_timeouts_per_driver() {
        Properties pg = JdbcDbConnectionTester.propsFor(target(DbType.POSTGRESQL, 5432));
        assertThat(pg.getProperty("user")).isEqualTo("reader");
        assertThat(pg.getProperty("password")).isEqualTo("S3cr3t-pw");
        assertThat(pg.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.MYSQL, 3306)).getProperty("connectTimeout")).isEqualTo("5000");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.MARIADB, 3306)).getProperty("connectTimeout")).isEqualTo("5000");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.ORACLE, 1521)).getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
    }

    @Test
    void mask_replaces_every_occurrence_of_the_secret() {
        assertThat(JdbcDbConnectionTester.mask("bad pw S3cr3t-pw for S3cr3t-pw", "S3cr3t-pw"))
                .isEqualTo("bad pw **** for ****");
        assertThat(JdbcDbConnectionTester.mask("msg", "")).isEqualTo("msg");
    }

    @Test
    void target_to_string_hides_password() {
        assertThat(target(DbType.ORACLE, 1521).toString()).doesNotContain("S3cr3t-pw").contains("127.0.0.1:1521/app");
    }

    @Test
    void closed_port_fails_fast_without_leaking_password() {
        // 포트 1은 닫혀 있다 — 드라이버가 즉시 connection refused. 결과 메시지에 비밀번호가 없어야 한다.
        DbConnectionTester.Result r = new JdbcDbConnectionTester().test(target(DbType.POSTGRESQL, 1));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).startsWith("접속 실패: ").doesNotContain("S3cr3t-pw");
    }
}
