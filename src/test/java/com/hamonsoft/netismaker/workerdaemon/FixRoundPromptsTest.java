package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 수정 회차 프롬프트 조각 — 원격 끝 비교·앞 회차 번호·리뷰 절 (스펙 2026-11-03 §7). */
class FixRoundPromptsTest {

    private static final String TIP = "abc1234" + "0".repeat(33);

    private static WorkerTaskResponse task(String design) {
        return new WorkerTaskResponse(42L, "acme/widgets", "main", "Add login", "로그인 화면",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), null, "[]", null, null, List.of(),
                null, null, design, null, null, null, null, null, null, null, null);
    }

    private static WorkerTaskResponse.FixRoundSpec fix(int round, List<String> previous) {
        return new WorkerTaskResponse.FixRoundSpec(9L, round, 1, "  저장 버튼이 안 눌림  ", previous,
                "netismaker/task-42-x", "abc1234");
    }

    @Test
    void 원격_끝_비교는_기록의_앞부분과_대소문자_무시() {
        assertThat(FixRoundPrompts.remoteAhead(TIP, "abc1234")).isFalse();
        assertThat(FixRoundPrompts.remoteAhead(TIP, "ABC1234")).isFalse();
        assertThat(FixRoundPrompts.remoteAhead(TIP, TIP)).isFalse();
        assertThat(FixRoundPrompts.remoteAhead("def5678" + "0".repeat(33), "abc1234")).isTrue();
        // 기록이 없으면 비교할 수 없다 — 앞섰다고 하지 않는다
        assertThat(FixRoundPrompts.remoteAhead(TIP, null)).isFalse();
        assertThat(FixRoundPrompts.remoteAhead(TIP, " ")).isFalse();
    }

    @Test
    void 원격_끝_메모는_두_커밋을_7자로() {
        assertThat(FixRoundPrompts.remoteAheadNote("def5678" + "0".repeat(33), TIP))
                .startsWith("원격 브랜치 끝이 def5678입니다(기록된 구현 커밋은 abc1234)");
    }

    @Test
    void 앞_회차_본문은_회차_번호를_달고_오래된_것부터() {
        String p = FixRoundPrompts.render(task(null), fix(3, List.of("첫 요청", "둘째 요청")), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).contains("## 이번 수정 요청 (수정 3회차)\n저장 버튼이 안 눌림\n")
                .contains("### 수정 1회차\n첫 요청").contains("### 수정 2회차\n둘째 요청")
                .contains("앞선 수정 회차에서 고친 것");
        assertThat(p.indexOf("첫 요청")).isLessThan(p.indexOf("둘째 요청"));
    }

    @Test
    void 첫_회차는_앞_회차_절이_없다() {
        String p = FixRoundPrompts.render(task(null), fix(1, List.of()), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).doesNotContain("앞선 수정").doesNotContain("마지막 리뷰");
    }

    @Test
    void 확정_디자인이_있으면_원래_작업에_참고용_H3로_넣는다() {
        String p = FixRoundPrompts.render(task("# 디자인 문서"), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).contains("\n### 확정된 디자인 (참고용 — 이미 구현됨, 이번 수정 범위만 고칠 것)\n")
                .contains("# 디자인 문서")
                .contains("`.design/`은 참조용이며 커밋하지 마세요.");
        // 보통 구현의 H2 절("반드시 이 디자인 기준으로 구현")은 회차 에이전트가 이미 된 화면을 다시 만들게 한다
        assertThat(p).doesNotContain("반드시 이 디자인 기준으로 구현").doesNotContain("\n## 확정된 디자인");
        // "원래 작업" 절 안(H3)에 들어간다
        assertThat(p.indexOf("## 원래 작업 (참고용)")).isLessThan(p.indexOf("### 확정된 디자인"));
    }

    @Test
    void 확정_디자인이_없으면_디자인_절이_없다() {
        String p = FixRoundPrompts.render(task(" "), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).doesNotContain("확정된 디자인");
    }

    @Test
    void 보통_구현_프롬프트의_디자인_절은_그대로_H2다() {
        assertThat(WorkerMainLoop.renderDesignSection(task("# 디자인 문서")))
                .contains("\n## 확정된 디자인 (반드시 이 디자인 기준으로 구현)\n").contains("# 디자인 문서");
    }

    @Test
    void 리뷰_방식은_수정_요청_기준_리뷰어_지시를_채운다() {
        String p = FixRoundPrompts.render(task(null), fix(2, List.of("첫 요청")), TIP, false,
                ImplementationMode.REVIEW, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).contains("## 마지막 리뷰 (수정과 테스트를 마친 뒤 1회)")
                .contains("리뷰 범위는 이번 수정 요청 본문입니다")
                .contains("This is fix round 2 on a branch")
                .contains("git diff " + TIP)
                .doesNotContain("__REVIEWER__").doesNotContain("__REQUIREMENTS__").doesNotContain("__BASE_SHA__")
                .doesNotContain("MIT License");   // 리소스 머리 주석은 빠진다
        // 리뷰 절은 배포 env 지시보다 앞
        assertThat(p.indexOf("## 마지막 리뷰")).isLessThan(p.indexOf("## 배포 환경변수 목록"));
    }

    // ---- 개발 DB 쓰기 (스펙 2026-11-03 §5.2 — 회차 프롬프트는 DB 절과 이미 적용된 SQL 절만 넣는다) ----

    private static WorkerTaskResponse taskWithWritableDb() {
        DbMcpGrant grant = new DbMcpGrant("tok", List.of(
                new DbMcpGrant.Server("db-12", "/mcp/db/12", "개발 (PostgreSQL)", "POSTGRESQL", true)), List.of());
        return task(null).withDbMcp(grant);
    }

    @Test
    void 회차_프롬프트는_쓰기_연결이면_쓰기_절을_넣는다() {
        String p = FixRoundPrompts.render(taskWithWritableDb(), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE);

        assertThat(p).contains("## 개발 DB 쓰기 (함께 쓰는 개발 DB)")
                .contains("- mcp__db-12__query / list_tables / describe_table / execute : 개발 (PostgreSQL)")
                .doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다");
    }

    /** 7인자(참조까지)는 dbExtra가 "" — DB 절만. 타입 절은 회차에 들어가지 않는다. */
    @Test
    void dbExtra가_없으면_DB_절_뒤에_아무것도_붙지_않는다() {
        String seven = FixRoundPrompts.render(taskWithWritableDb(), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE, List.of());
        String eight = FixRoundPrompts.render(taskWithWritableDb(), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE, List.of(), "");

        assertThat(eight).isEqualTo(seven);
        assertThat(seven).doesNotContain("## 이미 적용된 SQL").doesNotContain("## DB 변경 (자동 마이그레이션 레포)")
                .doesNotContain("## 작업 타입");
    }

    /** 붙는 순서: 배포 env 지시 → DB 절 → dbExtra(자동 마이그레이션 절 → 이미 적용된 SQL 절) → 참조 절. */
    @Test
    void dbExtra는_DB_절_바로_뒤에_붙는다() {
        String dbExtra = DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("db/symphony/task-42.sql", true, false))
                + DbMcpWorkerSupport.appliedSqlSection(List.of(new DbChangeView(12L, 5L, "개발 (PostgreSQL)", 1, null,
                "CREATE TABLE t_new (id int)", "EXECUTED", null, null, null, null, null, null)));
        String p = FixRoundPrompts.render(taskWithWritableDb(), fix(1, null), TIP, false,
                ImplementationMode.DIRECT, ImplementationPrompts.DIRECT_COMMIT_RULE, List.of(), dbExtra);

        assertThat(p).endsWith(DbMcpWorkerSupport.promptSection(taskWithWritableDb().dbMcp(), true) + dbExtra);
        assertThat(p.indexOf("## 배포 환경변수 목록")).isLessThan(p.indexOf("## 개발 DB 쓰기"));
        assertThat(p.indexOf("## 개발 DB 쓰기")).isLessThan(p.indexOf("## DB 변경 (자동 마이그레이션 레포)"));
        assertThat(p.indexOf("## DB 변경 (자동 마이그레이션 레포)")).isLessThan(p.indexOf("## 이미 적용된 SQL"));
        assertThat(p).contains("- [실행됨] #12 개발 (PostgreSQL): CREATE TABLE t_new (id int)");
    }
}
