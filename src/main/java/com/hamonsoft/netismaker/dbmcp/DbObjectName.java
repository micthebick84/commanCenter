package com.hamonsoft.netismaker.dbmcp;

import java.util.Locale;

/**
 * 방언 규칙으로 정규화한 객체 이름 — 소유 기록(task_db_object)과 문장의 대상을 이 값으로 비교한다 (스펙 2026-11-03 §6.3).
 * 비교는 정확 일치다. 따옴표·대소문자·스키마가 하나라도 다르면 다른 객체로 본다(Review Focus 4).
 */
public record DbObjectName(String schema, String name) {

    /**
     * 비인용 이름은 PostgreSQL이 소문자, Oracle이 대문자로 접는다. MySQL은 서버가 이름을 소문자로 다룰 때
     * (lower_case_table_names ≠ 0)만 소문자로 접는다. 인용 이름은 그대로 둔다. 스키마를 생략하면 defaultSchema를 쓴다
     * (호출자가 그 연결의 현재 스키마를 DB가 돌려준 표기 그대로 넘긴다).
     */
    public static DbObjectName normalize(SqlDialect d, DbObjectRef r, String defaultSchema, boolean mysqlLowerCase) {
        String schema = r.schema() == null ? defaultSchema : fold(d, r.schema(), r.schemaQuoted(), mysqlLowerCase);
        return new DbObjectName(schema, fold(d, r.name(), r.nameQuoted(), mysqlLowerCase));
    }

    private static String fold(SqlDialect d, String text, boolean quoted, boolean mysqlLowerCase) {
        if (quoted) return text;
        return switch (d) {
            case POSTGRES -> text.toLowerCase(Locale.ROOT);
            case ORACLE -> text.toUpperCase(Locale.ROOT);
            case MYSQL -> mysqlLowerCase ? text.toLowerCase(Locale.ROOT) : text;
        };
    }
}
