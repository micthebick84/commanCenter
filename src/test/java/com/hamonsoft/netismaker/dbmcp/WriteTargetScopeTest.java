package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static com.hamonsoft.netismaker.dbmcp.SqlDialect.MYSQL;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.ORACLE;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/** 쓰기 대상의 경계 — 이름 규칙만으로 보는 부분 (최종 리뷰 Important 1). 분류표에 없는 서버 설정·스키마 이름을 본다. */
class WriteTargetScopeTest {

    private static String check(SqlDialect d, String sql, String defaultSchema, boolean mysqlLowerCase) throws SqlWriteRejected {
        Function<DbObjectRef, DbObjectName> names = r -> DbObjectName.normalize(d, r, defaultSchema, mysqlLowerCase);
        return WriteTargetScope.check(d, SqlWriteParser.parse(sql, d), names,
                DbObjectName.defaultSchemaOf(d, defaultSchema, mysqlLowerCase));
    }

    @Test
    void MySQL_대소문자_구분_서버는_스키마_대소문자가_다르면_다른_database() throws SqlWriteRejected {
        // lower_case_table_names = 0 — 서버가 App과 app을 다른 database로 본다
        assertThat(check(MYSQL, "DELETE FROM app.legacy WHERE id = 1", "App", false))
                .isEqualTo("연결의 데이터베이스 밖 객체는 쓸 수 없습니다: app.legacy — 이 연결은 App 안에서만 씁니다");
        assertThat(check(MYSQL, "DELETE FROM App.legacy WHERE id = 1", "App", false)).isNull();
        assertThat(check(MYSQL, "DELETE FROM legacy WHERE id = 1", "App", false)).isNull();
        // 소문자 서버는 기본 스키마도 접어 비교한다
        assertThat(check(MYSQL, "DELETE FROM app.legacy WHERE id = 1", "App", true)).isNull();
    }

    @Test
    void 연결의_기본_database가_시스템_스키마면_비한정_이름도_거부() throws SqlWriteRejected {
        assertThat(check(MYSQL, "UPDATE user SET Super_priv = 'Y' WHERE User = 'app'", "mysql", true))
                .isEqualTo("시스템 스키마의 객체는 쓸 수 없습니다: mysql.user");
        assertThat(check(POSTGRES, "DELETE FROM pg_database WHERE datname = 'x'", "pg_catalog", false))
                .isEqualTo("시스템 스키마의 객체는 쓸 수 없습니다: pg_catalog.pg_database");
        assertThat(check(ORACLE, "DELETE FROM legacy WHERE id = 1", "SYS", false))
                .isEqualTo("시스템 스키마의 객체는 쓸 수 없습니다: SYS.LEGACY");
    }

    @Test
    void 시스템_스키마_이름() {
        for (String s : new String[]{"pg_catalog", "pg_toast", "pg_temp_3", "pg_toast_temp_3", "information_schema"}) {
            assertThat(WriteTargetScope.systemSchema(POSTGRES, s)).as(s).isTrue();
        }
        // 인용으로 대문자를 보존한 이름은 PostgreSQL에서 다른 스키마다
        assertThat(WriteTargetScope.systemSchema(POSTGRES, "PG_CATALOG")).isFalse();
        assertThat(WriteTargetScope.systemSchema(POSTGRES, "public")).isFalse();
        for (String s : new String[]{"mysql", "MySQL", "information_schema", "INFORMATION_SCHEMA", "performance_schema", "sys"}) {
            assertThat(WriteTargetScope.systemSchema(MYSQL, s)).as(s).isTrue();
        }
        assertThat(WriteTargetScope.systemSchema(MYSQL, "app")).isFalse();
        for (String s : new String[]{"SYS", "SYSTEM", "MDSYS", "XDB", "OUTLN"}) {
            assertThat(WriteTargetScope.systemSchema(ORACLE, s)).as(s).isTrue();
        }
        assertThat(WriteTargetScope.systemSchema(ORACLE, "APP")).isFalse();
    }
}
