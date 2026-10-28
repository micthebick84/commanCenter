package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 배포 흐름의 미리 빌드 연결(스펙 2026-10-28 §5.2): 순서·로그·.dockerignore·경고·실패 처리. */
class DeployServicePrebuildTest {

    @TempDir Path tmp;

    private static final WorkerTaskResponse.PrebuildSpec SPEC =
            new WorkerTaskResponse.PrebuildSpec("mvn -q package", "hm-dash/target/*.war", "jdk8-maven");
    private static final DeployPrebuilder.Staged STAGED =
            new DeployPrebuilder.Staged("hm-dash.war", 1024, "hm-dash/target/hm-dash.war");
    private static final String NO_IGNORE_WARNING =
            "[경고] Dockerfile이 .netis-prebuild/를 쓰지 않음 — 미리 빌드 산출물이 이미지에 들어가지 않을 수 있음";

    private GitRepoCache repos;
    private WorktreeService worktrees;
    private ClaudeExecAdapter claude;
    private DeployTarget target;
    private DeployPrebuilder prebuilder;
    private File wt;
    private final List<String> sink = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repos = mock(GitRepoCache.class);
        worktrees = mock(WorktreeService.class);
        claude = mock(ClaudeExecAdapter.class);
        target = mock(DeployTarget.class);
        prebuilder = mock(DeployPrebuilder.class);
        File cache = Files.createDirectories(tmp.resolve("cache")).toFile();
        wt = Files.createDirectories(tmp.resolve("wt")).toFile();
        when(repos.fetchOnly(any(), anyString())).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "sha"));
        when(worktrees.createForDeploy(any(), anyString(), anyString(), anyLong())).thenReturn(wt);
        when(target.deploy(any(), any())).thenReturn(
                new DeployTarget.DeployResult("http://h:19000", "cid", 19000, "img", "[target]\n"));
    }

    private DeployService service() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        return new DeployService(props, repos, worktrees, claude, target, prebuilder);
    }

    private static WorkerTaskResponse task(WorkerTaskResponse.PrebuildSpec prebuild) {
        return new WorkerTaskResponse(9L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-9-add-login",
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), prebuild);
    }

    private void prebuilderStages() throws Exception {
        when(prebuilder.run(eq(SPEC), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(2);
            out.accept("[미리 빌드] $ mvn -q package (도구=jdk8-maven)");
            out.accept("[미리 빌드 산출물] hm-dash/target/hm-dash.war (0.0MB) → .netis-prebuild/hm-dash.war");
            return STAGED;
        });
    }

    @Test
    void prebuild_null이면_예전과_같다() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");

        var result = service().deploy(task(null), sink::add);

        verifyNoInteractions(prebuilder);
        assertThat(wt.toPath().resolve(".dockerignore")).doesNotExist();
        assertThat(result.log()).doesNotContain("[미리 빌드").doesNotContain("[빌드 컨텍스트]")
                .doesNotContain("[경고]").contains("[기존 Dockerfile 사용]");
        assertThat(sink).isEmpty();
    }

    @Test
    void 생성한_Dockerfile이면_dockerignore를_나중에_쓴다() throws Exception {
        prebuilderStages();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"),
                    "FROM tomcat:8.5\nCOPY .netis-prebuild/hm-dash.war /usr/local/tomcat/webapps/ROOT.war\nEXPOSE 8080\n");
            Files.writeString(dir.toPath().resolve(".dockerignore"), "node_modules\n");
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });

        var result = service().deploy(task(SPEC), sink::add);

        assertThat(Files.readString(wt.toPath().resolve(".dockerignore"))).isEqualTo(DeployPrebuilder.DOCKERIGNORE);
        assertThat(result.log())
                .contains("[빌드 컨텍스트] .dockerignore를 미리 빌드 산출물만 보내도록 씀 (레포의 .dockerignore 교체)")
                .contains("[미리 빌드] $ mvn -q package")
                .doesNotContain("[경고]");
        assertThat(sink).anyMatch(l -> l.startsWith("[미리 빌드] $"))
                .anyMatch(l -> l.startsWith("[미리 빌드 산출물]"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(claude).exec(prompt.capture(), eq(wt), any(), anyList(), eq(true));
        assertThat(prompt.getValue())
                .contains(DeployPrebuilder.promptSection("hm-dash.war", SPEC.artifact()))
                .endsWith(DeployService.DOCKERFILE_NO_BUILD_RULE);
        // 미리 빌드 → 생성 순서
        var order = inOrder(prebuilder, claude, target);
        order.verify(prebuilder).run(any(), any(), any());
        order.verify(claude).exec(anyString(), any(), any(), anyList(), anyBoolean());
        order.verify(target).deploy(any(), any());
    }

    @Test
    void 생성한_Dockerfile에_기존_dockerignore가_없으면_교체_표시_없이_쓴다() throws Exception {
        prebuilderStages();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"), "FROM x\nCOPY .netis-prebuild/a /a\n");
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });

        var result = service().deploy(task(SPEC), sink::add);

        assertThat(Files.readString(wt.toPath().resolve(".dockerignore"))).isEqualTo(DeployPrebuilder.DOCKERIGNORE);
        assertThat(result.log()).contains("[빌드 컨텍스트] .dockerignore를 미리 빌드 산출물만 보내도록 씀\n")
                .doesNotContain("교체");
    }

    @Test
    void 레포_Dockerfile이면_dockerignore를_건드리지_않는다() throws Exception {
        prebuilderStages();
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");
        Files.writeString(wt.toPath().resolve(".dockerignore"), "target\n");

        var result = service().deploy(task(SPEC), sink::add);

        assertThat(Files.readString(wt.toPath().resolve(".dockerignore"))).isEqualTo("target\n");
        assertThat(result.log()).contains("[기존 Dockerfile 사용]").contains(NO_IGNORE_WARNING)
                .doesNotContain("[빌드 컨텍스트]");
        verifyNoInteractions(claude);
    }

    @Test
    void 레포_Dockerfile이_스테이징_폴더를_쓰면_경고가_없다() throws Exception {
        prebuilderStages();
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nCOPY .netis-prebuild/hm-dash.war /a.war\n");

        var result = service().deploy(task(SPEC), sink::add);

        assertThat(result.log()).doesNotContain("[경고]");
    }

    @Test
    void 미리_빌드_실패면_build하지_않는다() throws Exception {
        when(prebuilder.run(any(), any(), any()))
                .thenThrow(new DeployPrebuilder.PrebuildException("미리 빌드 실패 (exit=1)", "tail"));

        assertThatThrownBy(() -> service().deploy(task(SPEC), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("미리 빌드 실패 (exit=1)");
                    assertThat(e.getDeployLog()).contains("tail");
                });

        verify(target, never()).deploy(any(), any());
        verifyNoInteractions(claude);
    }

    @Test
    void 생성이_실패하면_dockerignore를_쓰지_않는다() throws Exception {
        prebuilderStages();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "실패", 10, null));

        assertThatThrownBy(() -> service().deploy(task(SPEC), sink::add))
                .isInstanceOf(DeployService.DeployException.class);

        assertThat(wt.toPath().resolve(".dockerignore")).doesNotExist();
        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 실행기_없이_만든_서비스는_prebuild_작업을_명확히_실패시킨다() throws Exception {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        var noPrebuilder = new DeployService(props, repos, worktrees, claude, target);

        assertThatThrownBy(() -> noPrebuilder.deploy(task(SPEC), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("미리 빌드 실행기가 없음"));
        verify(target, never()).deploy(any(), any());
    }

    @Test
    void buildDockerfilePrompt_4인자_빈_섹션은_3인자와_같다() {
        String three = DeployService.buildDockerfilePrompt(null, "acme/widgets", "b");

        assertThat(DeployService.buildDockerfilePrompt(null, "acme/widgets", "b", null)).isEqualTo(three);
        assertThat(DeployService.buildDockerfilePrompt(null, "acme/widgets", "b", "")).isEqualTo(three);
        assertThat(DeployService.buildDockerfilePrompt(null, "acme/widgets", "b", "  \n")).isEqualTo(three);
    }

    @Test
    void buildDockerfilePrompt_섹션은_템플릿_뒤_빌드금지_규칙_앞에_들어간다() {
        String prompt = DeployService.buildDockerfilePrompt("tpl {github_repo}@{branch}", "acme/widgets", "b",
                "## 섹션\n- 줄\n");

        assertThat(prompt).startsWith("tpl acme/widgets@b").endsWith(DeployService.DOCKERFILE_NO_BUILD_RULE);
        assertThat(prompt.indexOf("tpl")).isLessThan(prompt.indexOf("## 섹션"));
        assertThat(prompt.indexOf("## 섹션")).isLessThan(prompt.indexOf("## 금지 (필수)"));
    }
}
