package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import static com.hamonsoft.netismaker.dbmcp.SqlDialect.MYSQL;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.ORACLE;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/** 방언별 이름 정규화 — 소유 기록과 문장의 대상을 같은 규칙으로 비교한다 (스펙 2026-11-03 §6.3). */
class DbObjectNameTest {

    private static DbObjectRef ref(String name) {
        return new DbObjectRef(null, false, name, false);
    }

    private static DbObjectRef quoted(String name) {
        return new DbObjectRef(null, false, name, true);
    }

    @Test
    void PostgreSQL_비인용은_소문자_인용은_그대로() {
        assertThat(DbObjectName.normalize(POSTGRES, ref("T_New"), "public", true))
                .isEqualTo(new DbObjectName("public", "t_new"));
        assertThat(DbObjectName.normalize(POSTGRES, quoted("T_New"), "public", true))
                .isEqualTo(new DbObjectName("public", "T_New"));
    }

    @Test
    void Oracle_비인용은_대문자_인용은_그대로() {
        assertThat(DbObjectName.normalize(ORACLE, ref("t_new"), "APP", false))
                .isEqualTo(new DbObjectName("APP", "T_NEW"));
        assertThat(DbObjectName.normalize(ORACLE, quoted("t_new"), "APP", false))
                .isEqualTo(new DbObjectName("APP", "t_new"));
    }

    /** lower_case_table_names ≠ 0인 MySQL·MariaDB는 백틱 인용과 무관하게 테이블·DB 이름을 소문자로 다룬다. */
    @Test
    void MySQL_소문자_서버는_인용도_소문자() {
        assertThat(DbObjectName.normalize(MYSQL, ref("Foo"), "app", true).name()).isEqualTo("foo");
        assertThat(DbObjectName.normalize(MYSQL, quoted("Foo"), "app", true).name()).isEqualTo("foo");
        assertThat(DbObjectName.normalize(MYSQL, ref("t"), "App", true).schema()).isEqualTo("app");
        assertThat(DbObjectName.normalize(MYSQL, new DbObjectRef("APP", true, "T_New", true), "app", true))
                .isEqualTo(new DbObjectName("app", "t_new"));
    }

    @Test
    void MySQL_대소문자_구분_서버는_그대로() {
        assertThat(DbObjectName.normalize(MYSQL, ref("Foo"), "app", false).name()).isEqualTo("Foo");
    }

    @Test
    void 스키마를_생략하면_기본_스키마_적으면_같은_규칙으로() {
        assertThat(DbObjectName.normalize(POSTGRES, ref("t"), "public", true).schema()).isEqualTo("public");
        assertThat(DbObjectName.normalize(POSTGRES, new DbObjectRef("Other", false, "t", false), "public", true))
                .isEqualTo(new DbObjectName("other", "t"));
        assertThat(DbObjectName.normalize(POSTGRES, new DbObjectRef("Other", true, "t", false), "public", true))
                .isEqualTo(new DbObjectName("Other", "t"));
        assertThat(DbObjectName.normalize(ORACLE, new DbObjectRef("app", false, "t", false), "APP", false))
                .isEqualTo(new DbObjectName("APP", "T"));
        assertThat(DbObjectName.normalize(MYSQL, new DbObjectRef("App", false, "t", false), "app", true).schema())
                .isEqualTo("app");
    }
}
