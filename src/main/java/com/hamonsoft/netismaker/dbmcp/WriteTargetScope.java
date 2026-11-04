package com.hamonsoft.netismaker.dbmcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 쓰기 대상의 경계 — 이름 규칙만으로 판정하는 부분 (최종 리뷰 Important 1). 분류 전에 보고, 걸리면 거부한다.
 * <ul>
 *   <li>모든 방언: 정규화한 스키마가 시스템 스키마면 거부 — PostgreSQL {@code pg_*}·{@code information_schema},
 *       MySQL·MariaDB {@code mysql}·{@code information_schema}·{@code performance_schema}·{@code sys},
 *       Oracle {@code SYS}·{@code SYSTEM} 등 이름이 알려진 Oracle 관리 계정. 시스템 카탈로그 DML은 설계 §6.2가 거부로 정한
 *       "사용자·역할·권한 변경"을 우회한다.</li>
 *   <li>MySQL·MariaDB: 정규화한 스키마가 연결의 기본 스키마(DATABASE())와 다르면 거부 — 이 방언은 스키마가 곧 database라
 *       연결 단위 쓰기 허가의 경계가 database다(같은 서버의 다른 database = 쓰기 허용을 켜지 않은 연결·운영 DB일 수 있다).</li>
 * </ul>
 * 대상은 문장이 바꾸는 객체 전부와 테이블 이름 변경의 새 이름이다(MySQL {@code RENAME TABLE a TO other.a}는 다른 database로 옮긴다).
 * DB를 봐야 하는 나머지 경계(PostgreSQL 비한정 이름이 pg_catalog에 닿는 경우, Oracle {@code oracle_maintained})는
 * {@link DbWriteService}가 이 검사 뒤에 본다.
 */
public final class WriteTargetScope {

    static final String SYSTEM_SCHEMA = "시스템 스키마의 객체는 쓸 수 없습니다: ";
    static final String OTHER_DATABASE = "연결의 데이터베이스 밖 객체는 쓸 수 없습니다: ";

    private static final Set<String> MYSQL_SYSTEM = Set.of("mysql", "information_schema", "performance_schema", "sys");

    /** 이름이 알려진 Oracle 관리 계정 — 빠진 것은 서비스가 all_users.oracle_maintained로 다시 본다. */
    private static final Set<String> ORACLE_SYSTEM = Set.of(
            "SYS", "SYSTEM", "AUDSYS", "CTXSYS", "DBSFWUSER", "DBSNMP", "DVF", "DVSYS", "GGSYS", "GSMADMIN_INTERNAL",
            "LBACSYS", "MDDATA", "MDSYS", "OJVMSYS", "OLAPSYS", "ORDDATA", "ORDPLUGINS", "ORDSYS", "OUTLN",
            "REMOTE_SCHEDULER_AGENT", "SI_INFORMTN_SCHEMA", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "SYS$UMF",
            "WMSYS", "XDB", "XS$NULL", "APPQOSSYS", "ANONYMOUS", "DIP", "PUBLIC");

    private WriteTargetScope() {}

    /**
     * @param names       문장의 이름을 그 연결 규칙으로 정규화한다({@link DbObjectName#normalize})
     * @param scopeSchema 스키마를 생략한 이름이 받는 스키마 — {@link DbObjectName#defaultSchemaOf}로 접은 값
     * @return 거부 사유, 괜찮으면 null
     */
    public static String check(SqlDialect d, WriteStatement st, Function<DbObjectRef, DbObjectName> names,
                               String scopeSchema) {
        for (DbObjectName n : targets(st, names)) {
            if (systemSchema(d, n.schema())) return SYSTEM_SCHEMA + qualified(n);
            if (d == SqlDialect.MYSQL && !n.schema().equals(scopeSchema)) {
                return OTHER_DATABASE + qualified(n) + " — 이 연결은 " + scopeSchema + " 안에서만 씁니다";
            }
        }
        return null;
    }

    /** 문장이 바꾸는 객체와 이름 변경의 새 이름 — 정규화한 값. */
    static List<DbObjectName> targets(WriteStatement st, Function<DbObjectRef, DbObjectName> names) {
        List<DbObjectName> out = new ArrayList<>();
        for (DbObjectRef r : st.targets()) out.add(names.apply(r));
        if (st.renameTo() != null) out.add(names.apply(st.renameTo()));
        return out;
    }

    /**
     * 정규화한 스키마가 시스템 스키마인지. PostgreSQL은 {@code pg_}로 시작하는 스키마를 시스템 몫으로 예약한다(pg_catalog·pg_toast·pg_temp…).
     * MySQL·MariaDB는 서버가 information_schema를 대소문자 없이 다루므로 대소문자를 무시하고 본다(같은 이름의 사용자 database는 없다고 본다).
     */
    static boolean systemSchema(SqlDialect d, String schema) {
        if (schema == null) return false;
        return switch (d) {
            case POSTGRES -> schema.startsWith("pg_") || schema.equals("information_schema");
            case MYSQL -> MYSQL_SYSTEM.contains(schema.toLowerCase(Locale.ROOT));
            case ORACLE -> ORACLE_SYSTEM.contains(schema);
        };
    }

    static String qualified(DbObjectName n) {
        return n.schema() + "." + n.name();
    }
}
