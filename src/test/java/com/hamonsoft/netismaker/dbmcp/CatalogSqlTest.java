package com.hamonsoft.netismaker.dbmcp;

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
}
