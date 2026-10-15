package com.hamonsoft.netismaker.dbmcp;

import java.util.Arrays;
import java.util.List;

/**
 * list_tables·describe_table용 방언별 SQL — 인터뷰 서비스 db/catalog.ts 이식.
 * 사용자 값(schema·table)은 바인딩 파라미터로만. schema가 null이면 기본 스키마.
 */
public final class CatalogSql {

    public enum Kind { TABLES, COLUMNS, INDEXES }

    public record Query(String sql, List<String> params) {}

    private static final int LIMIT = DbMcpLimits.MAX_CATALOG_ROWS + 1;

    private CatalogSql() {}

    public static Query of(SqlDialect d, Kind k, String schema, String table) {
        String t = table == null ? "" : table;
        return switch (d) {
            case POSTGRES -> switch (k) {
                case TABLES -> new Query(
                        "SELECT table_schema, table_name, table_type FROM information_schema.tables "
                                + "WHERE (CAST(? AS text) IS NULL AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                                + "AND table_schema NOT LIKE 'pg_toast%') OR table_schema = ? "
                                + "ORDER BY table_schema, table_name LIMIT " + LIMIT,
                        Arrays.asList(schema, schema));
                case COLUMNS -> new Query(
                        "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, "
                                + "col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS comment "
                                + "FROM information_schema.columns c WHERE c.table_schema = COALESCE(CAST(? AS text), current_schema()) "
                                + "AND c.table_name = ? ORDER BY c.ordinal_position LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT indexname, indexdef FROM pg_indexes "
                                + "WHERE schemaname = COALESCE(CAST(? AS text), current_schema()) AND tablename = ? "
                                + "ORDER BY indexname LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
            };
            case MYSQL -> switch (k) {
                case TABLES -> new Query(
                        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) ORDER BY TABLE_NAME LIMIT " + LIMIT,
                        Arrays.asList(schema));
                case COLUMNS -> new Query(
                        "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
                                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) "
                                + "AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? "
                                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX LIMIT " + LIMIT,
                        Arrays.asList(schema, t));
            };
            // Oracle: 비인용 식별자는 대문자 저장 — UPPER로 비교. 행 상한은 실행기 setMaxRows가 건다.
            case ORACLE -> switch (k) {
                case TABLES -> new Query(
                        "SELECT owner, object_name, object_type FROM all_objects "
                                + "WHERE owner = NVL(UPPER(?), USER) AND object_type IN ('TABLE', 'VIEW') ORDER BY object_name",
                        Arrays.asList(schema));
                case COLUMNS -> new Query(
                        "SELECT c.column_name, c.data_type, c.data_length, c.nullable, c.data_default, m.comments "
                                + "FROM all_tab_columns c LEFT JOIN all_col_comments m ON m.owner = c.owner "
                                + "AND m.table_name = c.table_name AND m.column_name = c.column_name "
                                + "WHERE c.owner = NVL(UPPER(?), USER) AND c.table_name = UPPER(?) ORDER BY c.column_id",
                        Arrays.asList(schema, t));
                case INDEXES -> new Query(
                        "SELECT i.index_name, i.uniqueness, ic.column_position, ic.column_name FROM all_indexes i "
                                + "JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name "
                                + "WHERE i.table_owner = NVL(UPPER(?), USER) AND i.table_name = UPPER(?) "
                                + "ORDER BY i.index_name, ic.column_position",
                        Arrays.asList(schema, t));
            };
        };
    }
}
