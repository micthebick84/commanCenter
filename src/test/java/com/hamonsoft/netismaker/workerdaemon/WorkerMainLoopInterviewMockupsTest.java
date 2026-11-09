package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.InterviewMockupFile;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 구현·수정 회차 흐름의 인터뷰 목업 — 파일 배치(claude 전)·프롬프트 절·계획 파일 머리·리뷰어·PR 본문·정리 (설계 2026-11-03 §7·§10).
 * 하네스는 WorkerMainLoopRefReposTest를 따른다 — claudeEnds()가 claude 시점의 계획 파일과 목업 파일을 읽는다.
 */
class WorkerMainLoopInterviewMockupsTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String BRANCH = "netismaker/task-7-add-login";
    private static final String FIX_BRANCH = "netismaker/task-7-add-login";
    private static final String PLAN = "# 계획\n### Task 1: 목록 화면 (목업 2 기준)\n### Task 2: 상세 화면\n";
    private static final String HTML_A = "<!doctype html><html><body><h1>목록 A안</h1></body></html>";
    private static final String HTML_B = "<!doctype html><html><body><p>상세</p></body></html>";
    private static final InterviewMockupFile M3 = new InterviewMockupFile(3, "list-filter-a", 2, "A안", HTML_A);
    private static final InterviewMockupFile M5 = new InterviewMockupFile(5, "detail", 1, "상세", HTML_B);
    private static final String EXCLUDED_LINE = "계획에서 가리켰지만 전달되지 않은 목업: 목업 2 — 찾지 말고 계획 글만 따르라.";

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private Path wt;
    private Path pluginDir;

    private WorkerTaskResponse task;
    private WorktreeService.RefWorktree rw12;

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
        pluginDir = tmp.resolve("superpowers");
        Files.createDirectories(pluginDir.resolve("skills/subagent-driven-development"));
        Files.writeString(pluginDir.resolve(SuperpowersPlugin.SDD_SKILL), "# SDD");

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), BRANCH));
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(worktrees.refChangeWarning(any())).thenReturn(Optional.empty());
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
        // 수정 회차 하네스
        when(repos.fetchOnly(any(), eq(FIX_BRANCH))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "cachehead"));
        when(worktrees.createOnBranch(any(), anyString(), eq(FIX_BRANCH), eq(7L)))
                .thenReturn(new WorktreeService.CreatedWorktree(wt.toFile(), FIX_BRANCH));
        when(gitOps.headSha(any())).thenReturn("abc1234" + "0".repeat(33));
        rw12 = new WorktreeService.RefWorktree(12L, "옛앱", "release/6.5", SHA_A, "_gitlab/apps+old-app",
                tmp.resolve("refs/12").toFile(), List.of());
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(pluginDir.toString()), mock(ClaudeAuthProbe.class));
    }

    // ---- 재료 ----

    /** claim 재료 — 기본은 목업 없음·참조 없음·디자인 없음·타입 없음. */
    private static final class Claim {
        ImplementationMode mode = ImplementationMode.DIRECT;
        String plan = PLAN;
        List<InterviewMockupFile> mockups = List.of();
        List<Integer> excluded = List.of();
        boolean withRef;
        String design;
        TaskType type;
        DbMcpGrant grant;
        DbWriteSpec db;
        WorkerTaskResponse.FixRoundSpec fix;
        String analysis = "# 설계 문서";
    }

    private Claim claim(ImplementationMode mode) {
        Claim c = new Claim();
        c.mode = mode;
        return c;
    }

    private WorkerTaskResponse claims(Claim c) {
        Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(c.mode);
        t.setImplementationAttempt(1);
        t.setImplementationTimeoutMinutes(80);
        t.setTaskType(c.type);
        if (c.withRef) {
            t.setRefRepos(List.of(new RefRepo(12L, "옛앱", "apps/old-app", "https://gitlab.example/apps/old-app.git",
                    "release/6.5", SHA_A, "2026-10-24T01:00:00Z", null)));
        }
        TaskDesign d = c.design == null ? null : TaskDesign.create(7L, c.design, null, null, null, null, 1000L);
        task = WorkerTaskResponse.forImplementation(t, TaskAnalysis.create(7L, c.analysis, "[]", null, 1000L),
                        d, c.plan, c.fix, c.fix == null ? null : c.mode, c.db)
                .withInterviewMockups(c.mockups, c.excluded);
        if (c.grant != null) task = task.withDbMcp(c.grant);
        when(http.nextTask()).thenReturn(Optional.of(task));
        if (c.withRef) {
            try {
                when(worktrees.createRefWorktree(7L, task.refRepos().get(0))).thenReturn(rw12);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return task;
    }

    /** claude 시점의 프롬프트·계획 파일·목업 파일(경로 → 내용). */
    private record Exec(String prompt, ClaudeExecAdapter.ExecOptions options, String planFile,
                        Map<String, String> mockupFiles) {}

    private AtomicReference<Exec> claudeEnds(int exitCode) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Path plan = wt.resolve(ImplementationScratch.planPath(7L));
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(7),
                            Files.exists(plan) ? Files.readString(plan) : null, mockupFiles()));
                    return new ClaudeExecAdapter.ExecResult(exitCode, "done", 10, null);
                });
        return seen;
    }

    /** worktree의 .netis/mockups 아래 파일(없으면 빈 맵). */
    private Map<String, String> mockupFiles() throws IOException {
        Path dir = wt.resolve(ImplementationScratch.MOCKUPS_DIR);
        Map<String, String> files = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return files;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                files.put(ImplementationScratch.MOCKUPS_DIR + "/" + p.getFileName(), Files.readString(p));
            }
        }
        return files;
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

    /** 목업 도입 전 구현 프롬프트(WorkerMainLoopRefReposTest :463-469 가드와 같은 조립) — 참조·DB 없음. */
    private String promptWithoutMockups(ImplementationMode mode) {
        boolean designWritten = mode == ImplementationMode.SDD;
        return templatePart(mode, designWritten) + DbMcpWorkerSupport.promptSection(task.dbMcp());
    }

    private String templatePart(ImplementationMode mode, boolean designWritten) {
        return WorkerMainLoop.fillImplementationPrompt(WorkerMainLoop.defaultImplementationPrompt(), task, "base123",
                BRANCH, ImplementationPrompts.planSection(task, mode, "base123", designWritten),
                ImplementationPrompts.commitRule(mode));
    }

    // ---- 바이트 가드 (설계 §10 반목표, RF4) ----

    @ParameterizedTest
    @EnumSource(ImplementationMode.class)
    void 목업없는_인터뷰의_구현은_프롬프트와_계획파일이_바이트_그대로(ImplementationMode mode) throws Exception {
        claims(claim(mode));   // 계획 본문에 "목업 2 기준"이 있어도 두 목록이 비었다
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(exec.get().prompt()).isEqualTo(promptWithoutMockups(mode))
                .doesNotContain("인터뷰 목업").doesNotContain("전달되지 않은")
                .doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE);
        switch (mode) {
            case DIRECT -> assertThat(exec.get().planFile()).isNull();
            case REVIEW -> assertThat(exec.get().planFile()).isEqualTo(RefRepoPrompts.planFileHeader(List.of()) + PLAN);
            case SDD -> assertThat(exec.get().planFile())
                    .isEqualTo(RefRepoPrompts.planFileHeader(List.of()) + ImplementationScratch.normalizeTaskHeadings(PLAN));
        }
        assertThat(exec.get().mockupFiles()).isEmpty();
        assertThat(wt.resolve(ImplementationScratch.MOCKUPS_DIR)).doesNotExist();
        assertThat(prBody()).doesNotContain("## 구현 기준 목업");
        assertThat(prBody()).isEqualTo(WorkerMainLoop.renderPrBody(task, 10, "head456",
                ImplementationPrompts.modeLine(mode, mode, 1, null), null, List.of(), ""));
    }

    @ParameterizedTest
    @EnumSource(ImplementationMode.class)
    void 제외번호만_있으면_전달되지_않은_줄만_더해진다(ImplementationMode mode) throws Exception {
        Claim c = claim(mode);
        c.excluded = List.of(2);   // 관리자가 계획의 목업 2를 해제했다
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
        boolean designWritten = mode == ImplementationMode.SDD;
        // 템플릿 바로 뒤에 그 줄 하나만 — SDD 줄·리뷰어 요구·나머지 절은 그대로
        assertThat(exec.get().prompt()).isEqualTo(templatePart(mode, designWritten)
                + "\n" + EXCLUDED_LINE + "\n"
                + DbMcpWorkerSupport.promptSection(task.dbMcp()));
        assertThat(exec.get().prompt()).doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE)
                .doesNotContain("interview mockups").doesNotContain("## 인터뷰 목업");
        switch (mode) {
            case DIRECT -> assertThat(exec.get().planFile()).isNull();
            case REVIEW -> assertThat(exec.get().planFile())
                    .isEqualTo(RefRepoPrompts.planFileHeader(List.of()) + EXCLUDED_LINE + "\n\n" + PLAN);
            case SDD -> assertThat(exec.get().planFile()).isEqualTo(RefRepoPrompts.planFileHeader(List.of())
                    + EXCLUDED_LINE + "\n\n" + ImplementationScratch.normalizeTaskHeadings(PLAN));
        }
        if (exec.get().planFile() != null) {
            assertThat(ImplementationScratch.countTaskHeadings(exec.get().planFile())).isEqualTo(2);
        }
        assertThat(exec.get().mockupFiles()).isEmpty();
        assertThat(wt.resolve(ImplementationScratch.MOCKUPS_DIR)).doesNotExist();
        assertThat(prBody()).doesNotContain("## 구현 기준 목업")
                .isEqualTo(WorkerMainLoop.renderPrBody(task, 10, "head456",
                        ImplementationPrompts.modeLine(mode, mode, 1, null), null, List.of(), ""));
    }

    // ---- 구현 ----

    @Test
    void 구현은_claude_전에_파일을_두고_절을_붙인다() throws Exception {
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(M3, M5);
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);
        // exclude 뒤에 둔다 — .netis/는 커밋에서 빠지는 이름
        AtomicBoolean mockupsAtExclude = new AtomicBoolean(true);
        when(worktrees.ensureWorkerExcludes(any())).thenAnswer(inv -> {
            mockupsAtExclude.set(Files.exists(wt.resolve(ImplementationScratch.MOCKUPS_DIR)));
            return true;
        });

        loop().pollAndProcess();

        assertThat(mockupsAtExclude.get()).isFalse();
        assertThat(exec.get().mockupFiles()).containsExactly(
                Map.entry(".netis/mockups/3-list-filter-a-v2.html", HTML_A),
                Map.entry(".netis/mockups/5-detail-v1.html", HTML_B));
        String section = InterviewMockupPrompts.implementationSection(List.of(M3, M5), List.of(), false);
        assertThat(exec.get().prompt()).contains("## 인터뷰 목업 (관리자가 확정한 화면")
                .isEqualTo(templatePart(ImplementationMode.DIRECT, false) + section
                        + DbMcpWorkerSupport.promptSection(task.dbMcp()));
        assertThat(section).contains("- 목업 3 · A안 (v2): `.netis/mockups/3-list-filter-a-v2.html`\n")
                .contains("- 목업 5 · 상세: `.netis/mockups/5-detail-v1.html`\n");
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 절_순서는_템플릿_목업_DB_참조() throws Exception {
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(M3);
        c.type = TaskType.BUG_FIX;
        c.grant = new DbMcpGrant("tok", List.of(
                new DbMcpGrant.Server("db-12", "/mcp/db/12", "개발 (PostgreSQL)", "POSTGRESQL", false)), List.of());
        c.db = new DbWriteSpec("db/symphony/task-7.sql", true, false);
        c.withRef = true;
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        String p = exec.get().prompt();
        int type = p.indexOf("## 작업 타입: 버그 픽스");
        int env = p.indexOf("## 배포 환경변수 목록");
        int mockups = p.indexOf("## 인터뷰 목업");
        int db = p.indexOf("## 데이터베이스 조회 (읽기 전용)");
        int migration = p.indexOf("## DB 변경 (자동 마이그레이션 레포)");
        int refs = p.indexOf("## 참조 repo (읽기 전용)");
        assertThat(type).isPositive();
        assertThat(type).isLessThan(env);
        assertThat(env).isLessThan(mockups);
        assertThat(mockups).isLessThan(db);
        assertThat(db).isLessThan(migration);
        assertThat(migration).isLessThan(refs);
        // 목업 절은 템플릿(배포 env 지시로 끝난다) 바로 뒤, DB 절 바로 앞
        assertThat(p).contains(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION
                + InterviewMockupPrompts.implementationSection(List.of(M3), List.of(), false)
                + DbMcpWorkerSupport.promptSection(task.dbMcp(), true));
        assertThat(p).endsWith(RefRepoPrompts.promptSection(List.of(rw12)));
    }

    @Test
    void SDD는_계획파일_머리와_SDD줄() throws Exception {
        Claim c = claim(ImplementationMode.SDD);
        c.mockups = List.of(M3);
        c.excluded = List.of(2);
        c.withRef = true;
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(exec.get().options().extraArgs()).containsExactly("--plugin-dir", pluginDir.toString());
        String planFile = exec.get().planFile();
        String header = InterviewMockupPrompts.planFileHeader(List.of(M3), List.of(2), false);
        // 참조 블록 다음에 목업 블록, 그다음 계획 본문
        assertThat(planFile).isEqualTo(RefRepoPrompts.planFileHeader(List.of(rw12)) + header
                + ImplementationScratch.normalizeTaskHeadings(PLAN));
        assertThat(header).startsWith("## 인터뷰 목업\n").contains("그대로 따르라").contains(EXCLUDED_LINE)
                .contains("`.netis/mockups/3-list-filter-a-v2.html`");
        assertThat(ImplementationScratch.countTaskHeadings(planFile)).isEqualTo(2);
        assertThat(exec.get().prompt()).contains(ImplementationPrompts.SDD_REF_LINE + InterviewMockupPrompts.SDD_MOCKUP_LINE)
                .contains(InterviewMockupPrompts.implementationSection(List.of(M3), List.of(2), false))
                .doesNotContain("interview mockups");   // SDD 리뷰어에는 계획 파일 머리와 SDD 줄로 전한다
        assertThat(exec.get().mockupFiles()).containsOnlyKeys(".netis/mockups/3-list-filter-a-v2.html");
    }

    @Test
    void SDD_승인디자인이면_계획파일_머리도_낮춘_문구() throws Exception {
        Claim c = claim(ImplementationMode.SDD);
        c.mockups = List.of(M3);
        c.design = "# 승인된 디자인";
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(exec.get().planFile())
                .startsWith(InterviewMockupPrompts.planFileHeader(List.of(M3), List.of(), true))
                .contains("승인본을 따르라").doesNotContain("그대로 따르라");
        assertThat(exec.get().prompt()).contains(InterviewMockupPrompts.SDD_MOCKUP_LINE)
                .contains("## 인터뷰 목업 (디자인 단계의 출발점 — 참고)");
    }

    @Test
    void 리뷰방식_리뷰어에_목업_요구사항() throws Exception {
        Claim c = claim(ImplementationMode.REVIEW);
        c.mockups = List.of(M3);
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(exec.get().prompt())
                .contains("Read it in full." + InterviewMockupPrompts.reviewerRequirement(List.of(M3), false))
                .contains("matches the interview mockups listed under \"인터뷰 목업\" at the top of the plan file")
                .doesNotContain("the design wins")
                .doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE);
        // 리뷰어가 읽을 계획 파일 머리에 목업 목록
        assertThat(exec.get().planFile())
                .isEqualTo(InterviewMockupPrompts.planFileHeader(List.of(M3), List.of(), false) + PLAN);
    }

    @Test
    void 승인디자인이_있으면_낮춘_절() throws Exception {
        Claim c = claim(ImplementationMode.REVIEW);
        c.mockups = List.of(M3);
        c.design = "# 승인된 디자인";
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(exec.get().prompt())
                .contains("## 확정된 디자인 (반드시 이 디자인 기준으로 구현)")
                .contains(InterviewMockupPrompts.implementationSection(List.of(M3), List.of(), true))
                .contains("## 인터뷰 목업 (디자인 단계의 출발점 — 참고)")
                .contains("다르면 `.design/` 승인본을 따르라")
                .doesNotContain("그대로 따를 것").doesNotContain("배치·라벨·요소·순서를 그대로 따르라")
                .contains(InterviewMockupPrompts.reviewerRequirement(List.of(M3), true))
                .contains("the design wins.");
        assertThat(exec.get().planFile()).contains("승인본을 따르라").doesNotContain("그대로 따르라");
        // 인터뷰 목업 파일은 그대로 넘긴다
        assertThat(exec.get().mockupFiles()).containsEntry(".netis/mockups/3-list-filter-a-v2.html", HTML_A);
    }

    @Test
    void 배치실패는_claude없이_구현실패() throws Exception {
        Claim c = claim(ImplementationMode.REVIEW);
        c.mockups = List.of(M3);
        claims(c);
        Files.writeString(wt.resolve(".netis"), "파일이라 폴더를 만들 수 없다");

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("인터뷰 목업 배치 실패: ");
        assertThat(r.headBranch()).isEqualTo(BRANCH);
        assertThat(r.implementationAttempt()).isEqualTo(1);
        verifyNoInteractions(claude);
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(worktrees, never()).discard(anyString(), any(), anyLong());   // 실패한 worktree는 디버그용으로 남긴다
    }

    @Test
    void 성공뒤_netis가_남지_않는다() throws Exception {
        Claim c = claim(ImplementationMode.SDD);
        c.mockups = List.of(M3, M5);
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);
        AtomicBoolean netisAtCommit = new AtomicBoolean(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), eq("base123"))).thenAnswer(inv -> {
            netisAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop().pollAndProcess();

        assertThat(exec.get().mockupFiles()).hasSize(2);
        assertThat(netisAtCommit.get()).isFalse();
        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(reported().status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void 실패해도_wip_전에_netis를_지운다() throws Exception {
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(M3);
        claims(c);
        claudeEnds(1);
        AtomicBoolean netisAtWip = new AtomicBoolean(true);
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            netisAtWip.set(Files.exists(wt.resolve(".netis")));
            return null;
        });

        loop().pollAndProcess();

        assertThat(reported().status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(netisAtWip.get()).isFalse();
    }

    // ---- PR 본문 ----

    @Test
    void PR본문_구현기준목업_절과_예산() throws Exception {
        Claim c = claim(ImplementationMode.REVIEW);
        c.mockups = List.of(M3);
        c.withRef = true;
        c.analysis = "가".repeat(60_000);
        claims(c);
        claudeEnds(0);

        loop().pollAndProcess();

        String body = prBody();
        String refSection = RefRepoPrompts.prSection(task.refRepos(), List.of());
        assertThat(body).contains(refSection + "## 구현 기준 목업\n- 목업 3 · `A안` (`list-filter-a` v2)\n\n## 사전 분석\n");
        assertThat(body.length()).isLessThanOrEqualTo(WorkerMainLoop.PR_BODY_MAX_CHARS);
        assertThat(body).contains(WorkerMainLoop.PR_BODY_CUT_MARK);

        // DB 변경 절은 그 자리(목업 절 다음) 그대로, 예산에서 목업 절 길이도 뺀다
        String dbSection = "## DB 변경\n- 개발 DB에 실행한 SQL: 1건\n\n";
        String direct = WorkerMainLoop.renderPrBody(task, 10, "head456", null, "메모 ".repeat(10_000),
                List.of(), dbSection);
        assertThat(direct).contains(refSection + InterviewMockupPrompts.prSection(List.of(M3)) + dbSection
                + "## 사전 분석\n");
        assertThat(direct.length()).isLessThanOrEqualTo(WorkerMainLoop.PR_BODY_MAX_CHARS);
    }

    @Test
    void PR본문의_목업_제목은_멘션이_되지_않는다() throws Exception {
        InterviewMockupFile odd = new InterviewMockupFile(4, "x", 1, "@admin `확인`", "<p/>");
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(odd);
        claims(c);
        claudeEnds(0);

        loop().pollAndProcess();

        assertThat(prBody()).contains("## 구현 기준 목업\n- 목업 4 · `@admin 확인` (`x` v1)\n");
    }

    // ---- 수정 회차 ----

    private void fixRound(Claim c) {
        c.fix = new WorkerTaskResponse.FixRoundSpec(9L, 2, 1, "저장 뒤 목록이 그대로", List.of(), FIX_BRANCH, "abc1234");
    }

    @Test
    void 회차는_파일을_두고_참고용_절을_넣는다() throws Exception {
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(M3);
        c.excluded = List.of(2);
        fixRound(c);
        claims(c);
        AtomicReference<Exec> exec = claudeEnds(0);

        loop().pollAndProcess();

        assertThat(exec.get().mockupFiles()).containsExactly(Map.entry(".netis/mockups/3-list-filter-a-v2.html", HTML_A));
        assertThat(exec.get().prompt()).contains(InterviewMockupPrompts.fixRoundSection(List.of(M3)))
                .doesNotContain("그대로 따를 것").doesNotContain("전달되지 않은");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.fixRequestId()).isEqualTo(9L);
        assertThat(wt.resolve(".netis")).doesNotExist();
    }

    @Test
    void 회차_배치실패는_claude없이_회차_구현실패() throws Exception {
        Claim c = claim(ImplementationMode.DIRECT);
        c.mockups = List.of(M3);
        fixRound(c);
        claims(c);
        Files.writeString(wt.resolve(".netis"), "파일");

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).startsWith("인터뷰 목업 배치 실패: ");
        assertThat(r.headBranch()).isEqualTo(FIX_BRANCH);
        assertThat(r.headSha()).isEqualTo("abc1234" + "0".repeat(33));   // 원격 끝 그대로
        assertThat(r.fixRequestId()).isEqualTo(9L);
        assertThat(r.fixAttempt()).isEqualTo(1);
        verifyNoInteractions(claude);
        verify(gitOps, never()).commitAndPush(any(), any(), anyString(), anyString(), any());
    }

    // ---- placeInterviewMockups ----

    @Test
    void 배치는_목업이_없으면_아무것도_만들지_않는다() throws Exception {
        WorkerTaskResponse none = claims(claim(ImplementationMode.DIRECT));
        WorkerMainLoop.placeInterviewMockups(none, wt);
        assertThat(wt.resolve(".netis")).doesNotExist();

        Claim c = claim(ImplementationMode.DIRECT);
        c.excluded = List.of(2);
        WorkerMainLoop.placeInterviewMockups(claims(c), wt);
        assertThat(wt.resolve(".netis")).doesNotExist();
    }

    /** mockupPath가 이름을 접어 실제로는 생기지 않는 경로지만, 정규화한 경로가 .netis/mockups 밖이면 쓰지 않는다. */
    @Test
    void 배치는_worktree_밖으로_나가지_않는다() throws Exception {
        assertThat(WorkerMainLoop.mockupTarget(wt, ".netis/mockups/3-a-v1.html"))
                .isEqualTo(wt.resolve(".netis/mockups/3-a-v1.html").toAbsolutePath().normalize());
        assertThatThrownBy(() -> WorkerMainLoop.mockupTarget(wt, ".netis/mockups/../../evil.html"))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("목업 경로가 worktree 밖입니다: ");
        assertThatThrownBy(() -> WorkerMainLoop.mockupTarget(wt, ".netis/plan/x.html"))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("목업 경로가 worktree 밖입니다: ");
        assertThat(tmp.resolve("evil.html")).doesNotExist();
    }
}
