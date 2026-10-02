import type { SqlDialect } from '../sqlReadOnly.js';
import { DB_LIMITS } from './limits.js';

/**
 * list_tables·describe_table용 방언별 SQL (스펙 2026-10-02 §6.2).
 * 사용자 값(schema·table)은 바인딩 파라미터로만 — 문자열 조립 금지. schema가 null이면 기본 스키마.
 * 파라미터 순서: tables = [schema], columns·indexes = [schema, table].
 */
export type CatalogKind = 'tables' | 'columns' | 'indexes';

const LIMIT = DB_LIMITS.maxCatalogRows + 1;

const SQL: Record<SqlDialect, Record<CatalogKind, string>> = {
  postgres: {
    tables:
      `SELECT table_schema, table_name, table_type FROM information_schema.tables ` +
      `WHERE ($1::text IS NULL AND table_schema NOT IN ('pg_catalog', 'information_schema') AND table_schema NOT LIKE 'pg_toast%') ` +
      `OR table_schema = $1 ORDER BY table_schema, table_name LIMIT ${LIMIT}`,
    columns:
      `SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, ` +
      `col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS comment ` +
      `FROM information_schema.columns c WHERE c.table_schema = COALESCE($1::text, current_schema()) AND c.table_name = $2 ` +
      `ORDER BY c.ordinal_position LIMIT ${LIMIT}`,
    indexes:
      `SELECT indexname, indexdef FROM pg_indexes ` +
      `WHERE schemaname = COALESCE($1::text, current_schema()) AND tablename = $2 ORDER BY indexname LIMIT ${LIMIT}`,
  },
  mysql: {
    tables:
      `SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) ORDER BY TABLE_NAME LIMIT ${LIMIT}`,
    columns:
      `SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT FROM information_schema.COLUMNS ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION LIMIT ${LIMIT}`,
    indexes:
      `SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX LIMIT ${LIMIT}`,
  },
  // Oracle은 비인용 식별자가 대문자로 저장된다 — 이름을 UPPER로 비교(따옴표로 만든 소문자 이름은 못 찾는다, 스펙 §6.2).
  // 행 상한은 execute maxRows가 건다.
  oracle: {
    tables:
      `SELECT owner, object_name, object_type FROM all_objects ` +
      `WHERE owner = NVL(UPPER(:1), USER) AND object_type IN ('TABLE', 'VIEW') ORDER BY object_name`,
    columns:
      `SELECT c.column_name, c.data_type, c.data_length, c.nullable, c.data_default, m.comments FROM all_tab_columns c ` +
      `LEFT JOIN all_col_comments m ON m.owner = c.owner AND m.table_name = c.table_name AND m.column_name = c.column_name ` +
      `WHERE c.owner = NVL(UPPER(:1), USER) AND c.table_name = UPPER(:2) ORDER BY c.column_id`,
    indexes:
      `SELECT i.index_name, i.uniqueness, ic.column_position, ic.column_name FROM all_indexes i ` +
      `JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name ` +
      `WHERE i.table_owner = NVL(UPPER(:1), USER) AND i.table_name = UPPER(:2) ORDER BY i.index_name, ic.column_position`,
  },
};

export function catalogSql(
  dialect: SqlDialect,
  kind: CatalogKind,
  schema: string | null,
  table?: string,
): { sql: string; params: unknown[] } {
  return { sql: SQL[dialect][kind], params: kind === 'tables' ? [schema] : [schema, table ?? ''] };
}
