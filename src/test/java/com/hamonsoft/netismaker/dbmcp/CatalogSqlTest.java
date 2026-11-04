package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSqlTest {

    @Test
    void placeholder_count_matches_params_for_every_dialect_and_kind() {
        for (SqlDialect d : SqlDialect.values()) {
            for (CatalogSql.Kind k : CatalogSql.Kind.values()) {
                CatalogSql.Query q = CatalogSql.of(d, k, null, "users");
                long marks = q.sql().chars().filter(c -> c == '?').count();
                assertThat(marks).as(d + " " + k).isEqualTo(q.params().size());
            }
        }
    }

    @Test
    void user_values_are_bound_not_concatenated() {
        CatalogSql.Query q = CatalogSql.of(SqlDialect.MYSQL, CatalogSql.Kind.COLUMNS, "app'; DROP", "t");
        assertThat(q.sql()).doesNotContain("DROP");
        assertThat(q.params()).containsExactly("app'; DROP", "t");
    }

    @Test
    void pg_tables_binds_schema_twice() {
        CatalogSql.Query q = CatalogSql.of(SqlDialect.POSTGRES, CatalogSql.Kind.TABLES, "public", null);
        assertThat(q.params()).containsExactly("public", "public");
    }

    /** 쓰기 실행기의 존재 확인 (스펙 2026-11-03 §6.3, Task 7 리뷰 반영 ②) — 값은 늘 바인드, 정규화한 이름을 그대로 비교한다. */
    @Test
    void object_exists_binds_schema_and_name_for_every_dialect() {
        for (SqlDialect d : SqlDialect.values()) {
            for (boolean lower : new boolean[]{false, true}) {
                CatalogSql.Query any = CatalogSql.objectExists(d, "app'; DROP", "t_new", lower);
                assertThat(any.sql()).as(d + " " + lower).doesNotContain("DROP").startsWith("SELECT COUNT(*)");
                assertThat(any.params()).containsExactly("app'; DROP", "t_new");
                for (DbObjectType t : DbObjectType.values()) {
                    CatalogSql.Query typed = CatalogSql.objectExists(d, "app", "t_new", t, lower);
                    assertThat(typed.sql().chars().filter(c -> c == '?').count()).as(d + " " + t).isEqualTo(2);
                    assertThat(typed.params()).containsExactly("app", "t_new");
                }
            }
        }
    }

    @Test
    void object_exists_postgres_uses_pg_class_and_relkind() {
        assertThat(CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t", false).sql())
                .isEqualTo("SELECT COUNT(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n "
                        + "ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?");
        assertThat(CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t", DbObjectType.TABLE, false).sql())
                .endsWith(" AND c.relkind IN ('r', 'p')");
        assertThat(CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t", DbObjectType.VIEW, false).sql())
                .endsWith(" AND c.relkind IN ('v', 'm')");
        assertThat(CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t", DbObjectType.SEQUENCE, false).sql())
                .endsWith(" AND c.relkind IN ('S')");
        // 대소문자 접기는 MySQL 소문자 서버에만
        assertThat(CatalogSql.objectExists(SqlDialect.POSTGRES, "public", "t", true).sql()).doesNotContain("LOWER");
    }

    @Test
    void object_exists_mysql_compares_lower_only_on_lower_case_server() {
        assertThat(CatalogSql.objectExists(SqlDialect.MYSQL, "app", "t", false).sql())
                .isEqualTo("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?");
        assertThat(CatalogSql.objectExists(SqlDialect.MYSQL, "app", "t", true).sql())
                .isEqualTo("SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE LOWER(TABLE_SCHEMA) = ? AND LOWER(TABLE_NAME) = ?");
        assertThat(CatalogSql.objectExists(SqlDialect.MYSQL, "app", "t", DbObjectType.TABLE, true).sql())
                .endsWith(" AND TABLE_TYPE IN ('BASE TABLE', 'SYSTEM VERSIONED')");
        assertThat(CatalogSql.objectExists(SqlDialect.MYSQL, "app", "t", DbObjectType.VIEW, false).sql())
                .endsWith(" AND TABLE_TYPE IN ('VIEW')");
        assertThat(CatalogSql.objectExists(SqlDialect.MYSQL, "app", "t", DbObjectType.SEQUENCE, false).sql())
                .endsWith(" AND TABLE_TYPE IN ('SEQUENCE')");
    }

    @Test
    void object_exists_oracle_counts_only_that_kind_never_synonym() {
        assertThat(CatalogSql.objectExists(SqlDialect.ORACLE, "APP", "T", false).sql())
                .isEqualTo("SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_name = ?");
        assertThat(CatalogSql.objectExists(SqlDialect.ORACLE, "APP", "T", DbObjectType.TABLE, true).sql())
                .endsWith(" AND object_type IN ('TABLE')").doesNotContain("LOWER").doesNotContain("UPPER");
        assertThat(CatalogSql.objectExists(SqlDialect.ORACLE, "APP", "T", DbObjectType.VIEW, false).sql())
                .endsWith(" AND object_type IN ('VIEW')");
        assertThat(CatalogSql.objectExists(SqlDialect.ORACLE, "APP", "T", DbObjectType.SEQUENCE, false).sql())
                .endsWith(" AND object_type IN ('SEQUENCE')");
    }
}
