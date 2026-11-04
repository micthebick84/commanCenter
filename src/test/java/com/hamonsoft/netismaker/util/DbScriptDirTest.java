package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 레포 카탈로그 DB 스크립트 폴더 값 규칙 (스펙 2026-11-03 task-type-db-write §6.6). */
class DbScriptDirTest {

    private static final String MESSAGE = "DB 스크립트 폴더는 레포 안 상대 경로여야 합니다";

    @Test
    void 정규화() {
        assertThat(DbScriptDir.normalize(" db\\sql/ ")).isEqualTo("db/sql");
        assertThat(DbScriptDir.resolve(null, 42)).isEqualTo("db/symphony/task-42.sql");
        for (String bad : List.of("/abs", "C:/x", "../up", "a/../b")) {
            assertThatThrownBy(() -> DbScriptDir.normalize(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 기본_폴더는_db_symphony() {
        assertThat(DbScriptDir.DEFAULT).isEqualTo("db/symphony");
    }

    @Test
    void 비면_null() {
        assertThat(DbScriptDir.normalize(null)).isNull();
        assertThat(DbScriptDir.normalize("")).isNull();
        assertThat(DbScriptDir.normalize("   ")).isNull();
    }

    @Test
    void 역슬래시는_슬래시로_바꾸고_끝_구분자를_뗀다() {
        assertThat(DbScriptDir.normalize("sql\\symphony\\")).isEqualTo("sql/symphony");
        assertThat(DbScriptDir.normalize("db/sql///")).isEqualTo("db/sql");
        assertThat(DbScriptDir.normalize("db")).isEqualTo("db");
    }

    @Test
    void 점이_들어간_이름은_통과시킨다() {
        // ".."는 한 단계가 통째로 ".."일 때만 거부한다 — 이름 안의 점은 막지 않는다.
        assertThat(DbScriptDir.normalize("db..old/v1.2")).isEqualTo("db..old/v1.2");
        assertThat(DbScriptDir.normalize("..db/x")).isEqualTo("..db/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abs", "\\abs", "//server/share", "\\\\server\\share", "C:/x", "c:\\x", "D:", "C:x",
            "../up", "..", "a/..", "a/../b", "a\\..\\b", "  /abs  "})
    void 절대_경로_드라이브_상위_폴더는_거부한다(String raw) {
        assertThatThrownBy(() -> DbScriptDir.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MESSAGE);
    }

    /**
     * 최종 리뷰 Important 4 — 워커는 모든 구현에서 이 값으로 worktree 경로를 만든다. Windows 금지 문자·제어문자가 저장되면 Windows 워커가
     * InvalidPathException으로 그 레포의 모든 구현을 커밋 단계에서 실패시키고, NUL은 PostgreSQL text 저장에서 500이 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"db<x", "db>x", "db:x", "db/a:b", "db\"x", "db|x", "db?x", "db*x",
            "db\u0000x", "db/x\u0000", "db\u0001x", "db\tx", "db\nx", "db\u001Fx", "db\u007Fx",
            "a//b", "a\\\\b", "./db", "db/./x", "db/.", ".git", ".git/x", ".GIT/x", "db/.git", "db/.Git/x",
            ".netis/x", ".design/x", ".Design"})
    void Windows_금지_문자_제어문자_빈_단계_점_단계_예약_폴더는_거부한다(String raw) {
        assertThatThrownBy(() -> DbScriptDir.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void 임시_폴더_이름은_첫_단계만_막는다() {
        assertThat(DbScriptDir.normalize(".gitx/sql")).isEqualTo(".gitx/sql");
        assertThat(DbScriptDir.normalize(".github/sql")).isEqualTo(".github/sql");
        assertThat(DbScriptDir.normalize("sql/.netis")).isEqualTo("sql/.netis");
        // 앞뒤 공백(줄바꿈 포함)은 떼고 본다
        assertThat(DbScriptDir.normalize("\tdb/sql\n")).isEqualTo("db/sql");
    }

    @Test
    void 이백오십오자까지만_허용한다() {
        String ok = "a".repeat(255);
        assertThat(DbScriptDir.normalize(ok)).isEqualTo(ok);
        // 앞뒤 공백·끝 구분자는 길이에 세지 않는다(저장되는 값이 기준).
        assertThat(DbScriptDir.normalize(" " + ok + "/ ")).isEqualTo(ok);
        assertThatThrownBy(() -> DbScriptDir.normalize("a".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void resolve는_폴더가_있으면_그_폴더를_쓴다() {
        assertThat(DbScriptDir.resolve("db/sql", 7L)).isEqualTo("db/sql/task-7.sql");
        assertThat(DbScriptDir.resolve("scripts", 123456789012L)).isEqualTo("scripts/task-123456789012.sql");
    }

    @Test
    void resolve는_폴더가_비면_기본_폴더를_쓴다() {
        assertThat(DbScriptDir.resolve(null, 1)).isEqualTo("db/symphony/task-1.sql");
        assertThat(DbScriptDir.resolve("", 1)).isEqualTo("db/symphony/task-1.sql");
        assertThat(DbScriptDir.resolve("  ", 1)).isEqualTo("db/symphony/task-1.sql");
    }
}
