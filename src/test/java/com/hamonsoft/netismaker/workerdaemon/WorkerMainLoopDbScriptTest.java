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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 워커의 PR SQL 스크립트 파일 — 커밋 직전 사후 조회·누적 파일 쓰기·강제 추가, 성공 판정(DB 작업은 누적 파일만으로 성공, 다른 타입은 이번 시도의
 * 코드나 쓰기), wip 경로의 같은 파일, 세션이 쓴 파일(무시 대상 포함) 강제 추가, PR 본문 "DB 변경" 절 (스펙 2026-11-03 §6.5·§6.6).
 * 하네스는 WorkerMainLoopDbPromptTest를 따른다.
 */
class WorkerMainLoopDbScriptTest {

    private static final long ID = 42L;
    private static final String SCRIPT = "db/symphony/task-42.sql";
    private static final String MANUAL = "db/symphony/task-42-manual.sql";
    private static final String BRANCH = "netismaker/task-42-add-login";
    private static final String TIP = "abc1234" + "0".repeat(33);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-04T01:00:00Z");

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
        when(worktrees.create(any(), anyString(), eq("main"), eq(ID), anyString(), anyInt()))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        when(repos.fetchOnly(any(), eq(BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        when(worktrees.createOnBranch(any(), anyString(), eq(BRANCH), eq(ID)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(gitOps.headSha(any())).thenReturn(TIP);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
        // 기본은 이번 시도의 코드 변경 없음 — 시험마다 필요한 쪽을 켠다
        when(gitOps.hasChanges(any())).thenReturn(false);
        when(gitOps.hasCommitsSince(any(), anyString())).thenReturn(false);
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
                AT, "EXECUTED".equals(classification) ? AT : null, null);
    }

    private static Task task(TaskType type) {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", ID);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setTaskType(type);
        return t;
    }

    private void claims(TaskType type, DbMcpGrant grant, DbWriteSpec db) {
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(task(type),
                TaskAnalysis.create(ID, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: A", null, null, db);
        if (grant != null) task = task.withDbMcp(grant);
        when(http.nextTask()).thenReturn(Optional.of(task));
    }

    private void claimsFixRound(TaskType type, DbMcpGrant grant, DbWriteSpec db) {
        var fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, "저장 뒤 목록이 그대로", List.of(), BRANCH, TIP);
        WorkerTaskResponse task = WorkerTaskResponse.forImplementation(task(type),
                TaskAnalysis.create(ID, "# 설계 문서", "[]", null, 1000L), null, "### 작업 1: A", fix,
                ImplementationMode.DIRECT, db);
        if (grant != null) task = task.withDbMcp(grant);
        when(http.nextTask()).thenReturn(Optional.of(task));
    }

    private void claudeSucceeds(Consumer<Path> sideEffect) throws Exception {
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    sideEffect.accept(wt);
                    return new ClaudeExecAdapter.ExecResult(0, "done", 10, null);
                });
    }

    private void claudeTimesOut() throws Exception {
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after PT45M"));
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** forceAdd가 불릴 때 그 파일 내용을 적어 둔다 — 쓰기가 강제 추가보다 먼저인지 본다. */
    private List<String> recordForceAdds() throws Exception {
        List<String> seen = new ArrayList<>();
        doAnswer(inv -> {
            String rel = inv.getArgument(1);
            Path f = ((File) inv.getArgument(0)).toPath().resolve(rel);
            seen.add(rel + "=" + (Files.exists(f) ? Files.readString(f) : "(없음)"));
            return null;
        }).when(gitOps).forceAdd(any(), anyString());
        return seen;
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(ID), cap.capture());
        return cap.getValue();
    }

    private String prBody() throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());
        return body.getValue();
    }

    // ---- 성공 경로 ----

    @Test
    void 커밋전에_스크립트를_쓰고_강제추가() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of(),   // 사전 조회 — 기록 없음(기준 id 0)
                List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")));   // 사후 조회 — 이번 시도의 쓰기
        claudeSucceeds(dir -> { });
        List<String> added = recordForceAdds();

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).commitAndPush(eq(wt.toFile()), any(), eq(BRANCH), anyString(), eq("base123"));
        order.verify(gitOps).createDraftPr(any(), any(), eq("main"), eq(BRANCH), eq("db: Add login"), anyString());
        assertThat(added).singleElement().asString()
                .startsWith(SCRIPT + "=-- Symphony 작업 #42 DB 변경 기록 (워커가 생성 — 직접 고치지 마세요)\n")
                .contains("CREATE TABLE t_new (id int);");
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        // 사전 조회 + 커밋 직전 사후 조회
        verify(http, times(2)).dbChanges(ID);
        assertThat(prBody()).contains("## DB 변경\n- 개발 DB에 실행한 SQL: 1건\n")
                .contains("- 스크립트 파일: `" + SCRIPT + "`");
    }

    @Test
    void DB작업은_누적기록_파일만으로_성공() throws Exception {
        // 1회차에 SQL을 다 적용하고 PR 생성에서 실패한 경우 — 재시도는 base에서 시작해 할 일이 파일뿐이다
        claims(TaskType.DB_WORK, readOnlyGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(gitOps).hasChanges(wt.toFile());
        verify(gitOps).hasCommitsSince(wt.toFile(), "base123");
        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), eq("base123"));
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(Files.readString(wt.resolve(SCRIPT))).contains("CREATE TABLE t_new (id int);");
    }

    @Test
    void 다른타입_이번시도_쓰기도_코드도_없으면_변경없음() throws Exception {
        claims(TaskType.BUG_FIX, readOnlyGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("commit/push 실패: nothing to commit — 구현 시도 후 파일 변경이 없음");
        assertThat(wt.resolve(SCRIPT)).doesNotExist();
        verify(gitOps, never()).forceAdd(any(), anyString());
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    /** 이번 시도에 남은 기록이 거부뿐이면 쓰기가 아니다(판정 F13) — 다른 타입은 변경 없음. */
    @Test
    void 다른타입_이번시도_거부뿐이면_변경없음() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(ID)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")),
                List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"), change(13, "DROP TABLE t_old", "REJECTED")));
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(reported().failureReason()).contains("nothing to commit");
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 다른타입_이번시도_쓰기가_있으면_성공() throws Exception {
        claims(TaskType.BUG_FIX, writableGrant(), db(false, true));
        when(http.dbChanges(ID)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")),
                List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"),
                        change(15, "ALTER TABLE t_new ADD COLUMN v int", "EXECUTED")));
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(Files.readString(wt.resolve(SCRIPT)))
                .contains("CREATE TABLE t_new (id int);").contains("ALTER TABLE t_new ADD COLUMN v int;");
        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
    }

    @Test
    void 다른타입_코드가_바뀌면_쓰기가_없어도_성공하고_누적파일도_싣는다() throws Exception {
        claims(TaskType.NEW_FEATURE, writableGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        when(gitOps.hasCommitsSince(any(), anyString())).thenReturn(true);   // 에이전트 커밋
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(wt.resolve(SCRIPT)).exists();
        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
    }

    @Test
    void 기록이_없으면_세션이_쓴_파일을_덮어쓰지_않는다() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of(), List.of(change(13, "DROP TABLE t_old", "REJECTED")));
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "-- 세션이 적은 SQL\nUPDATE t SET v = 1;\n"));
        when(gitOps.hasChanges(any())).thenReturn(true);

        loop().pollAndProcess();

        assertThat(Files.readString(wt.resolve(SCRIPT))).isEqualTo("-- 세션이 적은 SQL\nUPDATE t SET v = 1;\n");
        verify(gitOps, times(1)).forceAdd(wt.toFile(), SCRIPT);
        verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), any());
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    /**
     * Review Focus 3 — 대상 레포가 db/·*.sql을 무시해 세션 파일이 강제 추가 전에는 변경으로 보이지 않는다. 강제 추가가 "변경 있음" 판정보다
     * 먼저라서 커밋할 것이 생긴다(실제 git의 무시 규칙은 GitOpsServicePushTest가 본다).
     */
    @Test
    void 세션이_쓴_무시대상_스크립트도_강제추가() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of(), List.of());
        claudeSucceeds(dir -> write(dir.resolve(SCRIPT), "UPDATE t SET v = 1;\n"));
        AtomicBoolean forced = new AtomicBoolean();
        doAnswer(inv -> { forced.set(true); return null; }).when(gitOps).forceAdd(any(), anyString());
        when(gitOps.hasChanges(any())).thenAnswer(inv -> forced.get());   // 무시된 파일은 강제 추가 뒤에만 보인다

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).hasChanges(wt.toFile());
        order.verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), any());
        order.verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 이전기록과_세션_manual_파일이_함께_커밋된다() throws Exception {
        // 쓰기 도구 없음 + 이전 기록 있음 — 세션은 -manual에 적고(Task 14), 워커는 누적 기록을 원래 경로에 쓴다
        claims(TaskType.DB_WORK, readOnlyGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        claudeSucceeds(dir -> write(dir.resolve(MANUAL), "ALTER TABLE t_new ADD COLUMN v int;\n"));
        when(gitOps.hasChanges(any())).thenReturn(true);

        loop().pollAndProcess();

        assertThat(Files.readString(wt.resolve(MANUAL))).isEqualTo("ALTER TABLE t_new ADD COLUMN v int;\n");
        assertThat(Files.readString(wt.resolve(SCRIPT))).contains("CREATE TABLE t_new (id int);")
                .doesNotContain("ADD COLUMN v");
        verify(gitOps).forceAdd(wt.toFile(), MANUAL);
        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        assertThat(prBody()).contains("- 스크립트 파일: `" + SCRIPT + "`")
                .contains("- 개발 DB 미적용 — 수동 적용 필요: `" + MANUAL + "`");
    }

    @Test
    void 성공경로_사후조회_실패는_wip보존_구현실패() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of())
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        claudeSucceeds(dir -> write(dir.resolve("src/App.java"), "class App {}"));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("DB 변경 기록 조회 실패 — SQL 스크립트 파일 미반영").contains("503")
                .contains("브랜치 `" + BRANCH + "`에 올려 둠");
        assertThat(r.headSha()).isEqualTo("wip999");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq("base123"), wip.capture());
        assertThat(wip.getValue()).isEqualTo("wip: 구현 중단(DB 변경 기록 조회 실패) — task #42");
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(http, times(2)).dbChanges(ID);   // wip 경로에서 다시 조회하지 않는다
        assertThat(wt.resolve(SCRIPT)).doesNotExist();
    }

    /** 사후 조회 본문이 없으면 "기록 없음"이 아니라 조회 실패다 — 실행한 SQL이 파일에서 빠진 PR을 만들지 않는다. */
    @Test
    void 사후조회_본문이_없으면_조회실패와_같다() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of(), null);
        claudeSucceeds(dir -> { });
        when(gitOps.hasChanges(any())).thenReturn(true);
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        assertThat(reported().failureReason())
                .startsWith("DB 변경 기록 조회 실패 — SQL 스크립트 파일 미반영: DB 변경 기록 응답 본문이 없다");
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 자동마이그레이션_레포는_조회도_파일도_없다() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(true, true));
        claudeSucceeds(dir -> write(dir.resolve("src/main/resources/db/migration/V2__t.sql"), "CREATE TABLE t (id int);"));

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        assertThat(wt.resolve(SCRIPT)).doesNotExist();
        verify(gitOps, never()).forceAdd(any(), anyString());
        verify(gitOps, never()).hasCommitsSince(any(), anyString());
        verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), eq("base123"));
        assertThat(prBody()).doesNotContain("## DB 변경");
    }

    /** DB 기록이 필요 없는 구현은 변경 판정을 commitAndPush에 맡긴다 — git 호출·사유가 이 기능 전과 같다. */
    @Test
    void 기록이_필요없는_구현은_변경판정을_그대로_commitAndPush에_맡긴다() throws Exception {
        claims(TaskType.BUG_FIX, readOnlyGrant(), db(false, false));
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        verify(gitOps, never()).hasChanges(any());
        verify(gitOps, never()).hasCommitsSince(any(), anyString());
        verify(gitOps).commitAndPush(any(), any(), anyString(), anyString(), eq("base123"));
        assertThat(prBody()).doesNotContain("## DB 변경");
    }

    // ---- 실패(wip) 경로 ----

    @Test
    void claude실패_wip에도_스크립트() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of(), List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")));
        claudeTimesOut();
        List<String> added = recordForceAdds();
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq("base123"), anyString());
        // 쓰기가 강제 추가보다 먼저
        assertThat(added).singleElement().asString().startsWith(SCRIPT + "=-- Symphony 작업 #42 DB 변경 기록")
                .contains("CREATE TABLE t_new (id int);");
        WorkerResultRequest r = reported();
        assertThat(r.headSha()).isEqualTo("wip999");
        assertThat(r.failureReason()).doesNotContain("미반영");
    }

    @Test
    void wip경로는_세션_manual_파일도_강제추가() throws Exception {
        claims(TaskType.DB_WORK, readOnlyGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    write(wt.resolve(MANUAL), "UPDATE t SET v = 1;\n");
                    return new ClaudeExecAdapter.ExecResult(1, "boom", 10, null);
                });
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        verify(gitOps).forceAdd(wt.toFile(), MANUAL);
        assertThat(Files.readString(wt.resolve(MANUAL))).isEqualTo("UPDATE t SET v = 1;\n");
        verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void wip경로_조회실패는_사유끝에_SQL_스크립트_파일_미반영() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(false, false));
        when(http.dbChanges(ID)).thenReturn(List.of())
                .thenThrow(new IllegalStateException("DB 변경 기록 응답 본문이 없다"));
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "boom", 10, null));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip999", null));

        loop().pollAndProcess();

        // wip은 그대로 올린다
        verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq("base123"), anyString());
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.headSha()).isEqualTo("wip999");
        assertThat(r.failureReason()).startsWith("claude exit=1").endsWith(" — SQL 스크립트 파일 미반영");
        assertThat(wt.resolve(SCRIPT)).doesNotExist();
    }

    @Test
    void wip경로도_자동마이그레이션_레포는_조회하지_않는다() throws Exception {
        claims(TaskType.DB_WORK, writableGrant(), db(true, true));
        claudeTimesOut();
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(0, null, null));

        loop().pollAndProcess();

        verify(http, never()).dbChanges(anyLong());
        assertThat(reported().failureReason()).doesNotContain("미반영");
    }

    // ---- 수정 회차 ----

    @Test
    void 수정회차도_누적파일로_다시쓴다() throws Exception {
        claimsFixRound(TaskType.DB_WORK, writableGrant(), db(false, true));
        DbChangeView earlier = change(12, "CREATE TABLE t_new (id int)", "EXECUTED");
        DbChangeView now = new DbChangeView(20L, 5L, "개발 (PostgreSQL)", 1, 9L, "ALTER TABLE t_old DROP COLUMN c",
                "DEFERRED", null, null, null, AT, null, null);
        when(http.dbChanges(ID)).thenReturn(List.of(earlier), List.of(earlier, now));
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        // 회차의 기준 커밋은 브랜치 끝(tip)
        verify(gitOps).hasCommitsSince(wt.toFile(), TIP);
        InOrder order = inOrder(gitOps);
        order.verify(gitOps).forceAdd(wt.toFile(), SCRIPT);
        order.verify(gitOps).commitAndPush(eq(wt.toFile()), any(), eq(BRANCH), anyString(), eq(TIP));
        assertThat(Files.readString(wt.resolve(SCRIPT))).contains("CREATE TABLE t_new (id int);")
                .contains("-- 병합 뒤 실행(승인 대기)").contains("수정 요청 #9").contains("ALTER TABLE t_old DROP COLUMN c;");
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 수정회차_다른타입_쓰기도_코드도_없으면_변경없음() throws Exception {
        claimsFixRound(TaskType.BUG_FIX, writableGrant(), db(false, true));
        List<DbChangeView> log = List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED"));
        when(http.dbChanges(ID)).thenReturn(log, log);
        claudeSucceeds(dir -> { });

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("수정 2회차: 변경 없음 — Claude가 고칠 곳을 찾지 못함");
        assertThat(wt.resolve(SCRIPT)).doesNotExist();
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void 수정회차_사후조회_실패도_wip보존_구현실패() throws Exception {
        claimsFixRound(TaskType.DB_WORK, writableGrant(), db(false, true));
        when(http.dbChanges(ID)).thenReturn(List.of(change(12, "CREATE TABLE t_new (id int)", "EXECUTED")))
                .thenThrow(new IllegalStateException("DB 변경 기록 응답 본문이 없다"));
        claudeSucceeds(dir -> { });
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(0, null, null));

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("DB 변경 기록 조회 실패 — SQL 스크립트 파일 미반영");
        assertThat(r.headSha()).isEqualTo(TIP);   // wip을 못 올렸으면 원격 끝 그대로
        assertThat(r.fixRequestId()).isEqualTo(9L);
        verify(gitOps).preservePartialWork(eq(wt.toFile()), any(), eq(BRANCH), eq(TIP),
                eq("wip: 수정 2회차 중단(DB 변경 기록 조회 실패) — task #42"));
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    // ---- PR 본문 "DB 변경" 절 ----

    @Test
    void PR본문_DB변경절과_예산() {
        List<DbChangeView> log = List.of(
                change(12, "CREATE TABLE t_new (id int)", "EXECUTED"),
                change(13, "ALTER TABLE t_old DROP COLUMN c", "DEFERRED"),
                change(14, "INSERT INTO t_new VALUES (1)", "EXECUTED"),
                change(15, "DROP TABLE t_other", "REJECTED"),
                change(16, "DISMISSED 문장", "DISMISSED"));

        String s = WorkerMainLoop.dbChangeSection(log, SCRIPT, SCRIPT, true, false, TaskType.DB_WORK);

        assertThat(s).contains("## DB 변경\n- 개발 DB에 실행한 SQL: 2건\n- 병합 뒤 실행(승인 대기): 1건\n")
                .contains("- 스크립트 파일: `" + SCRIPT + "`")
                // 승인 대기 SQL 목록(판정 F14) — 거부·실행 안 함은 없다
                .contains("ALTER TABLE t_old DROP COLUMN c;")
                .doesNotContain("DROP TABLE t_other").doesNotContain("DISMISSED 문장")
                .doesNotContain("개발 DB 미적용");
        assertThat(s.indexOf("- 병합 뒤 실행(승인 대기): 1건")).isLessThan(s.indexOf("ALTER TABLE t_old DROP COLUMN c;"));

        // 예산 — 승인 대기 SQL이 50 000자여도 본문은 60 000자 이하, 머리·절 제목은 남는다
        String huge = "UPDATE t SET v = '" + "가".repeat(50_000) + "'";
        String big = WorkerMainLoop.dbChangeSection(List.of(change(13, huge, "DEFERRED")), SCRIPT, SCRIPT, true, false,
                TaskType.DB_WORK);
        WorkerTaskResponse t = WorkerTaskResponse.forImplementation(task(TaskType.DB_WORK),
                TaskAnalysis.create(ID, "분".repeat(30_000), "[]", null, 1000L), null, "### 작업 1: A", null, null,
                db(false, false));
        String body = WorkerMainLoop.renderPrBody(t, 12_000, "abcdef1234567", null, "메".repeat(20_000), List.of(), big);
        assertThat(body.length()).isLessThanOrEqualTo(WorkerMainLoop.PR_BODY_MAX_CHARS);
        assertThat(body).contains("## DB 변경\n- 개발 DB에 실행한 SQL: 0건\n- 병합 뒤 실행(승인 대기): 1건\n")
                .contains("- 스크립트 파일: `" + SCRIPT + "`").contains("UPDATE t SET v = '가가")
                .contains("## 사전 분석\n").contains("## 리뷰 메모\n").endsWith("Ready for review로 전환하세요.\n");
        // DB 절이 없으면 본문은 예전과 같다
        assertThat(WorkerMainLoop.renderPrBody(t, 12_000, "abcdef1234567", null, "R", List.of(), ""))
                .isEqualTo(WorkerMainLoop.renderPrBody(t, 12_000, "abcdef1234567", null, "R", List.of()));
    }

    @Test
    void DB작업_쓰기도구없으면_개발DB_미적용_문구() {
        // 기록 없음 — 세션이 원래 경로에 적었다
        assertThat(WorkerMainLoop.dbChangeSection(List.of(), SCRIPT, SCRIPT, false, false, TaskType.DB_WORK))
                .isEqualTo("## DB 변경\n- 개발 DB 미적용 — 수동 적용 필요: `" + SCRIPT + "`\n\n");
        // 쓰기 도구가 있으면 그 줄이 없다 — 기록도 없으면 절 자체가 없다
        assertThat(WorkerMainLoop.dbChangeSection(List.of(), SCRIPT, SCRIPT, true, false, TaskType.DB_WORK)).isEmpty();
        // 다른 타입·타입 없음은 기록이 없으면 절이 없다
        assertThat(WorkerMainLoop.dbChangeSection(List.of(), SCRIPT, SCRIPT, false, false, TaskType.BUG_FIX)).isEmpty();
        assertThat(WorkerMainLoop.dbChangeSection(List.of(), SCRIPT, SCRIPT, false, false, null)).isEmpty();
        // 자동 마이그레이션 레포는 기록·파일이 없고 개발 DB에는 시험 컨테이너가 적용한다 — 절이 없다
        assertThat(WorkerMainLoop.dbChangeSection(List.of(), SCRIPT, SCRIPT, false, true, TaskType.DB_WORK)).isEmpty();
    }
}
