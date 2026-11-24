package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DeployOverrides;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 배포 덮어쓰기(DB 선택·설정 파일) 연결 (스펙 2026-11-24 §10). */
class DeployServiceOverridesTest {

    @TempDir Path tmp;

    private static final String ENTRY = "WEB-INF/classes/config/jdbc_maria.properties";
    private static final String TOMCAT_DOCKERFILE =
            "FROM tomcat:8.5.99-jdk17\nCOPY .netis-prebuild/app.war /usr/local/tomcat/webapps/ROOT.war\nEXPOSE 8080\n";
    private static final WorkerTaskResponse.PrebuildSpec SPEC =
            new WorkerTaskResponse.PrebuildSpec("mvn -q package", "target/*.war", "jdk8-maven");

    private GitRepoCache repos;
    private WorktreeService worktrees;
    private ClaudeExecAdapter claude;
    private DeployTarget target;
    private DeployPrebuilder prebuilder;
    private File wt;
    private Path reposDir;
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
        reposDir = tmp.resolve("repos");
        when(repos.fetchOnly(any(), anyString())).thenReturn(new GitRepoCache.CheckedOutRepo(cache, "sha"));
        when(worktrees.createForDeploy(any(), anyString(), anyString(), anyLong())).thenReturn(wt);
        when(target.deploy(any(), any())).thenReturn(
                new DeployTarget.DeployResult("http://h:19000", "cid", 19000, "img", "[target]\n"));
    }

    private DeployService service() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, Duration.ofMinutes(30), null,
                Duration.ofMinutes(7), 0, 0, null, null, 0, 0, 0, null, 0, null);
        var props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, reposDir.toString(), null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        return new DeployService(props, repos, worktrees, claude, target, prebuilder);
    }

    private static WorkerTaskResponse task(WorkerTaskResponse.PrebuildSpec prebuild, List<EnvVar> env,
                                           DeployOverrides overrides) {
        return new WorkerTaskResponse(9L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DEPLOY, List.of(), null, null, "netismaker/task-9-add-login",
                "abc1234def", env, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), prebuild,
                List.of(), List.of(), null, null, overrides);
    }

    private static DeployOverrides filesOverride() {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("jdbc.url", "jdbc:mariadb://db:3306/x");
        entries.put("jdbc.password", "s3cr3t-pw");
        return new DeployOverrides(List.of(new EnvVar("DB_HOST", "db", false)),
                List.of(new DeployOverrides.FileOverride(ENTRY, entries)),
                "주 DB = 개발 DB (db:3306)", null);
    }

    private Path stageWar(String entryName) throws Exception {
        Path dir = Files.createDirectories(wt.toPath().resolve(".netis-prebuild"));
        Path war = dir.resolve("app.war");
        try (OutputStream os = Files.newOutputStream(war); ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry(entryName));
            z.write("jdbc.url=old\n".getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        return war;
    }

    private void prebuilderStages(String fileName) throws Exception {
        when(prebuilder.run(eq(SPEC), any(), any()))
                .thenReturn(new DeployPrebuilder.Staged(fileName, 10, "target/" + fileName));
    }

    private void claudeWritesDockerfile(String content) throws Exception {
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean())).thenAnswer(inv -> {
            File dir = inv.getArgument(1);
            Files.writeString(dir.toPath().resolve("Dockerfile"), content);
            return new ClaudeExecAdapter.ExecResult(0, "ok", 10, null);
        });
    }

    private Path overrideRoot() {
        return reposDir.resolveSibling("deploy-override");
    }

    private void assertFailure(DeployOverrides o, WorkerTaskResponse.PrebuildSpec prebuild, String message) {
        assertThatThrownBy(() -> service().deploy(task(prebuild, List.of(), o), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(message));
        try {
            verify(target, never()).deploy(any(), any());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(overrideRoot()).doesNotExist();
    }

    @Test
    void error면_fetch전에_실패() throws Exception {
        assertThatThrownBy(() -> service().deploy(
                task(null, List.of(), DeployOverrides.failed("연결을 찾을 수 없습니다")), sink::add))
                .isInstanceOfSatisfying(DeployService.DeployException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("연결을 찾을 수 없습니다"));
        verify(repos, never()).fetchOnly(any(), anyString());
        verify(target, never()).deploy(any(), any());
    }

    @Test
    void 덮어쓰기_없으면_기존과_같다() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");

        var result = service().deploy(task(null, List.of(new EnvVar("A", "1", false)), null), sink::add);

        ArgumentCaptor<DeployTarget.DeploySpec> spec = ArgumentCaptor.forClass(DeployTarget.DeploySpec.class);
        verify(target).deploy(spec.capture(), any());
        assertThat(spec.getValue().env()).containsExactly(Map.entry("A", "1"));
        assertThat(spec.getValue().overrideDir()).isNull();
        assertThat(result.log()).doesNotContain("[DB 연결]").doesNotContain("[설정 덮어쓰기]");
        assertThat(overrideRoot()).doesNotExist();
    }

    @Test
    void env는_overrides것을_쓴다() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");
        var o = new DeployOverrides(List.of(new EnvVar("DB_HOST", "db", false)), List.of(), null, null);

        service().deploy(task(null, List.of(new EnvVar("A", "1", false)), o), sink::add);

        ArgumentCaptor<DeployTarget.DeploySpec> spec = ArgumentCaptor.forClass(DeployTarget.DeploySpec.class);
        verify(target).deploy(spec.capture(), any());
        assertThat(spec.getValue().env()).containsExactly(Map.entry("DB_HOST", "db"));
        assertThat(spec.getValue().overrideDir()).isNull();
    }

    @Test
    void 파일이면_사본을_쓰고_spec에_폴더() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        claudeWritesDockerfile(TOMCAT_DOCKERFILE);
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<Path> dir = new AtomicReference<>();
        when(target.deploy(any(), any())).thenAnswer(inv -> {
            DeployTarget.DeploySpec s = inv.getArgument(0);
            dir.set(s.overrideDir());
            seen.set(Files.readString(s.overrideDir().resolve(ENTRY), StandardCharsets.UTF_8));
            return new DeployTarget.DeployResult("http://h:19000", "cid", 19000, "img", "[target]\n");
        });

        var result = service().deploy(task(SPEC, List.of(), filesOverride()), sink::add);

        assertThat(seen.get()).startsWith("jdbc.url=old\n").contains("jdbc.password=s3cr3t-pw");
        assertThat(dir.get()).isEqualTo(overrideRoot().resolve("task-9"));
        assertThat(dir.get().startsWith(wt.toPath())).isFalse();   // 빌드 컨텍스트 밖
        String dockerfile = Files.readString(wt.toPath().resolve("Dockerfile"));
        assertThat(dockerfile).contains("/opt/netis-override/WEB-INF/classes");
        assertThat(result.log()).contains("[설정 덮어쓰기] tomcat 8 블록 추가")
                .contains("[DB 연결] 주 DB = 개발 DB (db:3306)")
                .doesNotContain("s3cr3t-pw");
        assertThat(sink).noneMatch(l -> l.contains("s3cr3t-pw"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(claude).exec(prompt.capture(), eq(wt), any(), anyList(), eq(true));
        assertThat(prompt.getValue()).endsWith(DeployService.CONFIG_OVERRIDE_RULE);
    }

    @Test
    void 배포가_끝나면_사본폴더를_지운다_성공() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        claudeWritesDockerfile(TOMCAT_DOCKERFILE);

        service().deploy(task(SPEC, List.of(), filesOverride()), sink::add);

        assertThat(overrideRoot()).doesNotExist();
    }

    @Test
    void 배포가_끝나면_사본폴더를_지운다_실패() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        claudeWritesDockerfile(TOMCAT_DOCKERFILE);
        AtomicReference<Path> dir = new AtomicReference<>();
        when(target.deploy(any(), any())).thenAnswer(inv -> {
            DeployTarget.DeploySpec s = inv.getArgument(0);
            dir.set(s.overrideDir());
            assertThat(s.overrideDir().resolve(ENTRY)).exists();
            throw new com.hamonsoft.netismaker.workerdaemon.deploy.DeployFailedException("빌드 실패", "log");
        });

        assertThatThrownBy(() -> service().deploy(task(SPEC, List.of(), filesOverride()), sink::add))
                .isInstanceOf(DeployService.DeployException.class);

        assertThat(dir.get()).doesNotExist();
        assertThat(overrideRoot()).doesNotExist();
    }

    @Test
    void 미리빌드_없으면_실패() {
        assertFailure(filesOverride(), null, "설정 파일 덮어쓰기는 미리 빌드가 켜진 레포에서만 됩니다");
    }

    @Test
    void war가_아니면_실패() throws Exception {
        prebuilderStages("app.jar");
        assertFailure(filesOverride(), SPEC, "설정 파일 덮어쓰기는 WAR 산출물에만 됩니다: app.jar");
    }

    @Test
    void 미리빌드가_남긴_Dockerfile도_레포의_것으로_센다() throws Exception {
        when(prebuilder.run(eq(SPEC), any(), any())).thenAnswer(inv -> {
            Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");
            return new DeployPrebuilder.Staged("app.war", 10, "target/app.war");
        });

        var result = service().deploy(task(SPEC, List.of(), null), sink::add);

        verifyNoInteractions(claude);   // 미리 빌드가 만든 Dockerfile이 있으면 claude가 새로 만들지 않는다(옛 동작)
        assertThat(result.log()).doesNotContain("[Dockerfile 없음");
    }

    @Test
    void 레포_Dockerfile이면_실패() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        Files.writeString(wt.toPath().resolve("Dockerfile"), TOMCAT_DOCKERFILE);
        assertFailure(filesOverride(), SPEC,
                "레포에 Dockerfile이 있어 설정 파일 덮어쓰기를 붙이지 않습니다 — 배포 DB를 '고르지 않음'으로 바꾸거나 레포 Dockerfile을 빼세요");
        verifyNoInteractions(claude);
    }

    @Test
    void tomcat이_아니면_실패() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        claudeWritesDockerfile("FROM eclipse-temurin:17\nCOPY .netis-prebuild/app.war /app.war\n");
        assertFailure(filesOverride(), SPEC, "설정 파일 덮어쓰기는 tomcat 이미지에서만 됩니다: eclipse-temurin:17");
    }

    @Test
    void 생성본이_context를_건드리면_실패() throws Exception {
        stageWar(ENTRY);
        prebuilderStages("app.war");
        claudeWritesDockerfile(TOMCAT_DOCKERFILE + "RUN sed -i 's/a/b/' $CATALINA_HOME/conf/context.xml\n");
        assertFailure(filesOverride(), SPEC, "생성한 Dockerfile이 Tomcat 설정을 직접 바꿨습니다 — 다시 배포하세요");
    }

    @Test
    void WAR에_파일없으면_실패() throws Exception {
        stageWar("WEB-INF/classes/other.properties");
        prebuilderStages("app.war");
        claudeWritesDockerfile(TOMCAT_DOCKERFILE);
        assertFailure(filesOverride(), SPEC, "WAR에 설정 파일이 없습니다: " + ENTRY);
    }

    @Test
    void 요약_로그줄() throws Exception {
        Files.writeString(wt.toPath().resolve("Dockerfile"), "FROM x\nEXPOSE 8080\n");
        var o = new DeployOverrides(List.of(new EnvVar("DB_PASSWORD", "topsecret", true)), List.of(),
                "주 DB = 개발 DB", null);

        var result = service().deploy(task(null, List.of(), o), sink::add);

        assertThat(result.log()).contains("[DB 연결] 주 DB = 개발 DB").doesNotContain("topsecret");
    }
}
