package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.ImageAvailability;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 생성한 Dockerfile 끝에 JVM 기본 로케일(ko_KR)을 붙인다 — 레포에 있는 Dockerfile은 건드리지 않는다.
 * 계기: 2026-11-03 라이나 생명 작업 20~23 — tomcat 이미지(LANG=C.UTF-8)에서 JVM 기본 언어가 en이라
 * 로케일 없이 찾는 메뉴 메시지(message.properties 기본 파일에 없음)가 NoSuchMessageException → 첫 화면이 오류 JSON.
 */
class DeployServiceJavaLocaleTest {

    @TempDir Path tmp;

    private static final String GENERATED = "FROM tomcat:7-jre8\nCOPY app.war /usr/local/tomcat/webapps/ROOT.war\nEXPOSE 8080\n";

    private ClaudeExecAdapter claude;
    private DeployTarget target;
    private DeployService service;
    private File wt;
    private final List<String> sink = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        GitRepoCache repos = mock(GitRepoCache.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        claude = mock(ClaudeExecAdapter.class);
        target = mock(DeployTarget.class);
        File cache = Files.createDirectories(tmp.resolve("cache")).toFile();
        wt = Files.createDirectories(tmp.resolve("wt")).toFile();
        when(repos.fetchOnly(any(), anyString())).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "sha"));
        when(worktrees.createForDeploy(any(), anyString(), anyString(), anyLong())).thenReturn(wt);
        when(target.deploy(any(), any())).thenReturn(
                new DeployTarget.DeployResult("http://h:19000", "cid", 19000, "img", "[target]\n"));
        when(target.checkImage(anyString())).thenReturn(ImageAvailability.PRESENT);
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        service = new DeployService(props, repos, worktrees, claude, target);
    }

    private static WorkerTaskResponse task() {
        return new WorkerTaskResponse(20L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-20-add-login",
                "abc1234def", List.of(), null, null, null, null, null, null, null, null, null, null, null);
    }

    private void claudeWrites(String content) throws Exception {
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"), content);
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });
    }

    private String dockerfile() throws Exception {
        return Files.readString(wt.toPath().resolve("Dockerfile"));
    }

    @Test
    void 생성한_Dockerfile_끝에_로케일을_붙인다() throws Exception {
        claudeWrites(GENERATED);

        var result = service.deploy(task(), sink::add);

        assertThat(dockerfile()).isEqualTo(GENERATED + DockerfileSupport.JAVA_LOCALE_BLOCK);
        assertThat(result.log()).contains("[로케일] JAVA_TOOL_OPTIONS=-Duser.language=ko -Duser.country=KR 추가\n");
        verify(target).deploy(any(), any());
    }

    @Test
    void 생성한_Dockerfile이_이미_정했으면_그대로_둔다() throws Exception {
        String own = GENERATED + "ENV JAVA_TOOL_OPTIONS=-Duser.language=ja\n";
        claudeWrites(own);

        var result = service.deploy(task(), sink::add);

        assertThat(dockerfile()).isEqualTo(own);
        assertThat(result.log()).contains("[로케일] Dockerfile이 이미 정함 — 그대로 둠\n");
    }

    @Test
    void 레포에_있는_Dockerfile은_건드리지_않는다() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), GENERATED);

        var result = service.deploy(task(), sink::add);

        assertThat(dockerfile()).isEqualTo(GENERATED);
        assertThat(result.log()).doesNotContain("[로케일]");
        verifyNoInteractions(claude);
    }
}
