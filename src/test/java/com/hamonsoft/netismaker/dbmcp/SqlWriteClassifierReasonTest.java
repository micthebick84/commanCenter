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
        return SqlWriteClassifier.decide(sql, d, SqlWriteClassifierTableTest.names(d), own, 1000, 10000);
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

    @Test
    void 분석기_거부_사유를_그대로_돌려준다() {
        assertThat(decide("INSERT INTO legacy (a) VALUES ('x'); DROP TABLE legacy", POSTGRES, none()).reason())
                .isEqualTo("여러 문장은 실행할 수 없습니다");
        assertThat(decide("INSERT INTO legacy (a) SELECT pg_sleep(1)", POSTGRES, none()).reason())
                .isEqualTo("금지 함수 pg_sleep");
        assertThat(decide("GRANT SELECT ON legacy TO bob", POSTGRES, none()).reason()).contains("GRANT");
    }

    @Test
    void 승인_대기와_거부에는_행_상한이_없다() {
        assertThat(decide("ALTER TABLE legacy DROP COLUMN a", POSTGRES, none()).rowCap()).isNull();
        assertThat(decide("DELETE FROM legacy", POSTGRES, none()).rowCap()).isNull();
        assertThat(decide("DELETE FROM legacy WHERE id = 1", POSTGRES, none()).rowCap()).isEqualTo(1000);
    }
}
