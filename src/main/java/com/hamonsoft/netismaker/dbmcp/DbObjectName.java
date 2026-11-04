package com.hamonsoft.netismaker.dbmcp;

import java.util.Locale;

/**
 * 방언 규칙으로 정규화한 객체 이름 — 소유 기록(task_db_object)과 문장의 대상을 이 값으로 비교한다 (스펙 2026-11-03 §6.3).
 * 비교는 정확 일치다. 따옴표·대소문자·스키마가 하나라도 다르면 다른 객체로 본다(Review Focus 4).
 */
public record DbObjectName(String schema, String name) {

    /**
     * PostgreSQL·Oracle은 비인용 이름만 접고(PostgreSQL 소문자, Oracle 대문자) 인용 이름은 그대로 둔다.
     * MySQL·MariaDB는 따옴표가 대소문자를 보존하지 않고 lower_case_table_names만 대소문자 구분을 정한다 —
     * {@code mysqlLowerCase}(그 값 ≠ 0)이면 이름과 스키마(생략 때 쓰는 defaultSchema 포함)를 인용 여부와 무관하게
     * 소문자로 접고, 아니면(= 0) 그대로 둔다. 스키마를 생략하면 defaultSchema를 쓴다(호출자가 그 연결의 현재 스키마를
     * DB가 돌려준 표기 그대로 넘긴다).
     */
    public static DbObjectName normalize(SqlDialect d, DbObjectRef r, String defaultSchema, boolean mysqlLowerCase) {
        if (d == SqlDialect.MYSQL && mysqlLowerCase) {
            return new DbObjectName(lower(r.schema() == null ? defaultSchema : r.schema()), lower(r.name()));
        }
        String schema = r.schema() == null ? defaultSchema : fold(d, r.schema(), r.schemaQuoted());
        return new DbObjectName(schema, fold(d, r.name(), r.nameQuoted()));
    }

    private static String fold(SqlDialect d, String text, boolean quoted) {
        if (quoted) return text;
        return switch (d) {
            case POSTGRES -> text.toLowerCase(Locale.ROOT);
            case ORACLE -> text.toUpperCase(Locale.ROOT);
            case MYSQL -> text;
        };
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
