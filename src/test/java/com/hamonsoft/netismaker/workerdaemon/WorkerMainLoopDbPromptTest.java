package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 워커의 개발 DB 쓰기 안내 — 프롬프트의 DB 절·자동 마이그레이션 절·이미 적용된 SQL 절, 구현 전 DB 변경 기록 사전 조회, 쓰기 도구가 없을 때의
 * manual 스크립트 경로, DB 메모의 PR 본문 이동 (스펙 2026-11-03 §5.2·§6.6~§6.8). 하네스는 WorkerMainLoopTaskTypeTest를 따른다.
 */
class WorkerMainLoopDbPromptTest {

    private static final String SCRIPT = "db/symphony/task-7.sql";
    private static final String MANUAL = "db/symphony/task-7-manual.sql";
    private static final String BRANCH = "netismaker/task-7-add-login";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private Path wt;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        repos = mock(GitRepoCache.class);
        claude = mock(ClaudeExecAdapter.class);
        worktrees = mock(WorktreeService.class);
        gitOps = mock(GitOpsService.class);
        reporter = mock(ResultReporter.class);
        wt = Files.createDirectories(tmp.resolve("wt"));

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        // 수정 회차 하네스
        when(repos.fetchOnly(any(), eq(BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        when(worktrees.createOnBranch(any(), anyString(), eq(BRANCH), eq(7L)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(gitOps.headSha(any())).thenReturn("abc1234" + "0".repeat(33));
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(""), mock(ClaudeAuthProbe.class));
    }

    // ---- 재료 ----

    private static DbMcpGrant writableGrant() {
        return new DbMcpGrant("tok", List.of(
                new DbMcpGrant.Server("db-12", "/mcp/db/12", "개발 (PostgreSQL)", "POSTGRESQL", true)), List.of());
    }

    private static DbMcpGrant readOnlyGrant() {
        return new DbMcpGrant("tok", List.of(
                new DbMcpGrant.Server("db-12", "/mcp/db/12", "개발 (PostgreSQL)", "POSTGRESQL", false)), List.of());
    }

    private static DbWriteSpec db(boolean migrationManaged, boolean priorWrites) {
        return new DbWriteSpec(SCRIPT, migrationManaged, priorWrites);
    }

    private static DbChangeView change(long id, String sql, String classification) {
        return new DbChangeView(id, 5L, "개발 (PostgreSQL)", 1, null, sql, classification, null, null, null,
                OffsetDateTime.parse("2026-11-04T01:00:00Z"), null, null);
    }

    private static Task task(TaskType type) {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setTaskType(type);
        return t;
    }

    /** 구현 claim. grant·db가 null이면 그 재료 없음. */
    private WorkerTaskResponse claims(TaskType type, DbMcpGrant grant, DbWriteSpec db) {
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(task(type),
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: A", null, null, db);
        if (grant != null) task = task.withDbMcp(grant);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    /** 수정 2회차 claim. */
    private WorkerTaskResponse claimsFixRound(TaskType type, DbMcpGrant grant, DbWriteSpec db) {
        var fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, "저장 뒤 목록이 그대로", List.of(), BRANCH, "abc1234");
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(task(type),
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: A", fix,
                ImplementationMode.DIRECT, db);
        if (grant != null) task = task.withDbMcp(grant);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    private AtomicReference<String> claudeSucceeds(Consumer<Path> sideEffect) throws Exception {
        AtomicReference<String> prompt = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    prompt.set(inv.getArgument(0));
                    sideEffect.accept(wt);
                    return new ClaudeExecAdapter.ExecResult(0, "done", 10, null);
                });
        return prompt;
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    private String prBody() throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());
        return body.getValue();
    }

    // ---- 사전 조회 ----

    @Test
    void 쓰기도_이전기록도_없으면_조회안함() throws Exception {
        claims(TaskType.BUG_FIX, readOnlyGrant(), db(false, false));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        assertThat(prompt.get()).doesNotContain("## 이미 적용된 SQL").doesNotContain("## 개발 DB 쓰기")
                .contains("## 데이터베이스 조회 (읽기 전용)");
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void DB재료도_연결도_없는_옛_claim은_조회안함() throws Exception {
        claims(null, null, null);
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        assertThat(prompt.get()).doesNotContain("## 이미 적용된 SQL").doesNotContain("## DB 변경");
    }

    @Test
    void 이전쓰기가_있으면_사전조회해_절을_넣는다() throws Exception {
        claims(TaskType.BUG_FIX, null, db(false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(
                change(12, "CREATE TABLE t_new (id int)", "EXECUTED"),
                change(13, "ALTER TABLE t_old DROP COLUMN c", "DEFERRED"),
                change(14, "DROP TABLE t_old", "REJECTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("## 이미 적용된 SQL (다시 실행하지 마세요)")
                .contains("- [실행됨] #12 개발 (PostgreSQL): CREATE TABLE t_new (id int)")
                .contains("- [승인 대기] #13 개발 (PostgreSQL): ALTER TABLE t_old DROP COLUMN c")
                .doesNotContain("DROP TABLE t_old");
        // 조회는 worktree 생성 뒤 claude 전 한 번 — 참조 준비 뒤라서 참조 실패 경로의 "대상 상호작용 없음"도 유지된다
        InOrder order = inOrder(worktrees, http, claude);
        order.verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt());
        order.verify(http).dbChanges(7L);
        order.verify(claude).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verify(http, times(1)).dbChanges(anyLong());
    }

    @Test
    void 쓰기연결만_있으면_조회하고_쓰기_절을_넣는다() throws Exception {
        claims(TaskType.NEW_FEATURE, writableGrant(), db(false, false));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(http).dbChanges(7L);
        assertThat(prompt.get()).contains("## 개발 DB 쓰기 (함께 쓰는 개발 DB)")
                .contains("- mcp__db-12__query / list_tables / describe_table / execute : 개발 (PostgreSQL)")
                .doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다")
                .doesNotContain("## 이미 적용된 SQL")   // 기록이 없으면 절도 없다
                .doesNotContain("## DB 변경 (자동 마이그레이션 레포)");
    }

    /** 붙는 순서 — 배포 env 지시 → DB 절 → 이미 적용된 SQL 절. */
    @Test
    void DB_절_뒤에_이미_적용된_SQL_절이_온다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        String p = prompt.get();
        assertThat(p.indexOf("## 배포 환경변수 목록")).isLessThan(p.indexOf("## 개발 DB 쓰기"));
        assertThat(p.indexOf("## 개발 DB 쓰기")).isLessThan(p.indexOf("## 이미 적용된 SQL"));
        assertThat(p).endsWith(DbMcpWorkerSupport.appliedSqlSection(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"))));
    }

    @Test
    void 사전조회_실패면_claude없이_구현실패() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("DB 변경 기록 조회 실패").contains("503");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.headSha()).isNull();
        assertThat(r.implementationAttempt()).isEqualTo(1);
        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(gitOps);
        // 같은 SQL이 두 번 실행되지 않게 — PR도 만들지 않는다
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 사전조회가_메시지_없는_예외여도_구현실패() throws Exception {
        claims(TaskType.NEW_FEATURE, writableGrant(), db(false, false));
        when(http.dbChanges(7L)).thenThrow(new IllegalStateException());

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("DB 변경 기록 조회 실패: ").doesNotContain("null");
        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
    }

    @Test
    void 사전조회_실패_사유의_접속정보는_가려진다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenThrow(new IllegalStateException("clone https://oauth2:glpat-SECRET@gitlab.example/g/p.git"));

        loop().pollAndProcess();

        assertThat(reported().failureReason()).doesNotContain("glpat-SECRET");
    }

    // ---- 자동 마이그레이션 레포 ----

    @Test
    void 자동마이그레이션_레포는_연결이_없어도_절이_붙는다() throws Exception {
        claims(TaskType.BUG_FIX, null, db(true, false));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("## DB 변경 (자동 마이그레이션 레포)")
                .contains("옛 컬럼은 두고 새 컬럼을 추가해")
                .doesNotContain("## 개발 DB 쓰기").doesNotContain("## 이미 적용된 SQL");
        verify(http, never()).dbChanges(anyLong());
    }

    /** needsDbLog = !migrationManaged && … — 레포 표시를 켠 뒤에는 이전 기록이 있어도 조회하지 않는다(기록·파일이 생기지 않는 레포). */
    @Test
    void 자동마이그레이션_레포는_이전기록이_있어도_조회하지_않는다() throws Exception {
        claims(TaskType.NEW_FEATURE, readOnlyGrant(), db(true, true));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        assertThat(prompt.get()).contains("## DB 변경 (자동 마이그레이션 레포)").doesNotContain("## 이미 적용된 SQL");
    }

    @Test
    void 자동마이그레이션_절은_타입과_무관하게_붙는다() throws Exception {
        for (TaskType type : new TaskType[]{null, TaskType.NEW_FEATURE, TaskType.FEATURE_CHANGE, TaskType.BUG_FIX, TaskType.DB_WORK}) {
            setUp();
            claims(type, null, db(true, false));
            AtomicReference<String> prompt = claudeSucceeds(dir -> { });

            loop().pollAndProcess();

            assertThat(prompt.get()).as("타입 %s", type).contains("## DB 변경 (자동 마이그레이션 레포)");
        }
    }

    // ---- DB 작업 타입 절의 세 갈래 호출부 ----

    @Test
    void DB작업_타입절_세_갈래() throws Exception {
        // 쓰기 도구 있음 → execute로 한 문장씩, 파일 경로는 말하지 않는다
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        AtomicReference<String> withTool = claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        assertThat(withTool.get()).contains("`execute` 도구로 한 문장씩").doesNotContain("쓰기 도구가 없습니다")
                .doesNotContain("`" + SCRIPT + "` 파일에");

        // 자동 마이그레이션 → 마이그레이션 관례대로
        setUp();
        claims(TaskType.DB_WORK, null, db(true, false));
        AtomicReference<String> migration = claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        assertThat(migration.get()).contains("## 작업 타입: DB 작업\n이 레포는 앱이 뜰 때 마이그레이션 파일을 DB에 자동 적용합니다.")
                .contains("마이그레이션 관례대로").doesNotContain("`execute` 도구로");

        // 쓰기 도구 없음 → 스크립트 파일에 적기
        setUp();
        claims(TaskType.DB_WORK, readOnlyGrant(), db(false, false));
        AtomicReference<String> noTool = claudeSucceeds(dir -> { });
        loop().pollAndProcess();
        assertThat(noTool.get()).contains("이 구현에는 개발 DB 쓰기 도구가 없습니다.").contains("`" + SCRIPT + "` 파일에 실행 순서대로");
    }

    /** 이전 쓰기 기록이 있는데 이번 구현에 쓰기 도구가 없으면 DB 작업 지시의 파일은 -manual — 워커의 누적 기록 파일을 덮어쓰지 않게. */
    @Test
    void 이전쓰기가_있는데_쓰기도구가_없으면_manual_경로() throws Exception {
        claims(TaskType.DB_WORK, readOnlyGrant(), db(false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("`" + MANUAL + "` 파일에 실행 순서대로").doesNotContain("`" + SCRIPT + "`")
                .contains("## 이미 적용된 SQL (다시 실행하지 마세요)");
    }

    @Test
    void 쓰기도구가_있으면_이전기록이_있어도_원래_경로_지시는_필요없다() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("`execute` 도구로 한 문장씩").doesNotContain(MANUAL);
    }

    @Test
    void 카탈로그가_정한_폴더가_지시에_반영된다() throws Exception {
        claims(TaskType.DB_WORK, readOnlyGrant(), new DbWriteSpec("sql/dev/task-7.sql", false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(change(12, "SELECT 1", "EXECUTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("`sql/dev/task-7-manual.sql` 파일에 실행 순서대로");
    }

    // ---- DB 메모 ----

    @Test
    void DB메모는_리뷰메모_뒤에_붙는다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> {
            write(dir.resolve(".netis/review-notes.md"), "R");
            write(dir.resolve(".netis/db-notes.md"), "D");
        });

        loop().pollAndProcess();

        assertThat(prBody()).contains("## 리뷰 메모\nR\n\nD\n\n");
    }

    @Test
    void DB메모만_있어도_리뷰_메모_절로_옮긴다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> write(dir.resolve(".netis/db-notes.md"), "자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요"));

        loop().pollAndProcess();

        assertThat(prBody()).contains("## 리뷰 메모\n자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요\n\n");
    }

    @Test
    void 리뷰메모만_있으면_그대로() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> write(dir.resolve(".netis/review-notes.md"), "R"));

        loop().pollAndProcess();

        assertThat(prBody()).contains("## 리뷰 메모\nR\n\n").doesNotContain("R\n\nD");
    }

    @Test
    void 메모가_둘_다_없으면_리뷰_메모_절이_없다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prBody()).doesNotContain("## 리뷰 메모");
    }

    /** DB 메모 파일은 커밋 전에 지워진다 — .netis/ 통째로. */
    @Test
    void DB메모_파일은_커밋_전에_지워진다() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> write(dir.resolve(".netis/db-notes.md"), "D"));
        AtomicReference<Boolean> existedAtCommit = new AtomicReference<>();
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            existedAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop().pollAndProcess();

        assertThat(existedAtCommit.get()).isFalse();
        assertThat(prBody()).contains("## 리뷰 메모\nD\n\n");
    }

    // ---- 수정 회차 ----

    @Test
    void 수정회차도_DB절과_이미적용된_SQL절() throws Exception {
        claimsFixRound(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenReturn(List.of(
                change(12, "CREATE TABLE t_new (id int)", "EXECUTED"), change(14, "DROP TABLE t_old", "REJECTED")));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("## 개발 DB 쓰기 (함께 쓰는 개발 DB)")
                .doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다")
                .contains("## 이미 적용된 SQL (다시 실행하지 마세요)")
                .contains("- [실행됨] #12 개발 (PostgreSQL): CREATE TABLE t_new (id int)")
                .doesNotContain("DROP TABLE t_old")
                .doesNotContain("## 작업 타입");
        assertThat(prompt.get().indexOf("## 개발 DB 쓰기")).isLessThan(prompt.get().indexOf("## 이미 적용된 SQL"));
        // 조회는 회차 worktree를 만든 뒤 claude 전
        InOrder order = inOrder(worktrees, http, claude);
        order.verify(worktrees).createOnBranch(any(), anyString(), eq(BRANCH), eq(7L));
        order.verify(http).dbChanges(7L);
        order.verify(claude).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
    }

    @Test
    void 수정회차도_자동마이그레이션_절() throws Exception {
        claimsFixRound(TaskType.BUG_FIX, null, db(true, false));
        AtomicReference<String> prompt = claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(prompt.get()).contains("## DB 변경 (자동 마이그레이션 레포)").contains("옛 컬럼은 두고 새 컬럼을 추가해")
                .doesNotContain("## 이미 적용된 SQL");
        verify(http, never()).dbChanges(anyLong());
    }

    @Test
    void 수정회차_사전조회_실패는_회차_표지를_달고_claude없이_구현실패() throws Exception {
        claimsFixRound(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(7L)).thenThrow(new IllegalStateException("DB 변경 기록 응답 본문이 없다"));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("DB 변경 기록 조회 실패: ");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.fixRequestId()).isEqualTo(9L);
        assertThat(r.fixAttempt()).isEqualTo(1);
        verify(claude, never()).exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any());
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 수정회차는_DB메모를_구현_로그로_보낸다() throws Exception {
        claimsFixRound(TaskType.BUG_FIX, writableGrant(), db(false, false));
        claudeSucceeds(dir -> {
            write(dir.resolve(".netis/review-notes.md"), "R");
            write(dir.resolve(".netis/db-notes.md"), "D");
        });

        loop().pollAndProcess();

        assertThat(reported().implementationLog()).contains("## 리뷰 메모\nR\n\nD\n\n").contains("done");
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- 순수 함수 ----

    @Test
    void dbSpecOf는_없으면_기본_경로와_쓰기없음() {
        WorkerTaskResponse none = claimsRaw(null);

        assertThat(WorkerMainLoop.dbSpecOf(none)).isEqualTo(new DbWriteSpec(SCRIPT, false, false));
        // 구현 블록은 있는데 재료가 없는 구버전 API
        assertThat(WorkerMainLoop.dbSpecOf(claimsRaw(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, "BUG_FIX"))))
                .isEqualTo(new DbWriteSpec(SCRIPT, false, false));
        // 있는 재료는 그대로
        assertThat(WorkerMainLoop.dbSpecOf(claimsRaw(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, null,
                new DbWriteSpec("sql/dev/task-7.sql", true, true)))))
                .isEqualTo(new DbWriteSpec("sql/dev/task-7.sql", true, true));
        // 경로가 비어 온 재료는 기본 경로로 — 플래그는 지킨다
        assertThat(WorkerMainLoop.dbSpecOf(claimsRaw(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null, null,
                new DbWriteSpec(" ", true, false)))))
                .isEqualTo(new DbWriteSpec(SCRIPT, true, false));
    }

    @Test
    void needsDbLog는_자동마이그레이션이_아니고_쓰기연결이나_이전쓰기가_있을때() {
        assertThat(WorkerMainLoop.needsDbLog(claimsRaw(null))).isFalse();
        assertThat(WorkerMainLoop.needsDbLog(withDb(writableGrant(), db(false, false)))).isTrue();
        assertThat(WorkerMainLoop.needsDbLog(withDb(readOnlyGrant(), db(false, true)))).isTrue();
        assertThat(WorkerMainLoop.needsDbLog(withDb(null, db(false, true)))).isTrue();
        assertThat(WorkerMainLoop.needsDbLog(withDb(readOnlyGrant(), db(false, false)))).isFalse();
        assertThat(WorkerMainLoop.needsDbLog(withDb(null, db(false, false)))).isFalse();
        // 자동 마이그레이션 레포는 기록이 없다
        assertThat(WorkerMainLoop.needsDbLog(withDb(writableGrant(), db(true, true)))).isFalse();
        assertThat(WorkerMainLoop.needsDbLog(withDb(null, db(true, false)))).isFalse();
    }

    @Test
    void 기준_id는_기록_전체의_최대_id_없으면_0() {
        assertThat(WorkerMainLoop.baselineIdOf(List.of())).isZero();
        assertThat(WorkerMainLoop.baselineIdOf(null)).isZero();
        // REJECTED·FAILED도 센다 — 이번 시도의 새 기록은 이 id보다 크다
        assertThat(WorkerMainLoop.baselineIdOf(List.of(change(12, "a", "EXECUTED"), change(31, "b", "REJECTED"),
                change(20, "c", "FAILED")))).isEqualTo(31L);
    }

    @Test
    void 메모_잇기는_빈_줄_하나로_리뷰_메모_뒤에() {
        assertThat(WorkerMainLoop.joinNotes(Optional.of("R"), Optional.of("D"))).contains("R\n\nD");
        assertThat(WorkerMainLoop.joinNotes(Optional.empty(), Optional.of("D"))).contains("D");
        assertThat(WorkerMainLoop.joinNotes(Optional.of("R"), Optional.empty())).contains("R");
        assertThat(WorkerMainLoop.joinNotes(Optional.empty(), Optional.empty())).isEmpty();
    }

    private WorkerTaskResponse withDb(DbMcpGrant grant, DbWriteSpec db) {
        WorkerTaskResponse t = WorkerTaskResponse.forImplementation(task(null),
                TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, null, null, null, db);
        return grant == null ? t : t.withDbMcp(grant);
    }

    private static WorkerTaskResponse claimsRaw(WorkerTaskResponse.ImplementationSpec spec) {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), null, null, null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, spec);
    }
}
