package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.CloseProgressRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

/**
 * 작업 종료(kind=CLOSE) 오케스트레이션 (스펙 2026-10-13 §4).
 *   ① 병합 → ② 배포 중지 → ③ 옵시디언 노트 → ④ 원격 브랜치 삭제 (+ 로컬 worktree 정리, 실패는 경고만)
 * 단계마다 close-progress를 보고하고, claim의 closeProgress에 있는 단계는 건너뛴다. 어느 단계든 실패하면
 * 그 뒤 단계는 하지 않고 종료실패를 보고한다(관리자 재시도 → 남은 단계부터).
 */
@Component
@Profile("worker")
@Slf4j
public class TaskCloser {

    private final WorkerProperties props;
    private final CloseProperties closeProps;
    private final WorkerHttpClient http;
    private final DeployService deployService;
    private final WorktreeService worktrees;
    private final ClaudeExecAdapter claude;
    private final ResultReporter reporter;
    private final List<MergeRequestCloser> closers;
    private final CloseNoteWriter noteWriter;
    private final Clock clock;

    @Autowired
    public TaskCloser(WorkerProperties props, CloseProperties closeProps, WorkerHttpClient http,
                      DeployService deployService, WorktreeService worktrees, ClaudeExecAdapter claude,
                      ResultReporter reporter) {
        this(props, closeProps, http, deployService, worktrees, claude, reporter,
                List.of(new GitHubPrCloser(props.githubPat()), new GitLabMrCloser(props.gitlabToken())),
                new CloseNoteWriter(), Clock.system(ZoneId.of("Asia/Seoul")));
    }

    TaskCloser(WorkerProperties props, CloseProperties closeProps, WorkerHttpClient http,
               DeployService deployService, WorktreeService worktrees, ClaudeExecAdapter claude,
               ResultReporter reporter, List<MergeRequestCloser> closers, CloseNoteWriter noteWriter, Clock clock) {
        this.props = props;
        this.closeProps = closeProps;
        this.http = http;
        this.deployService = deployService;
        this.worktrees = worktrees;
        this.claude = claude;
        this.reporter = reporter;
        this.closers = closers;
        this.noteWriter = noteWriter;
        this.clock = clock;
    }

    /** 단계 실패 — 메시지는 그대로 failure_reason이 된다. */
    static final class StepFailed extends Exception {
        StepFailed(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface Step<T> {
        T run() throws Exception;
    }

    public void process(WorkerTaskResponse task) {
        EnumSet<CloseStep> done = CloseStep.parse(task.closeProgress());
        RepoRef ref = task.repoRef();
        WorkerResultRequest.UsageReport usage = null;
        String mergeSha = task.closeContext() == null ? null : task.closeContext().mergeCommitSha();
        try {
            if (!done.contains(CloseStep.MERGED)) {
                if (task.prNumber() == null) throw new StepFailed("PR 번호가 없어 병합할 수 없습니다");
                String merged = step("병합", () -> closerFor(ref).merge(ref, task.prNumber(), task.headSha(),
                        closeProps.mergeMethod()));
                if (merged != null) mergeSha = merged;
                progress(task, CloseStep.MERGED, mergeSha, null);
            }
            if (!done.contains(CloseStep.UNDEPLOYED)) {
                step("배포 중지", () -> { deployService.undeploy(task.id()); return null; });
                progress(task, CloseStep.UNDEPLOYED, null, null);
            }
            if (!done.contains(CloseStep.NOTE_WRITTEN)) {
                NoteOutcome n = writeNote(task, mergeSha);
                usage = n.usage();
                progress(task, CloseStep.NOTE_WRITTEN, null, n.relativePath());
            }
            if (!done.contains(CloseStep.BRANCH_DELETED)) {
                step("브랜치 삭제", () -> { closerFor(ref).deleteBranch(ref, task.headBranch()); return null; });
                progress(task, CloseStep.BRANCH_DELETED, null, null);
            }
            cleanupLocal(task);
            reporter.reportTerminal(task.id(), WorkerResultRequest.closed(props.id(), usage));
            log.info("작업 종료 완료: task={} merge={}", task.id(), mergeSha);
        } catch (StepFailed e) {
            log.warn("작업 종료 실패: task={} {}", task.id(), GitRemotes.mask(e.getMessage()));
            reporter.reportTerminal(task.id(), WorkerResultRequest.closeFailed(props.id(),
                    GitRemotes.mask(e.getMessage()), usage));
        }
    }

    /** 메인 루프의 예기치 못한 예외 경로. */
    public void reportFailure(Long taskId, String reason) {
        reporter.reportTerminal(taskId, WorkerResultRequest.closeFailed(props.id(), GitRemotes.mask(reason), null));
    }

    private record NoteOutcome(String relativePath, WorkerResultRequest.UsageReport usage) {}

    private NoteOutcome writeNote(WorkerTaskResponse task, String mergeSha) throws StepFailed {
        Path vault = closeProps.vaultDir();
        if (vault == null) {
            throw new StepFailed("노트: CLOSE_OBSIDIAN_VAULT_DIR 미설정 — 워커 public.env에 볼트 경로를 넣고 재시도하세요");
        }
        Path work = Paths.get(props.reposDir()).resolveSibling("close").resolve("task-" + task.id());
        ClaudeExecAdapter.ExecResult r = step("노트 생성", () -> {
            Files.createDirectories(work);
            try {
                return claude.execWithoutTools(CloseNotePrompt.render(closeProps.notePromptTemplate(), task, mergeSha),
                        work.toFile(), closeProps.noteTimeout(), closeProps.noteModel());
            } finally {
                RepoCacheCleanupJob.deleteTree(work);
            }
        });
        WorkerResultRequest.UsageReport usage = usageOf(r.usage());
        // 코드펜스만 있는 출력("```markdown\n```")은 clean() 뒤에 비므로 빈 노트와 같이 실패로 본다.
        if (r.exitCode() != 0 || CloseNoteWriter.clean(r.stdout()).isBlank()) {
            String out = r.stdout() == null ? "" : r.stdout();
            throw new StepFailed("노트 생성 실패(exit=" + r.exitCode() + "): "
                    + GitRemotes.mask(out.substring(0, Math.min(300, out.length()))));
        }
        CloseNoteWriter.Written w = step("노트 저장", () -> noteWriter.write(vault, closeProps.noteFolder(),
                new CloseNoteWriter.NoteMeta(task.id(), task.title(), task.prUrl(), mergeSha),
                r.stdout(), LocalDate.now(clock)));
        return new NoteOutcome(w.relativePath(), usage);
    }

    private void progress(WorkerTaskResponse task, CloseStep s, String mergeSha, String notePath) throws StepFailed {
        step("진행 보고(" + s + ")", () -> {
            http.postCloseProgress(task.id(), new CloseProgressRequest(props.id(), s, mergeSha, notePath));
            return null;
        });
    }

    private void cleanupLocal(WorkerTaskResponse task) {
        String key = GitRemotes.localKey(task.repoRef());
        for (WorktreeKind k : WorktreeKind.values()) worktrees.discard(key, k, task.id());   // 실패는 discard가 삼킨다
    }

    private MergeRequestCloser closerFor(RepoRef ref) throws StepFailed {
        return closers.stream().filter(c -> c.supports(ref)).findFirst()
                .orElseThrow(() -> new StepFailed("이 레포 호스트의 병합을 지원하지 않습니다: " + ref.host()));
    }

    private static <T> T step(String label, Step<T> s) throws StepFailed {
        try {
            return s.run();
        } catch (StepFailed e) {
            throw e;
        } catch (MergeRequestCloser.CloseRefusedException e) {
            throw new StepFailed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailed(label + " 중단됨");
        } catch (Exception e) {
            throw new StepFailed(label + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static WorkerResultRequest.UsageReport usageOf(ClaudeExecAdapter.Usage u) {
        return u == null ? null : new WorkerResultRequest.UsageReport(u.costUsd(), u.inputTokens(), u.outputTokens(),
                u.cacheCreationTokens(), u.cacheReadTokens());
    }
}
