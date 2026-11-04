package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쓰기 SQL 분류표 (스펙 2026-11-03 §6.2) — 표는 src/test/resources/dbmcp/sql-write-cases.txt.
 * 줄 형식 `방언 ¦ 소유 ¦ 기대 ¦ SQL`, ALL은 세 방언으로 펼친다.
 */
class SqlWriteClassifierTableTest {

    private static final String CASES = "/dbmcp/sql-write-cases.txt";
    private static final int MAX_UPDATE_DELETE_ROWS = 1000;
    private static final int MAX_INSERT_ROWS = 10000;

    record Case(int line, SqlDialect dialect, String owners, String expected, String sql) {
        @Override
        public String toString() {
            return line + ": " + dialect + " | " + owners + " | " + expected + " | " + sql;
        }
    }

    static List<Case> cases() throws IOException {
        List<Case> out = new ArrayList<>();
        try (InputStream in = SqlWriteClassifierTableTest.class.getResourceAsStream(CASES)) {
            assertThat(in).as(CASES).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String raw;
            int no = 0;
            while ((raw = r.readLine()) != null) {
                no++;
                if (raw.isBlank() || raw.startsWith("#")) continue;
                String[] f = raw.split(" ¦ ", 4);
                if (f.length != 4) throw new IllegalStateException(CASES + ":" + no + " 형식 오류: " + raw);
                for (SqlDialect d : dialects(f[0].strip(), no)) {
                    out.add(new Case(no, d, f[1].strip(), f[2].strip(), f[3]));
                }
            }
        }
        return out;
    }

    private static List<SqlDialect> dialects(String code, int line) {
        return switch (code) {
            case "PG" -> List.of(SqlDialect.POSTGRES);
            case "MY" -> List.of(SqlDialect.MYSQL);
            case "OR" -> List.of(SqlDialect.ORACLE);
            case "ALL" -> List.of(SqlDialect.POSTGRES, SqlDialect.MYSQL, SqlDialect.ORACLE);
            default -> throw new IllegalStateException(CASES + ":" + line + " 모르는 방언: " + code);
        };
    }

    static String defaultSchema(SqlDialect d) {
        return switch (d) {
            case POSTGRES -> "public";
            case MYSQL -> "app";
            case ORACLE -> "APP";
        };
    }

    static Function<DbObjectRef, DbObjectName> names(SqlDialect d) {
        return ref -> DbObjectName.normalize(d, ref, defaultSchema(d), true);
    }

    /** 스키마를 생략한 이름이 받는 스키마 — 대상 경계 검사(WriteTargetScope)가 MySQL·MariaDB의 다른 database를 가를 때 쓴다. */
    static String scopeSchema(SqlDialect d) {
        return DbObjectName.defaultSchemaOf(d, defaultSchema(d), true);
    }

    static Ownership ownership(SqlDialect d, String owners) {
        if (owners.equals("-")) return Ownership.none();
        Set<Ownership.OwnedObject> owned = new HashSet<>();
        for (String name : owners.split(",")) {
            String n = name.strip();
            DbObjectType type = n.startsWith("v_") ? DbObjectType.VIEW : DbObjectType.TABLE;
            owned.add(new Ownership.OwnedObject(type, names(d).apply(new DbObjectRef(null, false, n, false))));
        }
        return new Ownership(owned, null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 분류표(Case c) {
        WriteDecision decision = SqlWriteClassifier.decide(c.sql(), c.dialect(), names(c.dialect()),
                scopeSchema(c.dialect()), ownership(c.dialect(), c.owners()), MAX_UPDATE_DELETE_ROWS, MAX_INSERT_ROWS);
        String actual = decision.verdict() + (decision.rowCap() == null ? "" : "/" + decision.rowCap());
        assertThat(actual).as(c.dialect() + " | " + c.sql()).isEqualTo(c.expected());
    }

    @Test
    void 분류표는_세_판정을_모두_담는다() throws IOException {
        List<Case> all = cases();
        assertThat(all).hasSizeGreaterThan(200);
        assertThat(all).extracting(c -> c.expected().replaceAll("/.*", ""))
                .contains("EXECUTE", "DEFER", "REJECT");
    }
}
