package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.InterviewMockupFile;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 디자인 흐름의 인터뷰 목업 — design worktree에 파일을 두고 출발점 절을 붙인다(설계 2026-11-03 §7). 디자인은 커밋하지 않고
 * 수확은 .design-out만 읽으므로 exclude는 부르지 않는다(계획 "설계와 다름 ③"). 템플릿은 운영 yml 그대로 읽는다.
 */
class WorkerMainLoopDesignMockupsTest {

    private static final String HTML = "<!doctype html><html><body><h1>목록 A안</h1></body></html>";
    private static final InterviewMockupFile M3 = new InterviewMockupFile(3, "list-filter-a", 2, "A안", HTML);

    @TempDir Path tmp;

    private WorkerProperties props;
    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private DesignResultHarvester harvester;
    private Path wt;

    /** application-worker.yml을 실제로 읽어 바인딩한 설정 — WorkerMainLoopDesignPromptTest와 같다. */
    private static WorkerProperties ymlProps() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"));
        for (PropertySource<?> ps : loaded) {
            env.getPropertySources().addLast(ps);
        }
        return Binder.get(env).bind("netis-maker.worker", WorkerProperties.class).get();
    }

    @BeforeEach
    void setUp() throws Exception {
        props = ymlProps();
        http = mock(WorkerHttpClient.class);
        repos = mock(GitRepoCache.class);
        claude = mock(ClaudeExecAdapter.class);
        worktrees = mock(WorktreeService.class);
        reporter = mock(ResultReporter.class);
        harvester = mock(DesignResultHarvester.class);
        wt = Files.createDirectories(tmp.resolve("design-wt"));

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.createForDesign(any(), anyString(), eq("main"), eq(7L))).thenReturn(wt.toFile());
        when(harvester.harvest(any())).thenReturn(new DesignResultHarvester.HarvestResult(
                "# 디자인", "[{\"path\":\"screens/a.html\",\"title\":\"a\",\"html\":\"<p/>\"}]", null, null));
    }

    private WorkerMainLoop loop() {
        return new WorkerMainLoop(props, http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, mock(GitOpsService.class), mock(DeployService.class),
                reporter, mock(SilentLossTracker.class), harvester, mock(TaskCloser.class),
                new SuperpowersPlugin(""), mock(ClaudeAuthProbe.class), mock(BaseSyncMerger.class));
    }

    /** 디자인 claim — previous가 있으면 반려 재실행(이전 디자인·피드백 동봉). */
    private WorkerTaskResponse claims(String previous, List<InterviewMockupFile> mockups, List<Integer> excluded) {
        WorkerTaskResponse task = new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DESIGN, List.of(), "분석", "[]", null, null, List.of(),
                null, null, previous, previous == null ? null : "[]",
                previous == null ? "[]" : "[{\"feedback\":\"버튼을 더 크게\"}]",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), null)
                .withInterviewMockups(mockups, excluded);
        when(http.nextTask()).thenReturn(Optional.of(task));
        return task;
    }

    private record Exec(String prompt, Map<String, String> mockupFiles) {}

    private AtomicReference<Exec> claudeSucceeds() throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0), mockupFiles()));
                    return new ClaudeExecAdapter.ExecResult(0, "done", 10, null);
                });
        return seen;
    }

    private Map<String, String> mockupFiles() throws Exception {
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

    @Test
    void 디자인은_출발점_절과_파일() throws Exception {
        WorkerTaskResponse task = claims(null, List.of(M3), List.of(2));
        AtomicReference<Exec> exec = claudeSucceeds();

        loop().pollAndProcess();

        assertThat(exec.get().mockupFiles()).containsExactly(Map.entry(".netis/mockups/3-list-filter-a-v2.html", HTML));
        String section = InterviewMockupPrompts.designSection(List.of(M3), false);
        assertThat(exec.get().prompt()).isEqualTo(WorkerMainLoop.renderDesignPrompt(props.designPromptTemplate(), task)
                + section + DbMcpWorkerSupport.promptSection(task.dbMcp()));
        assertThat(section).contains("## 인터뷰 목업 (출발점)\n").contains("이 목업에서 출발해 다듬어라")
                .contains("- 목업 3 · A안 (v2): `.netis/mockups/3-list-filter-a-v2.html`")
                .doesNotContain("이전 디자인과 피드백 반영이 우선이다")
                .doesNotContain("전달되지 않은");   // 디자인 claim에는 계획 본문이 없다 — 제외 번호를 쓰지 않는다
        assertThat(reported().status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
        verify(worktrees, never()).ensureWorkerExcludes(any());
        verify(worktrees).discard("acme/widgets", WorktreeKind.DESIGN, 7L);
    }

    @Test
    void 반려_재실행이면_이전_디자인과_피드백이_우선() throws Exception {
        WorkerTaskResponse task = claims("# 이전 설계", List.of(M3), List.of());
        AtomicReference<Exec> exec = claudeSucceeds();

        loop().pollAndProcess();

        assertThat(exec.get().prompt()).isEqualTo(WorkerMainLoop.renderDesignPrompt(props.designPromptTemplate(), task)
                        + InterviewMockupPrompts.designSection(List.of(M3), true)
                        + DbMcpWorkerSupport.promptSection(task.dbMcp()))
                .contains("이전 디자인이 있으면 이전 디자인과 피드백 반영이 우선이다")
                .contains("# 이전 설계").contains("버튼을 더 크게");
        assertThat(exec.get().mockupFiles()).hasSize(1);
    }

    /** 목업이 없으면(제외 번호만 있어도) 디자인 프롬프트는 지금과 같고 파일도 두지 않는다. */
    @Test
    void 목업이_없으면_디자인_프롬프트가_그대로() throws Exception {
        for (List<Integer> excluded : List.of(List.<Integer>of(), List.of(2))) {
            reset(reporter, claude);
            WorkerTaskResponse task = claims("# 이전 설계", List.of(), excluded);
            AtomicReference<Exec> exec = claudeSucceeds();

            loop().pollAndProcess();

            assertThat(exec.get().prompt()).isEqualTo(
                    WorkerMainLoop.renderDesignPrompt(props.designPromptTemplate(), task)
                            + DbMcpWorkerSupport.promptSection(task.dbMcp()))
                    .doesNotContain("인터뷰 목업");
            assertThat(exec.get().mockupFiles()).isEmpty();
            assertThat(wt.resolve(".netis")).doesNotExist();
            assertThat(reported().status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
        }
    }

    @Test
    void 디자인_배치실패는_디자인실패() throws Exception {
        claims(null, List.of(M3), List.of());
        Files.writeString(wt.resolve(".netis"), "파일이라 폴더를 만들 수 없다");

        loop().pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.DESIGN_FAILED);
        assertThat(r.failureReason()).startsWith("인터뷰 목업 배치 실패: ");
        verifyNoInteractions(claude);
        verifyNoInteractions(harvester);
        // 실패 보고 뒤 design worktree를 지운다
        InOrder order = inOrder(reporter, worktrees);
        order.verify(reporter).reportTerminal(eq(7L), any());
        order.verify(worktrees).discard("acme/widgets", WorktreeKind.DESIGN, 7L);
    }
}
