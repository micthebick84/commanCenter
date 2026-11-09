package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbMcpWorkerSupportTest {

    private final DbMcpGrant grant = new DbMcpGrant("tok",
            List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영 (MySQL)", "MYSQL")),
            List.of("DB 연결 #13를 사용할 수 없습니다"));

    @Test
    void appends_http_servers_with_bearer_header() {
        List<TaskMcpSpec> out = DbMcpWorkerSupport.withDbServers(
                List.of(new TaskMcpSpec("ctx7", "https://c", "http")), grant, "http://localhost:8090/");
        assertThat(out).hasSize(2);
        assertThat(out.get(1)).isEqualTo(new TaskMcpSpec("db-12", "http://localhost:8090/mcp/db/12", "http",
                Map.of("Authorization", "Bearer tok")));
    }

    @Test
    void no_grant_or_no_token_keeps_extras() {
        List<TaskMcpSpec> extras = List.of(new TaskMcpSpec("ctx7", "https://c", "http"));
        assertThat(DbMcpWorkerSupport.withDbServers(extras, null, "http://x")).isEqualTo(extras);
        assertThat(DbMcpWorkerSupport.withDbServers(null, new DbMcpGrant(null, List.of(), List.of("꺼짐")), "http://x")).isEmpty();
    }

    @Test
    void prompt_section_lists_tools_and_notices_without_token() {
        String s = DbMcpWorkerSupport.promptSection(grant);
        assertThat(s).contains("## 데이터베이스 조회 (읽기 전용)")
                .contains("- mcp__db-12__query / list_tables / describe_table : 운영 (MySQL)")
                .contains("참고: DB 연결 #13를 사용할 수 없습니다")
                .doesNotContain("tok");
        assertThat(DbMcpWorkerSupport.promptSection(null)).isEmpty();
    }

    // ---- 개발 DB 쓰기 (스펙 2026-11-03 §6.7·§6.8) ----

    private static DbMcpGrant.Server writable(String name, String label) {
        return new DbMcpGrant.Server(name, "/mcp/db/" + name.substring(3), label, "POSTGRESQL", true);
    }

    private static DbMcpGrant.Server readOnly(String name, String label) {
        return new DbMcpGrant.Server(name, "/mcp/db/" + name.substring(3), label, "MYSQL", false);
    }

    private static DbMcpGrant grantWith(DbMcpGrant.Server... servers) {
        return new DbMcpGrant("tok", List.of(servers), List.of());
    }

    /** 쓰기 연결이 없으면 지금(스펙 2026-10-14) 문구와 한 글자도 다르지 않다 — 쓰기 안내를 허용해도, 읽기 연결만이면 그대로. */
    @Test
    void 쓰기연결이_없으면_지금_문구_그대로() {
        String expected = "\n\n## 데이터베이스 조회 (읽기 전용)\n"
                + "이 작업에는 아래 DB 조회 도구가 붙어 있습니다. 스키마·데이터를 확인할 때 쓰세요. "
                + "SELECT 계열 단일 문장만 실행되고 최대 200행·30초 제한입니다. "
                + "쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다.\n"
                + "- mcp__db-12__query / list_tables / describe_table : 운영 (MySQL)\n"
                + "참고: DB 연결 #13를 사용할 수 없습니다\n";

        assertThat(DbMcpWorkerSupport.promptSection(grant)).isEqualTo(expected);
        assertThat(DbMcpWorkerSupport.promptSection(grant, true)).isEqualTo(expected);
        assertThat(DbMcpWorkerSupport.promptSection(grant, false)).isEqualTo(expected);
        // 알림만 있는 grant(토큰·연결 없음)도 그대로
        DbMcpGrant noticeOnly = new DbMcpGrant(null, List.of(), List.of("DB 쓰기 꺼짐"));
        assertThat(DbMcpWorkerSupport.promptSection(noticeOnly, true))
                .isEqualTo("\n\n## 데이터베이스 조회 (읽기 전용)\n참고: DB 쓰기 꺼짐\n")
                .isEqualTo(DbMcpWorkerSupport.promptSection(noticeOnly));
        assertThat(DbMcpWorkerSupport.promptSection(null, true)).isEmpty();
        assertThat(DbMcpWorkerSupport.promptSection(new DbMcpGrant("t", List.of(), List.of()), true)).isEmpty();
    }

    @Test
    void 쓰기연결_절() {
        String s = DbMcpWorkerSupport.promptSection(grantWith(writable("db-12", "개발 (PostgreSQL)")), true);

        assertThat(s).startsWith("\n\n## 개발 DB 쓰기 (함께 쓰는 개발 DB)\n")
                .contains("- mcp__db-12__query / list_tables / describe_table / execute : 개발 (PostgreSQL)")
                .contains("옛 컬럼은 두고 새 컬럼을 추가해").contains("[테스트]")
                .contains("레포에 마이그레이션 파일을 만들지 마세요")
                .contains(".netis/db-notes.md").contains("자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요")
                .contains("`execute`").contains("승인 대기")
                .doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다")
                .doesNotContain("## 데이터베이스 조회 (읽기 전용)")
                .doesNotContain("tok");
        // 세션은 PR 본문을 쓸 수 없다 — 자동 마이그레이션 장치를 보면 워커 임시 파일에 남긴다(계획 "설계와 다름 ①")
        assertThat(s).doesNotContain("PR 본문에 '자동 마이그레이션");
        // §6.7 순서 — 안내(마이그레이션 파일 금지·자동 마이그레이션 감지)는 맨 끝
        assertThat(s.indexOf("옛 컬럼은 두고")).isLessThan(s.indexOf("[테스트]"));
        assertThat(s.indexOf("[테스트]")).isLessThan(s.indexOf("`query`로 결과를 확인"));
        assertThat(s.indexOf("`query`로 결과를 확인")).isLessThan(s.indexOf("레포에 마이그레이션 파일을 만들지 마세요"));
        assertThat(s.indexOf("레포에 마이그레이션 파일을 만들지 마세요")).isLessThan(s.indexOf(".netis/db-notes.md"));
        assertThat(s).endsWith("\n");
    }

    /** 디자인만 1인자를 쓴다 — 서버가 writable로 줘도(디자인 토큰은 쓰기 도구가 없다) 쓰기 절은 없다. */
    @Test
    void 디자인_프롬프트에는_쓰기절이_없다() {
        DbMcpGrant g = grantWith(writable("db-12", "개발 (PostgreSQL)"));

        String s = DbMcpWorkerSupport.promptSection(g);

        assertThat(s).contains("## 데이터베이스 조회 (읽기 전용)")
                .contains("- mcp__db-12__query / list_tables / describe_table : 개발 (PostgreSQL)")
                .contains("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다")
                .doesNotContain("개발 DB 쓰기").doesNotContain("execute");
        assertThat(DbMcpWorkerSupport.promptSection(g, false)).isEqualTo(s);
    }

    /** 쓰기 연결과 읽기 연결이 섞이면 둘 다 안내한다 — 읽기 연결 줄에는 execute가 없고, 알림은 맨 끝. */
    @Test
    void 섞인_연결은_쓰기절과_읽기절_둘_다() {
        DbMcpGrant g = new DbMcpGrant("tok",
                List.of(writable("db-12", "개발 (PostgreSQL)"), readOnly("db-13", "운영 (MySQL)")),
                List.of("DB 연결 #14를 사용할 수 없습니다"));

        String s = DbMcpWorkerSupport.promptSection(g, true);

        assertThat(s).contains("- mcp__db-12__query / list_tables / describe_table / execute : 개발 (PostgreSQL)")
                .contains("- mcp__db-13__query / list_tables / describe_table : 운영 (MySQL)")
                .doesNotContain("mcp__db-13__query / list_tables / describe_table / execute")
                .doesNotContain("mcp__db-12__query / list_tables / describe_table :");
        assertThat(s.indexOf("## 개발 DB 쓰기")).isLessThan(s.indexOf("## 데이터베이스 조회 (읽기 전용)"));
        // 읽기 절의 서버 거부 문구는 읽기 연결에만 해당한다 — 쓰기 연결 줄은 읽기 절에 없다
        String readSection = s.substring(s.indexOf("## 데이터베이스 조회 (읽기 전용)"));
        assertThat(readSection).contains("서버가 거부합니다").doesNotContain("db-12");
        assertThat(s).endsWith("참고: DB 연결 #14를 사용할 수 없습니다\n");
    }

    @Test
    void 쓰기연결_절_뒤에_알림이_온다() {
        DbMcpGrant g = new DbMcpGrant("tok", List.of(writable("db-12", "개발 (PostgreSQL)")), List.of("참고할 것"));

        assertThat(DbMcpWorkerSupport.promptSection(g, true)).endsWith("\n참고: 참고할 것\n");
    }

    /** 토큰이 없으면 도구가 안 붙는다(withDbServers) — 쓰기 절도 쓰지 않는다. */
    @Test
    void 토큰이_없으면_쓰기절이_없다() {
        DbMcpGrant g = new DbMcpGrant(null, List.of(writable("db-12", "개발 (PostgreSQL)")), List.of("꺼짐"));

        assertThat(DbMcpWorkerSupport.promptSection(g, true))
                .isEqualTo("\n\n## 데이터베이스 조회 (읽기 전용)\n참고: 꺼짐\n");
    }

    @Test
    void anyWritable은_토큰과_쓰기_연결이_함께_있어야_참() {
        assertThat(DbMcpWorkerSupport.anyWritable(grantWith(writable("db-12", "a")))).isTrue();
        assertThat(DbMcpWorkerSupport.anyWritable(grantWith(readOnly("db-13", "b"), writable("db-12", "a")))).isTrue();
        assertThat(DbMcpWorkerSupport.anyWritable(grantWith(readOnly("db-13", "b")))).isFalse();
        assertThat(DbMcpWorkerSupport.anyWritable(new DbMcpGrant(null, List.of(writable("db-12", "a")), List.of()))).isFalse();
        assertThat(DbMcpWorkerSupport.anyWritable(new DbMcpGrant("t", List.of(), List.of()))).isFalse();
        assertThat(DbMcpWorkerSupport.anyWritable(null)).isFalse();
    }

    @Test
    void 자동마이그레이션_절() {
        String s = DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("db/symphony/task-42.sql", true, false));

        assertThat(s).isEqualTo("\n## DB 변경 (자동 마이그레이션 레포)\n"
                + "이 레포는 앱이 뜰 때 마이그레이션 파일을 DB에 자동 적용합니다. DB 변경이 필요하면 레포의 마이그레이션 관례대로 파일로 만드세요. "
                + "가데이터도 관례가 허용하면 그 방식으로 넣으세요. 개발 DB에는 시험 컨테이너가 뜰 때 적용됩니다 — DB에 직접 실행하지 마세요.\n"
                + "- 같은 DB를 다른 사람도 씁니다. 기존 컬럼을 지우거나 이름·타입을 바꿔야 하면 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요. "
                + "옛 컬럼 삭제는 이번 변경에 넣지 말고 병합 뒤 별도 작업으로 남기세요.\n");
        assertThat(s).contains("## DB 변경 (자동 마이그레이션 레포)").contains("레포의 마이그레이션 관례대로 파일로 만드세요")
                .contains("가데이터도 관례가 허용하면").contains("시험 컨테이너가 뜰 때 적용됩니다")
                .contains("옛 컬럼은 두고 새 컬럼을 추가해").contains("옛 컬럼 삭제는 이번 변경에 넣지 말고 병합 뒤 별도 작업으로");
        assertThat(DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("x", false, false))).isEmpty();
        assertThat(DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("x", false, true))).isEmpty();
        assertThat(DbMcpWorkerSupport.migrationManagedSection(null)).isEmpty();
    }

    private static DbChangeView change(long id, String conn, String sql, String classification) {
        return new DbChangeView(id, 5L, conn, 1, null, sql, classification, null, null, null,
                OffsetDateTime.parse("2026-11-04T01:00:00Z"), null, null);
    }

    @Test
    void 이미적용된_SQL_절() {
        List<DbChangeView> log = List.of(
                change(12, "개발 (PostgreSQL)", "CREATE TABLE t_new (id int)", "EXECUTED"),
                change(13, "개발 (PostgreSQL)", "ALTER TABLE t_old DROP COLUMN c", "DEFERRED"),
                change(14, "개발 (PostgreSQL)", "DROP TABLE t_old", "REJECTED"),
                change(15, "개발 (PostgreSQL)", "INSERT INTO t_new VALUES (broken", "FAILED"),
                change(16, "개발 (PostgreSQL)", "ALTER TABLE t_old DROP COLUMN d", "DISMISSED"));

        String s = DbMcpWorkerSupport.appliedSqlSection(log);

        assertThat(s).startsWith("\n## 이미 적용된 SQL (다시 실행하지 마세요)\n")
                .contains("describe_table").contains("query")
                .contains("- [실행됨] #12 개발 (PostgreSQL): CREATE TABLE t_new (id int)\n")
                .contains("- [승인 대기] #13 개발 (PostgreSQL): ALTER TABLE t_old DROP COLUMN c\n")
                .doesNotContain("DROP TABLE t_old").doesNotContain("#14").doesNotContain("#15").doesNotContain("#16");
        assertThat(s.indexOf("#12")).isLessThan(s.indexOf("#13"));
        assertThat(s).endsWith("\n");
    }

    @Test
    void 이미적용된_SQL_절은_해당_기록이_없으면_빈_문자열() {
        assertThat(DbMcpWorkerSupport.appliedSqlSection(null)).isEmpty();
        assertThat(DbMcpWorkerSupport.appliedSqlSection(List.of())).isEmpty();
        assertThat(DbMcpWorkerSupport.appliedSqlSection(List.of(
                change(1, "개발", "DROP TABLE x", "REJECTED"), change(2, "개발", "x", "FAILED"),
                change(3, "개발", "y", "DISMISSED"), change(4, "개발", "z", "모르는 값"))))
                .isEmpty();
    }

    @Test
    void 이미적용된_SQL_절은_여러_줄_문장을_목록_항목_안에_둔다() {
        String s = DbMcpWorkerSupport.appliedSqlSection(List.of(
                change(12, "개발", "CREATE TABLE t_new (\r\n  id int\r\n)", "EXECUTED"),
                change(13, null, "UPDATE t SET a = 1 WHERE b = 2", "DEFERRED")));

        assertThat(s).contains("- [실행됨] #12 개발: CREATE TABLE t_new (\n    id int\n  )\n")
                .contains("- [승인 대기] #13: UPDATE t SET a = 1 WHERE b = 2\n")
                .doesNotContain("\r");
    }

    @Test
    void 이미적용된_SQL_절은_20000자_상한() {
        List<DbChangeView> log = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            log.add(change(i, "개발 (PostgreSQL)", "INSERT INTO t VALUES ('" + "x".repeat(900) + "')", "EXECUTED"));
        }

        String s = DbMcpWorkerSupport.appliedSqlSection(log);

        assertThat(s.length()).isLessThanOrEqualTo(DbMcpWorkerSupport.APPLIED_SQL_MAX_CHARS);
        assertThat(s).contains("#1 ").doesNotContain("#60 ").contains("건 생략")
                .startsWith("\n## 이미 적용된 SQL (다시 실행하지 마세요)\n");
        // 생략한 개수 = 60 - 실은 개수
        long listed = s.lines().filter(l -> l.startsWith("- [실행됨]")).count();
        assertThat(s).contains("외 " + (60 - listed) + "건 생략");
    }

    /** 문장 하나가 상한보다 커도 절이 비지 않고 상한을 넘지 않는다. */
    @Test
    void 이미적용된_SQL_절은_상한보다_큰_문장_하나도_잘라서_싣는다() {
        String s = DbMcpWorkerSupport.appliedSqlSection(List.of(
                change(12, "개발", "INSERT INTO t VALUES ('" + "y".repeat(50_000) + "')", "EXECUTED")));

        assertThat(s.length()).isLessThanOrEqualTo(DbMcpWorkerSupport.APPLIED_SQL_MAX_CHARS);
        assertThat(s).contains("- [실행됨] #12 개발: INSERT INTO t VALUES ('yyy").contains("…(이하 생략)");
    }
}
