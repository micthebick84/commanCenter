package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.hamonsoft.netismaker.dbmcp.Ownership.none;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.MYSQL;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.ORACLE;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/** 판정 사유 문구 — 세션이 다음 행동을 고를 수 있게 이유와 대안을 담는다 (스펙 2026-11-03 §6.2). */
class SqlWriteClassifierReasonTest {

    private static WriteDecision decide(String sql, SqlDialect d, Ownership own) {
        return SqlWriteClassifier.decide(sql, d, SqlWriteClassifierTableTest.names(d),
                SqlWriteClassifierTableTest.scopeSchema(d), own, 1000, 10000);
    }

    private static Ownership owns(SqlDialect d, DbObjectType type, String name, String dependentNote) {
        DbObjectName n = SqlWriteClassifierTableTest.names(d).apply(new DbObjectRef(null, false, name, false));
        return new Ownership(Set.of(new Ownership.OwnedObject(type, n)), dependentNote);
    }

    @Test
    void 남의_컬럼삭제_사유() {
        assertThat(decide("ALTER TABLE legacy DROP COLUMN a", POSTGRES, none()).reason())
                .isEqualTo("남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요");
    }

    @Test
    void 남의_테이블_DROP_사유() {
        WriteDecision d = decide("DROP TABLE legacy", POSTGRES, none());
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(d.reason()).isEqualTo("남의 테이블은 지울 수 없습니다(DROP) — 그 작업이 만든 테이블만 지울 수 있습니다");
    }

    @Test
    void 의존작업이면_소유예외_꺼짐() {
        Ownership own = owns(POSTGRES, DbObjectType.TABLE, "t_new", "작업 #25");

        WriteDecision drop = decide("DROP TABLE t_new", POSTGRES, own);
        assertThat(drop.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(drop.reason()).contains("작업 #25");

        WriteDecision dropColumn = decide("ALTER TABLE t_new DROP COLUMN a", POSTGRES, own);
        assertThat(dropColumn.verdict()).isEqualTo(WriteDecision.Verdict.DEFER);
        assertThat(dropColumn.reason()).contains("작업 #25");

        WriteDecision update = decide("UPDATE t_new SET a = 1", POSTGRES, own);
        assertThat(update.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(update.reason()).contains("작업 #25");
    }

    @Test
    void 의존작업_메모는_소유와_무관한_판정에는_붙지_않는다() {
        Ownership own = owns(POSTGRES, DbObjectType.TABLE, "t_new", "작업 #25");
        assertThat(decide("DROP TABLE legacy", POSTGRES, own).reason()).doesNotContain("작업 #25");
        assertThat(decide("INSERT INTO t_new (a) VALUES (1)", POSTGRES, own))
                .isEqualTo(new WriteDecision(WriteDecision.Verdict.EXECUTE, null, 10000));
    }

    @Test
    void 빈_문장과_10만자_초과는_거부() {
        for (String sql : new String[] {null, "", "   \n", "-- 주석만", ";"}) {
            WriteDecision d = decide(sql, POSTGRES, none());
            assertThat(d.verdict()).as(String.valueOf(sql)).isEqualTo(WriteDecision.Verdict.REJECT);
        }
        assertThat(decide("", POSTGRES, none()).reason()).isEqualTo("빈 문장");

        String head = "INSERT INTO legacy (a) VALUES ('";
        String tail = "')";
        String exact = head + "x".repeat(100_000 - head.length() - tail.length()) + tail;
        assertThat(exact).hasSize(100_000);
        assertThat(decide(exact, POSTGRES, none()).verdict()).isEqualTo(WriteDecision.Verdict.EXECUTE);

        WriteDecision over = decide(exact + " ", POSTGRES, none());
        assertThat(over.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(over.reason()).contains("100000");
    }

    @Test
    void 여러_ALTER_동작은_가장_엄한_분류() {
        WriteDecision d = decide("ALTER TABLE legacy ADD COLUMN c text, DROP COLUMN a", POSTGRES, none());
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.DEFER);
        assertThat(d.reason()).isEqualTo("남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요");

        WriteDecision rename = decide("ALTER TABLE legacy DROP COLUMN a, RENAME TO legacy2", POSTGRES, none());
        assertThat(rename.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
    }

    @Test
    void 남의_뷰_교체는_새_뷰면_CREATE_VIEW_안내() {
        WriteDecision d = decide("CREATE OR REPLACE VIEW v_x AS SELECT a FROM legacy", POSTGRES, none());
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.DEFER);
        assertThat(d.reason()).contains("새 뷰면 CREATE VIEW를 쓰세요");
    }

    @Test
    void ADD_INDEX는_CREATE_INDEX_안내() {
        WriteDecision d = decide("ALTER TABLE legacy ADD INDEX ix_a (a)", MYSQL, none());
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(d.reason()).contains("CREATE INDEX를 쓰세요");
    }

    @Test
    void 모르는_ALTER_TABLE_동작은_그_단어로_거부() {
        assertThat(decide("ALTER TABLE legacy OWNER TO bob", POSTGRES, none()).reason())
                .isEqualTo("분석할 수 없는 ALTER TABLE 동작: OWNER");
    }

    @Test
    void 조건_없는_UPDATE_DELETE는_WHERE_안내() {
        assertThat(decide("DELETE FROM legacy", ORACLE, none()).reason()).contains("WHERE");
        assertThat(decide("UPDATE legacy SET a = 1", ORACLE, none()).reason()).contains("WHERE");
    }

    @Test
    void 소유_시퀀스만_지울_수_있다() {
        Ownership own = owns(POSTGRES, DbObjectType.SEQUENCE, "seq_new", null);
        assertThat(decide("DROP SEQUENCE seq_new", POSTGRES, own).verdict()).isEqualTo(WriteDecision.Verdict.EXECUTE);
        assertThat(decide("DROP SEQUENCE seq_old", POSTGRES, own).verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(decide("CREATE OR REPLACE SEQUENCE seq_new", MYSQL, owns(MYSQL, DbObjectType.SEQUENCE, "seq_new", null))
                .verdict()).isEqualTo(WriteDecision.Verdict.EXECUTE);
    }

    @Test
    void 소유_테이블도_CASCADE는_거부() {
        WriteDecision d = decide("DROP TABLE t_new CASCADE", POSTGRES, owns(POSTGRES, DbObjectType.TABLE, "t_new", null));
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(d.reason()).contains("CASCADE");
    }

    @Test
    void PostgreSQL_63바이트를_넘는_이름은_거부() {
        String longName = "t_" + "a".repeat(62);
        assertThat(longName).hasSize(64);
        Ownership own = owns(POSTGRES, DbObjectType.TABLE, longName, null);
        WriteDecision d = decide("DROP TABLE " + longName, POSTGRES, own);
        assertThat(d.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(d.reason()).contains("63");
        assertThat(decide("DROP TABLE " + longName, ORACLE, owns(ORACLE, DbObjectType.TABLE, longName, null)).verdict())
                .isEqualTo(WriteDecision.Verdict.EXECUTE);
    }

    /**
     * MySQL·MariaDB는 lower_case_table_names≠0이면 서버가 이름을 자기 표로 소문자로 접는다. ASCII 밖 대소문자 문자는
     * 그 표와 이 JVM의 접기가 Unicode 판마다 어긋날 수 있다 — 어긋나면 레거시 테이블에 IF NOT EXISTS로 소유 기록만 얻고
     * CREATE OR REPLACE로 교체하는 경로가 열린다. 객체 이름 자리에서만 거부하고 컬럼 이름은 그대로 받는다.
     */
    @Test
    void MySQL_객체_이름에_대소문자가_있는_ASCII_밖_문자는_거부() {
        WriteDecision ifNotExists = decide("CREATE TABLE IF NOT EXISTS `EQUİPMENT` (id INT)", MYSQL, none());
        assertThat(ifNotExists).isEqualTo(WriteDecision.reject(SqlWriteParser.MYSQL_CASED_NON_ASCII + "EQUİPMENT"));
        assertThat(ifNotExists.reason()).contains("대소문자");

        // 소유 기록이 JVM이 접은 이름(ა U+10D0)이어도, 서버가 접지 않는 Ა(U+1C90)로 교체하는 문장은 거부
        Ownership own = owns(MYSQL, DbObjectType.TABLE, "ა", null);
        Ownership tNew = owns(MYSQL, DbObjectType.TABLE, "t_new", null);
        assertThat(decide("CREATE OR REPLACE TABLE `Ა` (id INT)", MYSQL, own).reason())
                .startsWith(SqlWriteParser.MYSQL_CASED_NON_ASCII);
        assertThat(decide("DROP TABLE `Équipe`.t_new", MYSQL, tNew).reason())
                .as("스키마 자리").startsWith(SqlWriteParser.MYSQL_CASED_NON_ASCII);
        assertThat(decide("RENAME TABLE t_new TO `Ꭰ`", MYSQL, tNew).reason())
                .as("새 이름 자리").startsWith(SqlWriteParser.MYSQL_CASED_NON_ASCII);

        assertThat(decide("CREATE TABLE `장비_목록` (id INT)", MYSQL, none()).verdict())
                .as("대소문자 없는 한글").isEqualTo(WriteDecision.Verdict.EXECUTE);
        assertThat(decide("ALTER TABLE legacy ADD COLUMN `Größe` INT", MYSQL, none()).verdict())
                .as("컬럼 이름").isEqualTo(WriteDecision.Verdict.EXECUTE);
        assertThat(decide("CREATE TABLE \"EQUİPMENT\" (id int)", POSTGRES, none()).verdict())
                .as("PostgreSQL 인용 이름은 접지 않는다").isEqualTo(WriteDecision.Verdict.EXECUTE);
        assertThat(decide("CREATE TABLE \"EQUİPMENT\" (id NUMBER)", ORACLE, none()).verdict())
                .as("Oracle 인용 이름은 접지 않는다").isEqualTo(WriteDecision.Verdict.EXECUTE);
    }

    @Test
    void 분석기_거부_사유를_그대로_돌려준다() {
        assertThat(decide("INSERT INTO legacy (a) VALUES ('x'); DROP TABLE legacy", POSTGRES, none()).reason())
                .isEqualTo("여러 문장은 실행할 수 없습니다");
        assertThat(decide("INSERT INTO legacy (a) SELECT pg_sleep(1)", POSTGRES, none()).reason())
                .isEqualTo("금지 함수 pg_sleep");
        assertThat(decide("GRANT SELECT ON legacy TO bob", POSTGRES, none()).reason()).contains("GRANT");
    }

    /**
     * PostgreSQL 서버(scan.l newline [\n\r])와 pgjdbc는 -- 주석을 CR에서도 끝내고, MySQL은 LF에서만 끝낸다.
     * 단독 CR을 받으면 분석기가 본 문장과 DB가 실행하는 문장이 달라진다 — 끊지 않고 거부한다.
     */
    @Test
    void 줄_주석_안의_단독_CR은_거부() {
        WriteDecision drop = decide("DROP TABLE t_new --\r, legacy", POSTGRES, owns(POSTGRES, DbObjectType.TABLE, "t_new", null));
        assertThat(drop.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(drop.reason()).contains("CR");

        WriteDecision multi = decide("INSERT INTO legacy (a) VALUES ('x') --\r; DROP TABLE legacy", POSTGRES, none());
        assertThat(multi.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(multi.reason()).contains("CR");

        WriteDecision hiddenWhere = decide("UPDATE legacy SET a = 1 -- \rWHERE id = 3", MYSQL, none());
        assertThat(hiddenWhere.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(hiddenWhere.reason()).contains("CR");

        WriteDecision routine = decide("CREATE FUNCTION f_x() RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql --\r; DROP TABLE legacy",
                POSTGRES, none());
        assertThat(routine.verdict()).isEqualTo(WriteDecision.Verdict.REJECT);
        assertThat(routine.reason()).contains("CR");
    }

    @Test
    void 단독_CR은_방언과_자리에_상관없이_따옴표_밖이면_거부() {
        String[] rejected = {
                "UPDATE legacy SET a = 1 -- x\rWHERE id = 3",
                "UPDATE legacy SET a = 1\rWHERE id = 3",
                "UPDATE legacy SET a = 1 /* x\r */ WHERE id = 3",
                "UPDATE legacy SET a = 1 WHERE id = 3 --\r",
        };
        for (SqlDialect d : SqlDialect.values()) {
            for (String sql : rejected) {
                assertThat(decide(sql, d, none()).verdict()).as(d + " | " + sql.replace("\r", "\\r"))
                        .isEqualTo(WriteDecision.Verdict.REJECT);
            }
            assertThat(decide("UPDATE legacy SET a = 1 -- x\r\nWHERE id = 3", d, none())).as(d + " | CRLF")
                    .isEqualTo(new WriteDecision(WriteDecision.Verdict.EXECUTE, null, 1000));
            assertThat(decide("INSERT INTO legacy (a) VALUES ('x\ry')", d, none())).as(d + " | 문자열 안 CR")
                    .isEqualTo(new WriteDecision(WriteDecision.Verdict.EXECUTE, null, 10000));
        }
        assertThat(decide("UPDATE legacy SET a = 1 # x\rWHERE id = 3", MYSQL, none()).verdict())
                .isEqualTo(WriteDecision.Verdict.REJECT);
    }

    @Test
    void 승인_대기와_거부에는_행_상한이_없다() {
        assertThat(decide("ALTER TABLE legacy DROP COLUMN a", POSTGRES, none()).rowCap()).isNull();
        assertThat(decide("DELETE FROM legacy", POSTGRES, none()).rowCap()).isNull();
        assertThat(decide("DELETE FROM legacy WHERE id = 1", POSTGRES, none()).rowCap()).isEqualTo(1000);
    }

    /** 최종 리뷰 Important 1 — 분류 전에 대상 경계를 본다. 남의 테이블 규칙(WHERE + 행 상한)을 통과해도 거부다. */
    @Test
    void 시스템_스키마와_MySQL_다른_database는_분류_전에_거부() {
        String outsideApp = " — 이 연결은 app 안에서만 씁니다";
        assertThat(decide("UPDATE mysql.user SET authentication_string = 'x' WHERE User = 'root'", MYSQL, none()))
                .isEqualTo(new WriteDecision(WriteDecision.Verdict.REJECT, "시스템 스키마의 객체는 쓸 수 없습니다: mysql.user", null));
        assertThat(decide("DELETE FROM prod.users WHERE id = 1", MYSQL, none()).reason())
                .isEqualTo("연결의 데이터베이스 밖 객체는 쓸 수 없습니다: prod.users" + outsideApp);
        assertThat(decide("CREATE TABLE prod.t_x (id INT)", MYSQL, none()).reason())
                .isEqualTo("연결의 데이터베이스 밖 객체는 쓸 수 없습니다: prod.t_x" + outsideApp);
        // 이름 변경의 새 이름도 대상이다 — MySQL RENAME TABLE은 다른 database로 옮긴다
        assertThat(decide("RENAME TABLE t_new TO prod.t_new", MYSQL, owns(MYSQL, DbObjectType.TABLE, "t_new", null)).reason())
                .isEqualTo("연결의 데이터베이스 밖 객체는 쓸 수 없습니다: prod.t_new" + outsideApp);
        assertThat(decide("DELETE FROM pg_catalog.pg_database WHERE datname = 'other'", POSTGRES, none()).reason())
                .isEqualTo("시스템 스키마의 객체는 쓸 수 없습니다: pg_catalog.pg_database");
        assertThat(decide("DELETE FROM SYSTEM.help WHERE topic = 'x'", ORACLE, none()).reason())
                .isEqualTo("시스템 스키마의 객체는 쓸 수 없습니다: SYSTEM.HELP");
        // 시스템이 아닌 다른 스키마는 PostgreSQL·Oracle에서 남의 테이블이다(같은 database 안)
        assertThat(decide("UPDATE other.legacy SET a = 1 WHERE id = 1", ORACLE, none()))
                .isEqualTo(new WriteDecision(WriteDecision.Verdict.EXECUTE, null, 1000));
    }
}
