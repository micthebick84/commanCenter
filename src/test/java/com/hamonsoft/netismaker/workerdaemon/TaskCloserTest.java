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
import org.springframework.web.client.RestClientException;

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
        return task(progress, null);
    }

    private WorkerTaskResponse task(String progress, String mergeSha) {
        CloseContext ctx = new CloseContext(null, null, null, mergeSha, "http://h:1", null, List.of(), List.of());
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

    private static ClaudeExecAdapter.Usage usage() {
        return new ClaudeExecAdapter.Usage(new java.math.BigDecimal("0.12"), 100, 50, 10, 5);
    }

    @Test
    void 노트_생성이_실패해도_claude_사용량은_종료실패에_실린다() throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "", 10, usage()));
        closer.process(task("MERGED,UNDEPLOYED"));
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.usage()).isNotNull();
        assertThat(r.usage().inputTokens()).isEqualTo(100L);
    }

    @Test
    void 펜스만_있는_출력이어도_사용량은_종료실패에_실린다() throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(0, "```markdown\n```", 10, usage()));
        closer.process(task("MERGED,UNDEPLOYED"));
        assertThat(terminal().usage()).isNotNull();
    }

    @Test
    void 노트_저장이_실패해도_사용량은_종료실패에_실린다() throws Exception {
        CloseNoteWriter badWriter = mock(CloseNoteWriter.class);
        when(badWriter.write(any(), any(), any(), any(), any())).thenThrow(new java.io.IOException("디스크 가득"));
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.id()).thenReturn("w1");
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(0, "# 노트 본문", 10, usage()));
        TaskCloser c = new TaskCloser(props, new CloseProperties(tmp.resolve("vault").toString(), null, null, null,
                null, null), http, deploy, worktrees, claude, reporter, List.of(mr), badWriter, Clock.systemUTC());
        c.process(task("MERGED,UNDEPLOYED"));
        WorkerResultRequest r = terminal();
        assertThat(r.failureReason()).startsWith("노트 저장 실패");
        assertThat(r.usage()).isNotNull();
    }

    @Test
    void 성공하면_노트_사용량이_작업종료_보고에_실린다() throws Exception {
        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(0, "# 노트 본문", 10, usage()));
        closer.process(task(null));
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSED);
        assertThat(r.usage()).isNotNull();
        assertThat(r.usage().outputTokens()).isEqualTo(50L);
        assertThat(r.usage().costUsd()).isEqualByComparingTo("0.12");
    }

    @Test
    void 병합이_이미_끝났으면_claim의_병합_커밋을_노트에_쓰고_병합은_다시_하지_않는다() throws Exception {
        closer.process(task("MERGED,UNDEPLOYED", "feedbeef"));
        verify(mr, never()).merge(any(), anyInt(), any(), any());
        Path note = tmp.resolve("vault/HamonSoft/netisMaker/로그인-수정-작업7-2026-10-13.md");
        assertThat(Files.readString(note)).contains("merge_commit: feedbeef");
        assertThat(terminal().status()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 진행_보고가_실패하면_이후_단계를_하지_않고_종료실패() throws Exception {
        doThrow(new RestClientException("연결 거부")).when(http).postCloseProgress(eq(7L), any());
        closer.process(task(null));
        verify(deploy, never()).undeploy(anyLong());
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.failureReason()).contains("진행 보고");
    }

    @Test
    void 배포_중지가_실패하면_배포_중지_실패로_보고한다() throws Exception {
        doThrow(new DeployService.DeployException("컨테이너 중지 실패: boom", null)).when(deploy).undeploy(7L);
        closer.process(task("MERGED"));
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.failureReason()).startsWith("배포 중지 실패");
        verify(claude, never()).execWithoutTools(any(), any(), any(), any());
    }

    @Test
    void 노트_작업_폴더는_성공이든_실패든_남지_않는다() throws Exception {
        Path work = tmp.resolve("close/task-7");
        closer.process(task("MERGED,UNDEPLOYED"));
        assertThat(work).doesNotExist();

        when(claude.execWithoutTools(anyString(), any(), any(), anyString()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "", 10, null));
        closer.process(task("MERGED,UNDEPLOYED"));
        assertThat(work).doesNotExist();
    }
}
