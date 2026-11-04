package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 워커가 만드는 PR SQL 스크립트 파일 (스펙 2026-11-03 §6.6) — 내용(실행된 문장 → 병합 뒤 실행 절), worktree 안 쓰기, 이번 시도의 쓰기 판정,
 * 성공 판정(DB 작업은 누적 파일만으로도 성공).
 */
class DbScriptFileTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-04T01:00:00Z");

    @TempDir Path tmp;

    private static DbChangeView change(long id, int attempt, String sql, String classification) {
        return new DbChangeView(id, 5L, "개발 (PostgreSQL)", attempt, null, sql, classification, null, null, null,
                AT, "EXECUTED".equals(classification) ? AT : null, null);
    }

    private static final List<DbChangeView> ENTRIES = List.of(
            change(12, 1, "CREATE TABLE t_new (id int)", "EXECUTED"),
            change(13, 1, "ALTER TABLE t_old DROP COLUMN c", "DEFERRED"),
            change(14, 1, "REJECTED 문장", "REJECTED"),
            change(15, 2, "INSERT INTO t_new VALUES (1);", "EXECUTED"),
            change(16, 2, "DISMISSED 문장", "DISMISSED"),
            change(17, 2, "FAILED 문장", "FAILED"));

    // ---- render ----

    @Test
    void 렌더_순서와_절() {
        String s = DbScriptFile.render(42, ENTRIES).orElseThrow();

        assertThat(s).startsWith("-- Symphony 작업 #42 DB 변경 기록 (워커가 생성 — 직접 고치지 마세요)\n")
                .contains("-- [실행됨] 시도 1 · 개발 (PostgreSQL)")
                .contains("\n-- 병합 뒤 실행(승인 대기)\n")
                .doesNotContain("REJECTED 문장").doesNotContain("DISMISSED 문장").doesNotContain("FAILED 문장");
        // 실행된 문장 전체(기록 순서) → 병합 뒤 실행 절의 승인 대기 문장
        int created = s.indexOf("CREATE TABLE t_new (id int);\n");
        int inserted = s.indexOf("INSERT INTO t_new VALUES (1);\n");
        int deferredTitle = s.indexOf("\n-- 병합 뒤 실행(승인 대기)\n");
        int dropped = s.indexOf("ALTER TABLE t_old DROP COLUMN c;\n");
        assertThat(created).isPositive().isLessThan(inserted);
        assertThat(inserted).isLessThan(deferredTitle);
        assertThat(deferredTitle).isLessThan(dropped);
        assertThat(s).contains("-- [실행됨] 시도 2 · 개발 (PostgreSQL) · 기록 #15\nINSERT INTO t_new VALUES (1);\n")
                .contains("-- [승인 대기] 시도 1 · 개발 (PostgreSQL) · 기록 #13\nALTER TABLE t_old DROP COLUMN c;\n")
                // 이미 ;로 끝나면 덧붙이지 않는다
                .doesNotContain(";;");
    }

    @Test
    void 실행됐거나_승인대기가_없으면_empty() {
        assertThat(DbScriptFile.render(42, List.of())).isEmpty();
        assertThat(DbScriptFile.render(42, null)).isEmpty();
        assertThat(DbScriptFile.render(42, List.of(change(14, 1, "DROP TABLE t", "REJECTED"),
                change(16, 1, "x", "DISMISSED"), change(17, 1, "y", "FAILED"), change(18, 1, "z", "SOMETHING_NEW"))))
                .isEmpty();
    }

    @Test
    void 승인대기가_없으면_병합뒤_절도_없다() {
        String s = DbScriptFile.render(42, List.of(change(12, 1, "CREATE TABLE t_new (id int)", "EXECUTED"))).orElseThrow();

        assertThat(s).doesNotContain("병합 뒤 실행(승인 대기)");
    }

    @Test
    void 수정회차_기록은_요청_번호를_달고_여러줄_SQL은_그대로() {
        DbChangeView fix = new DbChangeView(20L, 5L, "개발 (PostgreSQL)", 1, 9L,
                "UPDATE t_new\r\n   SET v = 1\r\n WHERE id = 1", "EXECUTED", null, 1L, null, AT, AT, null);

        String s = DbScriptFile.render(42, List.of(fix)).orElseThrow();

        assertThat(s).contains("-- [실행됨] 시도 1 · 개발 (PostgreSQL) · 수정 요청 #9 · 기록 #20\n"
                + "UPDATE t_new\n   SET v = 1\n WHERE id = 1;\n");
    }

    /** 실행 결과가 기록되지 않은 실행 행(사전 저장 뒤 마무리 저장 실패) — 실행됐다고 단정하지 않는다. */
    @Test
    void 실행시각이_없는_실행행은_확인필요로_적는다() {
        DbChangeView unsure = new DbChangeView(21L, 5L, "개발 (PostgreSQL)", 1, null, "DELETE FROM t_new WHERE id = 3",
                "EXECUTED", null, null, null, AT, null, null);

        String s = DbScriptFile.render(42, List.of(unsure)).orElseThrow();

        assertThat(s).contains("-- [실행 여부 확인 필요 — 실행 결과가 기록되지 않음] 시도 1 · 개발 (PostgreSQL) · 기록 #21\n")
                .doesNotContain("-- [실행됨]");
    }

    /** 연결 이름에 줄바꿈이 있어도 주석을 벗어나 SQL 줄이 되지 않는다. */
    @Test
    void 연결이름의_줄바꿈은_주석_밖으로_나가지_않는다() {
        DbChangeView odd = new DbChangeView(22L, null, "개발\nDROP TABLE x;", 1, null, "SELECT 1", "EXECUTED",
                null, null, null, AT, AT, null);

        String s = DbScriptFile.render(42, List.of(odd)).orElseThrow();

        assertThat(s).contains("-- [실행됨] 시도 1 · 개발 DROP TABLE x; · 기록 #22\n").doesNotContain("\nDROP TABLE x;");
    }

    @Test
    void 끝줄이_주석이면_세미콜론을_다음_줄에() {
        DbChangeView commented = new DbChangeView(23L, 5L, "개발 (PostgreSQL)", 1, null,
                "CREATE TABLE t (id int) -- 메모", "EXECUTED", null, null, null, AT, AT, null);

        assertThat(DbScriptFile.render(42, List.of(commented)).orElseThrow())
                .contains("CREATE TABLE t (id int) -- 메모\n;\n");
    }

    // ---- write ----

    @Test
    void 쓰기는_폴더를_만들고_UTF8로_덮어쓴다() throws Exception {
        Path wt = Files.createDirectories(tmp.resolve("wt"));

        Path written = DbScriptFile.write(wt, "db/symphony/task-42.sql", "-- 가\n");
        DbScriptFile.write(wt, "db/symphony/task-42.sql", "-- 나\n");

        assertThat(written).isEqualTo(wt.resolve("db/symphony/task-42.sql"));
        assertThat(Files.readString(written)).isEqualTo("-- 나\n");
    }

    @Test
    void 경로탈출_거부() throws Exception {
        Path wt = Files.createDirectories(tmp.resolve("wt"));

        assertThatThrownBy(() -> DbScriptFile.write(wt, "../x.sql", "x"))
                .isInstanceOf(IOException.class).hasMessageStartingWith("스크립트 경로가 worktree 밖입니다: ");
        assertThatThrownBy(() -> DbScriptFile.write(wt, "/abs.sql", "x"))
                .isInstanceOf(IOException.class).hasMessageStartingWith("스크립트 경로가 worktree 밖입니다: ");
        assertThatThrownBy(() -> DbScriptFile.write(wt, "db/../../x.sql", "x"))
                .isInstanceOf(IOException.class).hasMessageStartingWith("스크립트 경로가 worktree 밖입니다: ");
        assertThat(tmp.resolve("x.sql")).doesNotExist();
    }

    /**
     * 세션이 폴더를 worktree 밖으로 가는 링크로 바꿔 둬도 그 너머에 폴더·파일을 만들지 않는다. 심볼릭 링크를 못 만드는 Windows PC는 정션
     * (권한 없이 만들 수 있다)으로, 둘 다 안 되면 건너뛴다.
     */
    @Test
    void 링크로_빠져나가는_폴더는_거부하고_밖에_아무것도_만들지_않는다() throws Exception {
        Path wt = Files.createDirectories(tmp.resolve("wt"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path link = wt.resolve("db");
        boolean junction = false;
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            assumeTrue(windows, "심볼릭 링크를 만들 수 없는 환경");
            try {
                ProcessRunner.requireSuccess(tmp.toFile(),
                        List.of("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()), 30);
                junction = true;
            } catch (Exception junctionFailed) {
                assumeTrue(false, "심볼릭 링크도 정션도 만들 수 없는 환경");
            }
        }
        try {
            assertThatThrownBy(() -> DbScriptFile.write(wt, "db/symphony/task-42.sql", "x"))
                    .isInstanceOf(IOException.class).hasMessageStartingWith("스크립트 경로가 worktree 밖입니다: ");
            assertThat(outside.resolve("symphony")).doesNotExist();
        } finally {
            // @TempDir 정리는 정션을 지우지 못한다 — 링크만 걷는다(rmdir은 대상 폴더를 건드리지 않는다)
            if (junction) ProcessRunner.requireSuccess(tmp.toFile(), List.of("cmd", "/c", "rmdir", link.toString()), 30);
        }
    }

    // ---- 이번 시도의 쓰기 (판정 F13) ----

    @Test
    void 이번시도의_쓰기는_기준_id보다_큰_실행됨_승인대기만() {
        assertThat(DbScriptFile.wroteSince(ENTRIES, 12)).isTrue();    // #13 DEFERRED·#15 EXECUTED
        assertThat(DbScriptFile.wroteSince(ENTRIES, 15)).isFalse();   // #16 DISMISSED·#17 FAILED만 남는다
        assertThat(DbScriptFile.wroteSince(List.of(change(30, 2, "DROP TABLE t", "REJECTED")), 12)).isFalse();
        assertThat(DbScriptFile.wroteSince(List.of(), 0)).isFalse();
        assertThat(DbScriptFile.wroteSince(null, 0)).isFalse();
    }

    // ---- 성공 판정 (스펙 §6.6) ----

    @Test
    void DB작업은_코드나_스크립트_다른타입은_코드나_이번시도_쓰기() {
        // DB 작업 — 누적 파일만으로도 성공
        assertThat(DbScriptFile.hasWork(TaskType.DB_WORK, false, false, true)).isTrue();
        assertThat(DbScriptFile.hasWork(TaskType.DB_WORK, true, false, false)).isTrue();
        assertThat(DbScriptFile.hasWork(TaskType.DB_WORK, false, true, false)).isFalse();
        assertThat(DbScriptFile.hasWork(TaskType.DB_WORK, false, false, false)).isFalse();
        // 다른 타입·타입 없음 — 이번 시도의 코드 변경이나 쓰기 기록
        for (TaskType type : new TaskType[]{null, TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE, TaskType.BUG_FIX}) {
            assertThat(DbScriptFile.hasWork(type, false, false, true)).as("%s 누적 파일만", type).isFalse();
            assertThat(DbScriptFile.hasWork(type, false, true, true)).as("%s 이번 시도 쓰기", type).isTrue();
            assertThat(DbScriptFile.hasWork(type, true, false, false)).as("%s 코드", type).isTrue();
        }
    }
}
