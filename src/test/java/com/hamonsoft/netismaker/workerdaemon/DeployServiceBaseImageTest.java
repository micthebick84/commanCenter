package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.ImageAvailability;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 생성한 Dockerfile의 베이스 이미지 확인 — 없는 태그면 build 전에 한 번 다시 생성, 그래도 없으면 배포실패.
 * 계기: 2026-11-02 작업 23(미리 빌드 성공 뒤 `FROM tomcat:7.0.109-jre8-openjdk` → docker build 첫 줄 not found), 10-28 작업 18.
 */
class DeployServiceBaseImageTest {

    @TempDir Path tmp;

    private static final String BAD = "tomcat:7.0.109-jre8-openjdk";
    private static final String GOOD = "tomcat:7.0.109-jdk8-openjdk";

    private GitRepoCache repos;
    private WorktreeService worktrees;
    private ClaudeExecAdapter claude;
    private DeployTarget target;
    private File wt;
    private final List<String> sink = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repos = mock(GitRepoCache.class);
        worktrees = mock(WorktreeService.class);
        claude = mock(ClaudeExecAdapter.class);
        target = mock(DeployTarget.class);
        File cache = Files.createDirectories(tmp.resolve("cache")).toFile();
        wt = Files.createDirectories(tmp.resolve("wt")).toFile();
        when(repos.fetchOnly(any(), anyString())).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "sha"));
        when(worktrees.createForDeploy(any(), anyString(), anyString(), anyLong())).thenReturn(wt);
        when(target.deploy(any(), any())).thenReturn(
                new DeployTarget.DeployResult("http://h:19000", "cid", 19000, "img", "[target]\n"));
        when(target.checkImage(BAD)).thenReturn(ImageAvailability.MISSING);
        when(target.checkImage(GOOD)).thenReturn(ImageAvailability.PRESENT);
    }

    private DeployService service(Duration dockerfileTimeout) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                dockerfileTimeout, 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        return new DeployService(props, repos, worktrees, claude, target);
    }

    private DeployService service() {
        return service(Duration.ofMinutes(7));
    }

    private static WorkerTaskResponse task() {
        return new WorkerTaskResponse(23L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-23-add-login",
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String dockerfile(String base) {
        return "FROM " + base + "\nCOPY app.war /usr/local/tomcat/webapps/ROOT.war\nEXPOSE 8080\n";
    }

    /** n번째 생성(1부터)이 쓸 베이스 이미지를 차례로 — 생성마다 Dockerfile을 새로 쓴다. */
    private void claudeWrites(String... bases) throws Exception {
        AtomicInteger call = new AtomicInteger();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            String base = bases[Math.min(call.getAndIncrement(), bases.length - 1)];
            Files.writeString(dir.toPath().resolve("Dockerfile"), dockerfile(base));
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });
    }

    @Test
    void 베이스_이미지가_있으면_다시_만들지_않는다() throws Exception {
        claudeWrites(GOOD);

        var result = service().deploy(task(), sink::add);

        verify(claude, times(1)).exec(anyString(), any(), any(), anyList(), anyBoolean());
        assertThat(result.log()).contains("[베이스 이미지] " + GOOD + "=있음\n").doesNotContain("[베이스 이미지 없음]");
        verify(target).deploy(any(), any());
    }

    @Test
    void 없으면_한_번_다시_만들고_배포한다() throws Exception {
        claudeWrites(BAD, GOOD);

        var result = service().deploy(task(), sink::add);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(claude, times(2)).exec(prompts.capture(), eq(wt), any(), anyList(), eq(true));
        String first = prompts.getAllValues().get(0);
        String second = prompts.getAllValues().get(1);
        assertThat(first).endsWith(DeployService.DOCKERFILE_NO_BUILD_RULE);
        assertThat(second).isEqualTo(first + DeployService.regenerateSection(List.of(BAD)));
        assertThat(second).contains(BAD).contains("docker manifest inspect");
        assertThat(result.log())
                .contains("[베이스 이미지] " + BAD + "=없음\n")
                .contains("[베이스 이미지 없음] " + BAD + " → claude 다시 생성\n")
                .contains("[베이스 이미지] " + GOOD + "=있음\n");
        assertThat(Files.readString(wt.toPath().resolve("Dockerfile"))).contains(GOOD);
        verify(target).deploy(any(), any());
    }

    @Test
    void 다시_만들어도_없으면_build하지_않고_실패한다() throws Exception {
        claudeWrites(BAD, BAD);

        assertThatThrownBy(() -> service().deploy(task(), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("베이스 이미지가 레지스트리에 없음: " + BAD);
                    assertThat(e.getDeployLog()).contains("[베이스 이미지 없음] " + BAD + " → claude 다시 생성");
                    assertThat(e.getDeployLog().split("=없음", -1)).hasSize(3);   // 두 번 확인
                });

        verify(claude, times(2)).exec(anyString(), any(), any(), anyList(), anyBoolean());
        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 확인하지_못하면_그대로_진행한다() throws Exception {
        // 하나는 UNKNOWN, 하나는 mock 기본값 null(다른 DeployTarget 구현·예전 mock) — 둘 다 확인 못 함
        when(target.checkImage("maven:3.6.3-jdk-8")).thenReturn(ImageAvailability.UNKNOWN);
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"),
                    "FROM maven:3.6.3-jdk-8 AS build\nFROM private.registry/tomcat:custom\nCOPY --from=build /a /b\n");
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });

        var result = service().deploy(task(), sink::add);

        verify(claude, times(1)).exec(anyString(), any(), any(), anyList(), anyBoolean());
        assertThat(result.log())
                .contains("[베이스 이미지] maven:3.6.3-jdk-8=확인 못 함, private.registry/tomcat:custom=확인 못 함\n");
        verify(target).deploy(any(), any());
    }

    @Test
    void 레포에_있는_Dockerfile은_확인하지_않는다() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), dockerfile(BAD));

        var result = service().deploy(task(), sink::add);

        verify(target, never()).checkImage(anyString());
        verifyNoInteractions(claude);
        assertThat(result.log()).contains("[기존 Dockerfile 사용]").doesNotContain("[베이스 이미지");
    }

    @Test
    void 다시_만들기는_dockerfile_timeout의_남은_시간만_쓴다() throws Exception {
        claudeWrites(BAD, GOOD);

        service(Duration.ofMinutes(7)).deploy(task(), sink::add);

        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(claude, times(2)).exec(anyString(), any(), timeouts.capture(), anyList(), anyBoolean());
        assertThat(timeouts.getAllValues().get(0)).isEqualTo(Duration.ofMinutes(7));
        assertThat(timeouts.getAllValues().get(1))
                .isLessThanOrEqualTo(Duration.ofMinutes(7))
                .isGreaterThan(Duration.ofMinutes(6));
    }

    @Test
    void 남은_시간이_1분보다_짧으면_다시_만들지_않고_실패한다() throws Exception {
        claudeWrites(BAD, GOOD);

        assertThatThrownBy(() -> service(Duration.ofSeconds(30)).deploy(task(), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("베이스 이미지가 레지스트리에 없음: " + BAD + " (다시 생성할 시간이 남지 않음)"));

        verify(claude, times(1)).exec(anyString(), any(), any(), anyList(), anyBoolean());
        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 다시_만드는_claude가_실패하면_배포실패() throws Exception {
        AtomicInteger call = new AtomicInteger();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            if (call.getAndIncrement() > 0) return new ClaudeExecAdapter.ExecResult(1, "실패", 10, null);
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"), dockerfile(BAD));
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });

        assertThatThrownBy(() -> service().deploy(task(), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Dockerfile 생성 claude exit=1"));

        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 다시_만든_claude가_Dockerfile을_지우면_배포실패() throws Exception {
        AtomicInteger call = new AtomicInteger();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Path df = dir.toPath().resolve("Dockerfile");
            if (call.getAndIncrement() > 0) Files.delete(df);
            else Files.writeString(df, dockerfile(BAD));
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });

        assertThatThrownBy(() -> service().deploy(task(), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("claude가 Dockerfile을 생성하지 못함"));

        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 미리_빌드면_dockerignore는_다시_만든_뒤에_쓴다() throws Exception {
        var spec = new WorkerTaskResponse.PrebuildSpec("mvn -q package", "netis/target/*.war", "jdk8-maven");
        DeployPrebuilder prebuilder = mock(DeployPrebuilder.class);
        when(prebuilder.run(eq(spec), any(), any()))
                .thenReturn(new DeployPrebuilder.Staged("ROOT.war", 1024, "netis/target/ROOT.war"));
        AtomicInteger call = new AtomicInteger();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            String base = call.getAndIncrement() == 0 ? BAD : GOOD;
            Files.writeString(dir.toPath().resolve("Dockerfile"),
                    "FROM " + base + "\nCOPY .netis-prebuild/ROOT.war /usr/local/tomcat/webapps/ROOT.war\n");
            Files.writeString(dir.toPath().resolve(".dockerignore"), "node_modules\n");   // 생성 claude가 매번 씀
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        var task = new WorkerTaskResponse(23L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-23-add-login",
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), spec);

        var result = new DeployService(props, repos, worktrees, claude, target, prebuilder).deploy(task, sink::add);

        assertThat(Files.readString(wt.toPath().resolve(".dockerignore"))).isEqualTo(DeployPrebuilder.DOCKERIGNORE);
        assertThat(result.log().indexOf("[베이스 이미지 없음]"))
                .isLessThan(result.log().indexOf("[빌드 컨텍스트]"));
    }

    @Test
    void 생성_규칙은_레지스트리_확인만_허용하고_태그를_지어내지_말라고_한다() {
        String rule = DeployService.DOCKERFILE_NO_BUILD_RULE;

        assertThat(rule).contains("`docker manifest inspect <이미지:태그>`").contains("## 베이스 이미지 (필수)");
        assertThat(rule.indexOf("## 금지 (필수)")).isLessThan(rule.indexOf("## 베이스 이미지 (필수)"));
    }
}
