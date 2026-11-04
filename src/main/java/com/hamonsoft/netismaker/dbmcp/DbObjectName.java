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
     * 코드포인트마다 소문자로 접고, 아니면(= 0) 그대로 둔다. 접기가 서버와 어긋날 수 있는 문자({@link #caseFoldMayDiffer})는
     * 분석기가 MySQL 객체 이름에서 먼저 거부한다. 스키마를 생략하면 defaultSchema를 쓴다(호출자가 그 연결의 현재 스키마를
     * DB가 돌려준 표기 그대로 넘긴다).
     */
    public static DbObjectName normalize(SqlDialect d, DbObjectRef r, String defaultSchema, boolean mysqlLowerCase) {
        if (d == SqlDialect.MYSQL && mysqlLowerCase) {
            return new DbObjectName(lower(r.schema() == null ? defaultSchema : r.schema()), lower(r.name()));
        }
        String schema = r.schema() == null ? defaultSchema : fold(d, r.schema(), r.schemaQuoted());
        return new DbObjectName(schema, fold(d, r.name(), r.nameQuoted()));
    }

    /** 스키마를 생략한 이름이 받는 스키마 — {@link #normalize}와 같은 접기(MySQL·MariaDB 소문자 서버면 소문자, 그 밖은 그대로). */
    public static String defaultSchemaOf(SqlDialect d, String defaultSchema, boolean mysqlLowerCase) {
        return d == SqlDialect.MYSQL && mysqlLowerCase ? lower(defaultSchema) : defaultSchema;
    }

    private static String fold(SqlDialect d, String text, boolean quoted) {
        if (quoted) return text;
        return switch (d) {
            case POSTGRES -> text.toLowerCase(Locale.ROOT);
            case ORACLE -> text.toUpperCase(Locale.ROOT);
            case MYSQL -> text;
        };
    }

    /**
     * 코드포인트마다 단순 대응({@link Character#toLowerCase(int)}) — 로케일과 무관하다. String.toLowerCase는 특수 규칙으로
     * İ(U+0130)를 i + U+0307 두 글자로, 단어 끝 Σ를 ς로 바꿔 MySQL·MariaDB의 이름 접기(İ→i, Σ→σ)와 어긋난다.
     */
    private static String lower(String text) {
        if (text == null) return null;
        StringBuilder folded = new StringBuilder(text.length());
        text.codePoints().map(Character::toLowerCase).forEach(folded::appendCodePoint);
        return folded.toString();
    }

    /**
     * ASCII 밖에서 대소문자가 있는(또는 이 JVM이 모르는) 문자가 들었는지. 그런 문자의 소문자 접기는 Unicode 판마다 다르다 —
     * 예: 조지아 Ა(U+1C90, Unicode 11)·체로키 Ꭰ(U+13A0, Unicode 8)는 JDK 21이 접지만, 서버의 이름 접기 표가 그보다 옛 판이면 접지 않는다.
     * 그러면 이 JVM이 접은 이름(소유 기록)과 서버가 접은 이름(실제 객체)이 달라진다. 분석기가 MySQL 객체 이름에서 거부한다.
     */
    static boolean caseFoldMayDiffer(String text) {
        return text.codePoints().anyMatch(cp -> cp > 0x7F && (Character.getType(cp) == Character.UNASSIGNED
                || Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp)
                || Character.toLowerCase(cp) != cp || Character.toUpperCase(cp) != cp));
    }
}
