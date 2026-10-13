package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.CloseContext;
import com.hamonsoft.netismaker.dto.CloseProgressRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCloserTest {

    @TempDir Path tmp;
    private WorkerHttpClient http;
    private DeployService deploy;
    private WorktreeService worktrees;
    private ClaudeExecAdapter claude;
    private ResultReporter reporter;
    private MergeRequestCloser mr;
    private TaskCloser closer;

    private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";

    private WorkerTaskResponse task(String progress) {
        CloseContext ctx = new CloseContext(null, null, null, null, "http://h:1", null, List.of(), List.of());
        return new WorkerTaskResponse(7L, "acme/w", "main", "로그인 수정", "d", WorkerTaskResponse.Kind.CLOSE,
                List.of(), null, null, "netismaker/task-7", SHA, List.of(), null, null, null, null, null, null,
                null, null, null, "github", null, 3, "https://github.com/acme/w/pull/3", progress, ctx);
    }

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        deploy = mock(DeployService.class);
        worktrees = mock(WorktreeService.class);
        claude = mock(ClaudeExecAdapter.class);
        reporter = mock(ResultReporter.class);
        mr = mock(MergeRequestCloser.class);
        when(mr.supports(any())).thenReturn(true);
        when(mr.merge(any(), eq(3), eq(SHA), eq("merge"))).thenReturn("m3rg3");
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(0, "# 노트 본문", 10, null));
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.id()).thenReturn("w1");
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        CloseProperties cp = new CloseProperties(tmp.resolve("vault").toString(), "HamonSoft/netisMaker", "merge",
                "sonnet", Duration.ofMinutes(1), null);
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        closer = new TaskCloser(props, cp, http, deploy, worktrees, claude, reporter, List.of(mr),
                new CloseNoteWriter(), clock);
    }

    private List<CloseStep> reportedSteps() {
        ArgumentCaptor<CloseProgressRequest> cap = ArgumentCaptor.forClass(CloseProgressRequest.class);
        verify(http, atLeast(0)).postCloseProgress(eq(7L), cap.capture());
        return cap.getAllValues().stream().map(CloseProgressRequest::step).toList();
    }

    private WorkerResultRequest terminal() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    @Test
    void 네_단계를_순서대로_하고_작업종료를_보고한다() throws Exception {
        closer.process(task(null));

        assertThat(reportedSteps()).containsExactly(CloseStep.MERGED, CloseStep.UNDEPLOYED,
                CloseStep.NOTE_WRITTEN, CloseStep.BRANCH_DELETED);
        verify(deploy).undeploy(7L);
        verify(mr).deleteBranch(any(RepoRef.class), eq("netismaker/task-7"));
        verify(worktrees).discard(anyString(), eq(WorktreeKind.TASK), eq(7L));
        assertThat(terminal().status()).isEqualTo(TaskStatus.CLOSED);
        Path note = tmp.resolve("vault/HamonSoft/netisMaker/로그인-수정-작업7-2026-10-13.md");
        assertThat(Files.readString(note)).contains("merge_commit: m3rg3").contains("# 노트 본문");
    }

    @Test
    void 끝난_단계는_건너뛴다() throws Exception {
        closer.process(task("MERGED,UNDEPLOYED"));
        verify(mr, never()).merge(any(), anyInt(), any(), any());
        verify(deploy, never()).undeploy(anyLong());
        assertThat(reportedSteps()).containsExactly(CloseStep.NOTE_WRITTEN, CloseStep.BRANCH_DELETED);
        assertThat(terminal().status()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 병합_거부면_이후_단계를_하지_않고_종료실패() throws Exception {
        when(mr.merge(any(), anyInt(), any(), any()))
                .thenThrow(new MergeRequestCloser.CloseRefusedException("CI 대기 중: checks pending"));
        closer.process(task(null));
        verify(deploy, never()).undeploy(anyLong());
        assertThat(reportedSteps()).isEmpty();
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.failureReason()).isEqualTo("CI 대기 중: checks pending");
    }

    @Test
    void 노트_실패면_브랜치를_지우지_않는다() throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "", 10, null));
        closer.process(task(null));
        verify(mr, never()).deleteBranch(any(), any());
        assertThat(reportedSteps()).containsExactly(CloseStep.MERGED, CloseStep.UNDEPLOYED);
        assertThat(terminal().failureReason()).contains("노트");
    }

    @Test
    void 펜스만_있는_노트_출력은_실패로_본다() throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(0, "```markdown\n```", 10, null));
        closer.process(task("MERGED,UNDEPLOYED"));
        verify(mr, never()).deleteBranch(any(), any());
        assertThat(reportedSteps()).isEmpty();
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.failureReason()).contains("노트");
    }

    @Test
    void 볼트_미설정이면_노트_단계에서_종료실패() throws Exception {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.id()).thenReturn("w1");
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        TaskCloser noVault = new TaskCloser(props, new CloseProperties(null, null, null, null, null, null),
                http, deploy, worktrees, claude, reporter, List.of(mr), new CloseNoteWriter(), Clock.systemUTC());
        noVault.process(task("MERGED,UNDEPLOYED"));
        assertThat(terminal().failureReason()).contains("CLOSE_OBSIDIAN_VAULT_DIR");
        verify(claude, never()).execWithoutTools(any(), any(), any(), any());
    }
}
