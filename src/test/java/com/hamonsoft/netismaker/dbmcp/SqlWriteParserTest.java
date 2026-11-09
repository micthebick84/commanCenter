package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hamonsoft.netismaker.dbmcp.SqlDialect.MYSQL;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.ORACLE;
import static com.hamonsoft.netismaker.dbmcp.SqlDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 분석 결과의 모양 — 실행기(Task 11)가 대상·이름 변경·실행문을 여기서 받는다. */
class SqlWriteParserTest {

    private static DbObjectRef ref(String name) {
        return new DbObjectRef(null, false, name, false);
    }

    @Test
    void 실행문은_끝_세미콜론_하나와_그_뒤만_뗀다() throws Exception {
        assertThat(SqlWriteParser.parse("  INSERT INTO legacy (a) VALUES (';');  -- 끝\n", POSTGRES).executableSql())
                .isEqualTo("INSERT INTO legacy (a) VALUES (';')");
        assertThat(SqlWriteParser.parse("DELETE FROM legacy WHERE id = 3", ORACLE).executableSql())
                .isEqualTo("DELETE FROM legacy WHERE id = 3");
    }

    @Test
    void 트리거와_루틴은_원문_그대로() throws Exception {
        String proc = "CREATE OR REPLACE PROCEDURE p_x AS BEGIN NULL; END;";
        WriteStatement st = SqlWriteParser.parse(proc, ORACLE);
        assertThat(st.kind()).isEqualTo(WriteKind.CREATE_ROUTINE);
        assertThat(st.orReplace()).isTrue();
        assertThat(st.executableSql()).isEqualTo(proc);

        String trigger = "CREATE TRIGGER tr_legacy BEFORE INSERT ON legacy FOR EACH ROW SET NEW.a = 'x'";
        assertThat(SqlWriteParser.parse(trigger, MYSQL).kind()).isEqualTo(WriteKind.CREATE_TRIGGER);
        assertThat(SqlWriteParser.parse("CREATE PACKAGE BODY pk AS END;", ORACLE).kind())
                .isEqualTo(WriteKind.CREATE_ROUTINE);
    }

    @Test
    void 대상_이름은_인용_여부와_스키마를_보존() throws Exception {
        WriteStatement st = SqlWriteParser.parse("DROP TABLE public.\"T_New\", other", POSTGRES);
        assertThat(st.kind()).isEqualTo(WriteKind.DROP_TABLE);
        assertThat(st.targets()).containsExactly(new DbObjectRef("public", false, "T_New", true), ref("other"));

        assertThat(SqlWriteParser.parse("DROP TABLE `a``b`", MYSQL).targets())
                .containsExactly(new DbObjectRef(null, false, "a`b", true));
    }

    @Test
    void ALTER_TABLE_동작을_순서대로_분해() throws Exception {
        WriteStatement st = SqlWriteParser.parse(
                "ALTER TABLE legacy ADD COLUMN c text, DROP COLUMN a, ALTER COLUMN b TYPE bigint, RENAME COLUMN d TO e",
                POSTGRES);
        assertThat(st.kind()).isEqualTo(WriteKind.ALTER_TABLE);
        assertThat(st.targets()).containsExactly(ref("legacy"));
        assertThat(st.actions()).containsExactly(AlterAction.ADD_COLUMN, AlterAction.DROP_COLUMN,
                AlterAction.ALTER_COLUMN, AlterAction.RENAME_COLUMN);
    }

    @Test
    void Oracle_괄호_ADD는_항목마다() throws Exception {
        WriteStatement st = SqlWriteParser.parse(
                "ALTER TABLE legacy ADD (c NUMBER, d NUMBER NOT NULL, e NUMBER REFERENCES t2 (id), CONSTRAINT uq UNIQUE (c))",
                ORACLE);
        assertThat(st.actions()).containsExactly(AlterAction.ADD_COLUMN, AlterAction.ADD_COLUMN_NOT_NULL_NO_DEFAULT,
                AlterAction.ADD_COLUMN_WITH_CONSTRAINT, AlterAction.ADD_CONSTRAINT);
    }

    @Test
    void 제약_삭제와_테이블_이름_변경() throws Exception {
        assertThat(SqlWriteParser.parse("ALTER TABLE legacy DROP CONSTRAINT fk_a, DROP PRIMARY KEY", MYSQL).actions())
                .containsExactly(AlterAction.DROP_CONSTRAINT, AlterAction.DROP_CONSTRAINT);

        WriteStatement alter = SqlWriteParser.parse("ALTER TABLE t_new RENAME TO app.t_new2", MYSQL);
        assertThat(alter.actions()).containsExactly(AlterAction.RENAME_TABLE);
        assertThat(alter.renameTo()).isEqualTo(new DbObjectRef("app", false, "t_new2", false));

        WriteStatement rename = SqlWriteParser.parse("RENAME TABLE t_new TO t_new2", MYSQL);
        assertThat(rename.kind()).isEqualTo(WriteKind.RENAME_TABLE);
        assertThat(rename.targets()).containsExactly(ref("t_new"));
        assertThat(rename.renameTo()).isEqualTo(ref("t_new2"));
    }

    @Test
    void CREATE_표시와_대상() throws Exception {
        WriteStatement table = SqlWriteParser.parse("CREATE TABLE IF NOT EXISTS t_new (id int)", POSTGRES);
        assertThat(table.kind()).isEqualTo(WriteKind.CREATE_TABLE);
        assertThat(table.ifNotExists()).isTrue();
        assertThat(table.orReplace()).isFalse();

        WriteStatement view = SqlWriteParser.parse("CREATE OR REPLACE VIEW v_new AS SELECT 1", POSTGRES);
        assertThat(view.kind()).isEqualTo(WriteKind.CREATE_VIEW);
        assertThat(view.orReplace()).isTrue();

        WriteStatement index = SqlWriteParser.parse("CREATE UNIQUE INDEX ux ON app.legacy (a)", ORACLE);
        assertThat(index.kind()).isEqualTo(WriteKind.CREATE_UNIQUE_INDEX);
        assertThat(index.targets()).containsExactly(new DbObjectRef("app", false, "legacy", false));

        assertThat(SqlWriteParser.parse("CREATE INDEX ON legacy (a)", POSTGRES).targets()).containsExactly(ref("legacy"));
        assertThat(SqlWriteParser.parse("COMMENT ON COLUMN app.legacy.a IS NULL", ORACLE).targets())
                .containsExactly(new DbObjectRef("app", false, "legacy", false));
    }

    @Test
    void WHERE는_최상위만_센다() throws Exception {
        assertThat(SqlWriteParser.parse("UPDATE legacy SET a = (SELECT b FROM t2 WHERE t2.id = 1)", POSTGRES).hasWhere())
                .isFalse();
        assertThat(SqlWriteParser.parse("UPDATE legacy SET a = 1 WHERE id IN (SELECT id FROM t2)", POSTGRES).hasWhere())
                .isTrue();
        assertThat(SqlWriteParser.parse("DELETE FROM legacy l WHERE l.id = 1", MYSQL).hasWhere()).isTrue();
        assertThat(SqlWriteParser.parse("INSERT INTO legacy (a) SELECT a FROM t2 WHERE b = 1", MYSQL).hasWhere())
                .isFalse();
    }

    @Test
    void 거부는_한국어_사유를_담은_예외() {
        assertThatThrownBy(() -> SqlWriteParser.parse("SELECT 1", POSTGRES))
                .isInstanceOf(SqlWriteRejected.class)
                .hasMessageContaining("query 도구");
        assertThatThrownBy(() -> SqlWriteParser.parse("DROP TABLE \"t_new", POSTGRES))
                .isInstanceOf(SqlWriteRejected.class)
                .hasMessage("닫히지 않은 문자열/식별자");
        assertThatThrownBy(() -> SqlWriteParser.parse("UPDATE legacy SET a = (1 WHERE id = 3", POSTGRES))
                .isInstanceOf(SqlWriteRejected.class)
                .hasMessage("괄호가 맞지 않습니다");
    }

    @Test
    void 대상_목록은_바꿀_수_없다() throws Exception {
        WriteStatement st = SqlWriteParser.parse("TRUNCATE t1, t2", POSTGRES);
        assertThat(st.targets()).containsExactly(ref("t1"), ref("t2"));
        assertThatThrownBy(() -> st.targets().add(ref("t3"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(st.actions()).isEqualTo(List.of());
    }
}
