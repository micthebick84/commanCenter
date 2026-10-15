package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 claude로 리뷰·SDD 구현을 한 번씩 돌리는 로컬 스모크 (스펙 2026-10-15 §9) — 구독 사용량이 들어 기본은 건너뛴다.
 * 워커 명령 조합(MCP 인자 포함)·실제 git(file:// 원격)·실제 worktree로 끝까지 가고, PR/MR 생성만 막는다.
 * 환경변수: RUN_CLAUDE_SMOKE=true(켜기), CLAUDE_CLI(claude 실행 파일), SUPERPOWERS_PLUGIN_PATH(SDD용 superpowers 플러그인 폴더).
 * Claude Code 세션 안에서 돌리면 중첩 세션 판정을 피하려고 CLAUDE*·ANTHROPIC*·MCP_* 환경변수를 지운 셸에서 돌린다.
 *
 * "방식이 정말 돌았는가"는 산출물과 세션 기록 두 군데서 본다 — 파일 변경·작성자만 보면 세션이 리뷰·SDD 지시를 무시하고
 * 그냥 고쳐도 통과한다.
 *  - 산출물: 리뷰는 PR 본문의 리뷰 메모, SDD는 계획 작업마다 남은 커밋(워커 마무리 커밋 제외).
 *  - 세션 기록: Agent·Skill 도구 호출과 쓰인 모델. ClaudeExecAdapter.ExecResult.stdout은 envelope의 result 글뿐이고
 *    modelUsage는 합계(Usage)로만 남아 모델별 키를 볼 수 없어서, 같은 정보를 claude가 남긴 세션 기록에서 읽는다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_CLAUDE_SMOKE", matches = "true")
class ImplementationModeClaudeSmokeTest {

    @TempDir Path tmp;

    private static final String TITLE = "hello 파일";
    /** 워커 마무리 커밋의 제목 — WorkerMainLoop.processImplementation이 "feat: " + 작업 제목으로 만든다. */
    private static final String WORKER_COMMIT_SUBJECT = "feat: " + TITLE;
    private static final String WORKER_AUTHOR = "netisMaker <netismaker@hamonsoft.local>";
    /** 연습 계획의 작업 수 — SDD는 작업마다 커밋한다. */
    private static final int PLAN_TASKS = 2;

    private static final String PLAN = String.join("\n",
            "# hello 구현 계획",
            "",
            "### 작업 1: hello.txt 만들기",
            "- [ ] 레포 루트에 `hello.txt`를 만들고 내용은 `hello` 한 줄로 한다.",
            "- [ ] 확인: `cat hello.txt`가 `hello`를 출력한다.",
            "",
            "### 작업 2: README에 한 줄 추가",
            "- [ ] `README.md` 끝에 `- hello.txt: 인사 파일` 한 줄을 추가한다.",
            "- [ ] 확인: `grep -c \"hello.txt\" README.md`가 1이다.",
            "");

    private record Commit(String author, String subject) {}

    /** 한 번 돌린 결과 — 워커 보고, PR 본문, 원격에 올라간 브랜치의 파일·커밋, claude 세션 기록. */
    private record Run(WorkerResultRequest report, String prBody, String trackedFiles,
                       List<Commit> commits, SessionLog session) {}

    @Test
    void review_mode_runs_end_to_end_with_the_real_worker_command() throws Exception {
        Run r = run(ImplementationMode.REVIEW);
        assertSoftly(softly -> {
            assertShipped(softly, r, "- **구현 방식**: 리뷰\n");
            // 리뷰어 단계가 .netis/review-notes.md를 썼고 워커가 PR 본문으로 회수했다 — 지시를 무시하면 이 절이 없다
            softly.assertThat(r.prBody()).as("PR 본문 '## 리뷰 메모'(리뷰어가 쓴 .netis/review-notes.md 회수)")
                    .contains("## 리뷰 메모");
            assertSubagentsRan(softly, r.session());
        });
    }

    @Test
    void sdd_mode_runs_end_to_end_with_the_real_worker_command() throws Exception {
        Run r = run(ImplementationMode.SDD);
        assertSoftly(softly -> {
            assertShipped(softly, r, "- **구현 방식**: SDD\n");
            // SDD는 계획 작업마다 커밋한다(작성자는 assertShipped가 모두 netisMaker인지 본다). 워커 마무리 커밋은 세션이
            // 커밋하지 않고 남긴 변경을 담는 것이라 셈에서 뺀다 — 안 그러면 한 번만 커밋해도 통과한다
            long taskCommits = r.commits().stream().filter(c -> !c.subject().equals(WORKER_COMMIT_SUBJECT)).count();
            softly.assertThat(taskCommits)
                    .as("SDD 태스크 커밋(워커 마무리 커밋 '%s' 제외), 전체 커밋: %s", WORKER_COMMIT_SUBJECT, r.commits())
                    .isGreaterThanOrEqualTo(PLAN_TASKS);
            softly.assertThat(r.session().skills()).as("Skill 도구 호출")
                    .anyMatch(s -> s.contains("subagent-driven-development"));
            assertSubagentsRan(softly, r.session());
        });
    }

    /** 방식과 무관한 공통 확인 — 방식 줄, 브랜치에 임시 파일 없음, 커밋 작성자, 사용량 보고. */
    private static void assertShipped(SoftAssertions softly, Run r, String modeLine) {
        softly.assertThat(r.prBody()).as("PR 본문 방식 줄(다른 방식으로 대신 진행했으면 줄이 다르다)").contains(modeLine);
        softly.assertThat(r.trackedFiles()).as("원격 브랜치의 파일").contains("hello.txt")
                .doesNotContain(".netis").doesNotContain(".superpowers").doesNotContain(".design");
        softly.assertThat(r.commits()).as("베이스(main) 이후 커밋의 작성자").isNotEmpty()
                .allMatch(c -> c.author().equals(WORKER_AUTHOR), "작성자가 netisMaker");
        WorkerResultRequest.UsageReport usage = r.report().usage();
        softly.assertThat(usage).as("구현 단계 사용량 보고").isNotNull();
        if (usage != null) softly.assertThat(usage.outputTokens()).as("출력 토큰").isPositive();
    }

    /** Agent 도구로 서브에이전트가 실제로 돌았는지 — 세션 모델과 다른 모델이 하나라도 있어야 한다. */
    private static void assertSubagentsRan(SoftAssertions softly, SessionLog s) {
        softly.assertThat(s.files())
                .as("claude 세션 기록 파일 수 — '%s'가 들어간 기록 폴더 %s", s.key(), s.dirs()).isPositive();
        softly.assertThat(s.agentCalls())
                .as("Agent 도구 호출 수 — 0이면 MCP 인자(--allowedTools 등)가 서브에이전트를 막았거나 지시를 무시한 것").isPositive();
        softly.assertThat(s.models())
                .as("세션과 서브에이전트가 쓴 모델(modelUsage 키에 해당) — 서브에이전트는 세션과 다른 모델로 돈다")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    private Run run(ImplementationMode mode) throws Exception {
        SuperpowersPlugin plugin = new SuperpowersPlugin(System.getenv("SUPERPOWERS_PLUGIN_PATH"));
        if (mode == ImplementationMode.SDD) {
            // 플러그인이 없으면 워커가 SDD를 리뷰로 대신 진행한다 — 구독 사용량을 쓰기 전에 설정부터 막는다
            assertThat(plugin.sddReadyPath())
                    .as("SDD 스모크는 SUPERPOWERS_PLUGIN_PATH(superpowers 플러그인 폴더)가 필요하다").isPresent();
        }
        Path bare = bareRemote();
        // 폴더는 전부 @TempDir 아래 — 라이브 캐시(~/netis-maker)를 건드리지 않는다. deadLetterDir는 목 보고기라 안 쓰지만
        // 기본값이 라이브 폴더를 가리키므로 같이 돌린다. null은 WorkerProperties 기본값(브랜치 접두사·git 작성자 포함)
        WorkerProperties props = new WorkerProperties(
                "smoke-1", null, null, null,              // id, version, apiBaseUrl, apiKey
                0, 0, 0, 0, 0,                            // 폴링·하트비트·보고 재시도·백오프·재전송
                tmp.resolve("repos").toString(),          // reposDir
                tmp.resolve("dead-letter").toString(),    // deadLetterDir
                System.getenv("CLAUDE_CLI"),              // claudeCliPath
                null, null,                               // githubPat, gitlabToken
                null, null,                               // promptTemplate, analysisTimeout
                tmp.resolve("worktrees").toString(),      // worktreeRoot
                null, null, null,                         // branchPrefix, gitUserName, gitUserEmail
                workerYmlTemplate(), null,                // implementationPromptTemplate(라이브 yml 그대로), implementationTimeout
                null, null,                               // designPromptTemplate, designTimeout
                null, null, null);                        // deploy, repoCacheCleanup, worktreeCleanup
        GitRepoCache cache = new GitRepoCache(props);
        WorktreeService worktrees = new WorktreeService(props, cache);
        GitOpsService gitOps = spy(new GitOpsService(props));
        doReturn(new GitOpsService.PrInfo("https://example.invalid/pr/1", 1))
                .when(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        WorkerMcpSupport mcp = mock(WorkerMcpSupport.class);
        Path emptyMcp = Files.writeString(tmp.resolve("mcp.json"), "{\"mcpServers\":{}}");
        when(mcp.buildClaudeArgsForTask(any())).thenReturn(new WorkerMcpSupport.TaskClaudeArgs(
                List.of("--mcp-config", emptyMcp.toString(), "--strict-mcp-config",
                        "--allowedTools", "mcp__smoke__noop"), null));
        ClaudeExecAdapter claude = new ClaudeExecAdapter(props, mcp);
        claude.init();
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        ResultReporter reporter = mock(ResultReporter.class);

        Task t = Task.create("acme/hello", "main", TITLE, "hello.txt를 만들고 README에 한 줄", "user1", 3,
                List.of(), "claude-sonnet-5-5", "medium");
        ReflectionTestUtils.setField(t, "id", 901L);
        t.setGitUrl(bare.toUri().toString());
        t.setImplementationMode(mode);
        t.setImplementationTimeoutMinutes(mode == ImplementationMode.SDD ? 60 : 30);
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(901L, "# 설계\nhello 파일을 만든다.", "[]", null, 1L), null, PLAN)));

        new WorkerMainLoop(props, http, cache, claude, mock(PromptResultParser.class), mcp, worktrees, gitOps,
                mock(DeployService.class), reporter, mock(SilentLossTracker.class), mock(DesignResultHarvester.class),
                mock(TaskCloser.class), plugin)
                .pollAndProcess();

        ArgumentCaptor<WorkerResultRequest> report = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(901L), report.capture());
        WorkerResultRequest result = report.getValue();
        // 여기서 막히면 아래 확인은 의미가 없다 — 실패 사유와 claude 마지막 출력을 같이 보여 준다
        assertThat(result.status())
                .as("구현 결과 — 사유: %s%nclaude 출력 끝: %s", result.failureReason(), tail(result.implementationLog(), 1500))
                .isEqualTo(TaskStatus.PR_CREATED);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());

        // 원격(bare)에 올라간 작업 브랜치를 본다 — worktree는 성공하면 워커가 지운다
        String branch = "refs/heads/" + result.headBranch();
        String trackedFiles = git(bare.toFile(), "ls-tree", "-r", "--name-only", branch);
        List<Commit> commits = commitsOf(git(bare.toFile(), "log", "--format=%an <%ae>%x09%s", "main.." + branch));
        SessionLog session = SessionLog.read(claudeProjectsDir(), tmp.getFileName().toString());
        System.out.printf("[스모크 %s] claude %d초 · 사용량 %s · Agent %d회 · 스킬 %s · 모델 %s · 커밋 %d개%n",
                mode, result.durationMs() / 1000, result.usage(), session.agentCalls(), session.skills(),
                session.models(), commits.size());
        return new Run(result, body.getValue(), trackedFiles, commits, session);
    }

    /** `작성자 <메일>\t제목` 줄들 — git이 섞어 낸 경고 줄(탭 없음)은 버린다. */
    private static List<Commit> commitsOf(String log) {
        return log.lines().filter(l -> l.contains("\t")).map(l -> {
            String[] parts = l.split("\t", 2);
            return new Commit(parts[0], parts[1]);
        }).toList();
    }

    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "# hello\n");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    private static String git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    /** claude 설정 폴더(CLAUDE_CONFIG_DIR, 없으면 ~/.claude) 아래 세션 기록 폴더. */
    private static Path claudeProjectsDir() {
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        Path config = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".claude") : Path.of(configured);
        return config.resolve("projects");
    }

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = ImplementationModeClaudeSmokeTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            return (String) worker.get("implementation-prompt-template");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * claude가 남긴 세션 기록에서 읽은 증거. 기록은 {설정 폴더}/projects/{작업 폴더 경로의 영숫자 아닌 글자를 '-'로 바꾼 이름}/
     * {세션}.jsonl, 서브에이전트 기록은 {세션}/subagents/ 아래다. 작업 폴더(worktree)가 @TempDir 아래라 그 폴더 이름(영숫자)이
     * 들어 있는 기록 폴더를 찾아 모두 읽는다 — 실행마다 이름이 달라 다른 실행의 기록과 섞이지 않는다.
     * 기록은 지우지 않는다(실패 때 들여다볼 자료이고, 보존 기간은 claude가 정리한다).
     */
    record SessionLog(String key, List<String> dirs, int files, int agentCalls, List<String> skills,
                      Set<String> models) {

        private static final ObjectMapper JSON = new ObjectMapper();

        static SessionLog read(Path projectsDir, String tempDirName) throws IOException {
            String key = tempDirName.replaceAll("[^A-Za-z0-9]", "-");
            List<String> dirs = new ArrayList<>();
            List<Path> logs = new ArrayList<>();
            if (Files.isDirectory(projectsDir)) {
                try (Stream<Path> list = Files.list(projectsDir)) {
                    for (Path project : list.filter(d -> Files.isDirectory(d)
                            && d.getFileName().toString().contains(key)).toList()) {
                        dirs.add(project.getFileName().toString());
                        try (Stream<Path> walk = Files.walk(project)) {
                            walk.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(logs::add);
                        }
                    }
                }
            }
            Set<String> agentCallIds = new TreeSet<>();   // 같은 호출이 두 줄에 나뉘어 적혀도 한 번으로 센다
            List<String> skills = new ArrayList<>();
            Set<String> models = new TreeSet<>();
            for (Path log : logs) scan(log, agentCallIds, skills, models);
            return new SessionLog(key, dirs, logs.size(), agentCallIds.size(), skills, models);
        }

        /** 기록 한 파일에서 assistant 메시지의 모델과 Agent·Skill 도구 호출을 모은다. */
        private static void scan(Path log, Set<String> agentCallIds, List<String> skills, Set<String> models)
                throws IOException {
            try (BufferedReader in = Files.newBufferedReader(log)) {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode rec = parse(line);
                    if (rec == null || !"assistant".equals(rec.path("type").asText())) continue;
                    JsonNode message = rec.path("message");
                    String model = message.path("model").asText("");
                    if (!model.isBlank() && !model.startsWith("<")) models.add(model);   // "<synthetic>" 제외
                    for (JsonNode block : message.path("content")) {
                        if (!"tool_use".equals(block.path("type").asText())) continue;
                        String tool = block.path("name").asText("");
                        if ("Agent".equals(tool)) agentCallIds.add(block.path("id").asText());
                        if ("Skill".equals(tool)) skills.add(block.path("input").path("skill").asText(""));
                    }
                }
            }
        }

        private static JsonNode parse(String line) {
            try {
                return line.isBlank() ? null : JSON.readTree(line);
            } catch (JsonProcessingException e) {
                return null;   // 쓰는 중이던 줄 등 — 건너뛴다
            }
        }
    }
}
