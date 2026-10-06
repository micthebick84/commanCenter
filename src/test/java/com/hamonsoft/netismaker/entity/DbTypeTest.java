package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbTypeTest {

    @Test
    void jdbc_url_per_type() {
        assertThat(DbType.POSTGRESQL.jdbcUrl("db.local", 5432, "app")).isEqualTo("jdbc:postgresql://db.local:5432/app");
        assertThat(DbType.MYSQL.jdbcUrl("db.local", 3306, "app")).isEqualTo("jdbc:mysql://db.local:3306/app");
        assertThat(DbType.MARIADB.jdbcUrl("db.local", 3307, "app")).isEqualTo("jdbc:mariadb://db.local:3307/app");
        assertThat(DbType.ORACLE.jdbcUrl("ora", 1521, "ORCLPDB1")).isEqualTo("jdbc:oracle:thin:@//ora:1521/ORCLPDB1");
    }

    @Test
    void default_ports_labels_and_probe_sql() {
        assertThat(List.of(DbType.values()).stream().map(DbType::getDefaultPort).toList())
                .containsExactly(5432, 3306, 3306, 1521);
        assertThat(DbType.MARIADB.getLabel()).isEqualTo("MariaDB");
        assertThat(DbType.ORACLE.probeSql()).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(DbType.MYSQL.probeSql()).isEqualTo("SELECT 1");
    }

    @Test
    void db_connection_to_string_never_contains_ciphertext() {
        DbConnection c = DbConnection.create(DbConnectionScope.USER, 1L, "user1", "운영 DB", DbType.POSTGRESQL,
                "db.local", 5432, "app", "reader", "CIPHERTEXT-abc", "user1");
        assertThat(c.toString()).doesNotContain("CIPHERTEXT").contains("운영 DB");
        assertThat(c.isOwnedBy("user1")).isTrue();
        assertThat(c.isOwnedBy("user2")).isFalse();
        assertThat(c.isEnabled()).isTrue();
    }
}
