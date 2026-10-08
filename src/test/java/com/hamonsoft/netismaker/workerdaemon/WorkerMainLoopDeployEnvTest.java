package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 구현 프롬프트의 배포 env 목록 지시 + 커밋 전 회수·보고 (스펙 2026-10-07 §4). */
class WorkerMainLoopDeployEnvTest {

    @TempDir Path tmp;

    // ---- 프롬프트 ----

    private static WorkerTaskResponse implementationTask() {
        Task t = Task.create("owner/repo", "main", "README 정리", "요구사항 본문", "user1", 3,
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 3L);
        return WorkerTaskResponse.forImplementation(t, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = WorkerMainLoopDeployEnvTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            return (String) worker.get("implementation-prompt-template");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_live_and_default_templates_both_end_with_the_deploy_env_instruction() {
        for (String tpl : List.of(workerYmlTemplate(), WorkerMainLoop.defaultImplementationPrompt())) {
            String prompt = WorkerMainLoop.fillImplementationPrompt(tpl, implementationTask(),
                    "35c9110", "netismaker/task-3-readme");

            assertThat(prompt).endsWith(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION);
            assertThat(prompt).contains(DeployEnvManifest.FILE_NAME);
            assertThat(prompt).contains("값은 절대 쓰지 마세요");
            assertThat(prompt).contains("SPRING_DATASOURCE_URL");
        }
    }

    @Test
    void the_instruction_frames_env_for_the_production_container_and_leaves_the_profile_to_the_dockerfile() {
        // 2026-10-08 작업 9: 목록이 SPRING_PROFILES_ACTIVE를 "기본값 dev"로 안내 → 배포 env가 Dockerfile의 prod를
        // 덮었고, netis-v7.0은 jar에서 application-dev.yml을 빼므로 컨테이너가 기동 직후 죽었다.
        String instruction = WorkerMainLoop.DEPLOY_ENV_INSTRUCTION;

        assertThat(instruction).contains("production용 Dockerfile");
        assertThat(instruction).contains("운영 실행 기준");
        assertThat(instruction).contains("SPRING_PROFILES_ACTIVE");
        assertThat(instruction).contains("목록에 넣지 마세요");
        assertThat(instruction).contains("빌드 산출물");
    }

    // ---- 회수·보고 (pollAndProcess) ----

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    private static WorkerTaskResponse queuedImplementation() {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.IMPLEMENTATION,
                List.of(), null, null, "netismaker/task-7-add-login", "abc1234def", List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private record Run(ResultReporter reporter, GitOpsService gitOps, AtomicBoolean manifestPresentAtCommit) {}

    /** claude가 (manifestJson이 있으면) 목록 파일을 쓰고 exit=claudeExit로 끝나는 구현 1회. */
    private Run run(String manifestJson, int claudeExit) throws Exception {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        GitRepoCache repos = mock(GitRepoCache.class);
        ClaudeExecAdapter claude = mock(ClaudeExecAdapter.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        GitOpsService gitOps = mock(GitOpsService.class);
        ResultReporter reporter = mock(ResultReporter.class);
        File dir = Files.createDirectories(tmp.resolve("wt")).toFile();
        Path manifest = dir.toPath().resolve(DeployEnvManifest.FILE_NAME);
        AtomicBoolean presentAtCommit = new AtomicBoolean();

        when(http.nextTask()).thenReturn(Optional.of(queuedImplementation()));
        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(dir, "base123"));
        when(worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login")))
                .thenReturn(new WorktreeService.CreatedWorktree(dir, "netismaker/task-7-add-login"));
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    if (manifestJson != null) Files.writeString(manifest, manifestJson);
                    return new ClaudeExecAdapter.ExecResult(claudeExit, "done", 10, null);
                });
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            presentAtCommit.set(Files.exists(manifest));   // 남아 있으면 git add -A로 PR에 섞인다
            return "head456";
        });
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));

        WorkerMainLoop loop = new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class));
        loop.pollAndProcess();
        return new Run(reporter, gitOps, presentAtCommit);
    }

    private static WorkerResultRequest reported(ResultReporter reporter) {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    @Test
    void manifest_is_removed_before_commit_and_reported_with_pr_created() throws Exception {
        Run r = run("{\"vars\":[{\"key\":\"JWT_SECRET\",\"description\":\"서명 키\",\"secret\":true,\"required\":true}]}", 0);

        assertThat(r.manifestPresentAtCommit()).isFalse();
        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(req.envTemplate()).containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void without_a_manifest_the_pr_is_still_created_with_an_empty_template() throws Exception {
        Run r = run(null, 0);

        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(req.envTemplate()).isEmpty();
    }

    @Test
    void a_failed_implementation_reports_no_template() throws Exception {
        Run r = run("{\"vars\":[{\"key\":\"A\"}]}", 1);

        WorkerResultRequest req = reported(r.reporter());
        assertThat(req.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(req.envTemplate()).isNull();
        verify(r.gitOps(), never()).commitAndPush(any(), any(), anyString(), anyString());
    }
}
