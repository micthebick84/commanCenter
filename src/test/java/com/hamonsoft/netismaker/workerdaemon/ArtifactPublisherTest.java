package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.dto.CloseArtifactSpec;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactPublisherTest {

    @TempDir Path tmp;
    private WorkerHttpClient http;
    private WorkerProperties props;
    private final List<List<String>> calls = new ArrayList<>();
    private String findOut = "";
    private int inspectExit = 0;
    private int cpExit = 0;

    private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";
    private static final byte[] WAR = "WAR-BYTES".getBytes();

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        props = mock(WorkerProperties.class);
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        when(http.currentArtifact(7L)).thenReturn(Optional.empty());
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(i -> new ArtifactInfo(i.getArgument(1), WAR.length, i.getArgument(4)));
    }

    private ArtifactPublisher publisher() {
        ArtifactProperties cfg = new ArtifactProperties(null, null, null);
        return new ArtifactPublisher(props, cfg, http, (args, timeout) -> {
            calls.add(List.copyOf(args));
            switch (args.get(0)) {
                case "image" -> { return new ProcessRunner.Result(inspectExit, inspectExit == 0 ? "sha256:x" : "No such image"); }
                case "run" -> { return new ProcessRunner.Result(1, findOut); }
                case "cp" -> {
                    if (cpExit == 0) Files.write(Path.of(args.get(2)), WAR);
                    return new ProcessRunner.Result(cpExit, cpExit == 0 ? "" : "Could not find the file");
                }
                default -> { return new ProcessRunner.Result(0, ""); }
            }
        });
    }

    private WorkerTaskResponse task(String path, String repo) {
        return new WorkerTaskResponse(7L, repo, "main", "t", "d", WorkerTaskResponse.Kind.CLOSE, List.of(), null, null,
                "netismaker/task-7", SHA, List.of(), null, null, null, null, null, null, null, null, null, "github",
                null, 3, "https://github.com/" + repo + "/pull/3", "MERGED,UNDEPLOYED", null, new CloseArtifactSpec(path));
    }

    @Test
    void 이미_보관돼_있으면_docker를_부르지_않는다() throws Exception {
        when(http.currentArtifact(7L)).thenReturn(Optional.of(new ArtifactInfo("w-task-7-abcdef1.war", 9, "ab")));
        ArtifactPublisher.Published p = publisher().publish(task(null, "acme/w"));
        assertThat(p.reused()).isTrue();
        assertThat(calls).isEmpty();
        verify(http, never()).uploadArtifact(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 카탈로그_경로면_find_없이_꺼내_올리고_임시_컨테이너를_지운다() throws Exception {
        ArtifactPublisher.Published p = publisher().publish(task("/usr/local/tomcat/webapps/ROOT.war", "acme/w"));

        assertThat(calls).containsExactly(
                List.of("image", "inspect", "--format", "{{.Id}}", "netis-task-7:abcdef1"),
                List.of("rm", "-f", "netis-artifact-7"),
                List.of("create", "--name", "netis-artifact-7", "--entrypoint", "true", "netis-task-7:abcdef1"),
                List.of("cp", "netis-artifact-7:/usr/local/tomcat/webapps/ROOT.war", calls.get(3).get(2)),
                List.of("rm", "-f", "netis-artifact-7"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WAR));
        verify(http).uploadArtifact(eq(7L), eq("w-task-7-abcdef1.war"),
                eq("netis-task-7:abcdef1:/usr/local/tomcat/webapps/ROOT.war"), any(Path.class), eq(sha),
                eq(Duration.ofMinutes(10)));
        assertThat(p.reused()).isFalse();
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
        assertThat(tmp.resolve("close/task-7")).doesNotExist();   // 빈 상위 폴더도 남기지 않는다
    }

    @Test
    void 경로가_없으면_find_결과가_하나일때_그것을_쓴다() throws Exception {
        findOut = "find: '/usr/local/tomcat/webapps': No such file or directory\n/app/netis.jar\n";
        publisher().publish(task(null, "micthebick84/netis-v7.0"));
        assertThat(calls.get(1)).containsExactly("run", "--rm", "--entrypoint", "find", "netis-task-7:abcdef1",
                "/usr/local/tomcat/webapps", "/app", "-maxdepth", "2", "-type", "f",
                "(", "-name", "*.war", "-o", "-name", "*.jar", ")");
        verify(http).uploadArtifact(eq(7L), eq("netis-v7.0-task-7-abcdef1.jar"), anyString(), any(), anyString(), any());
    }

    @Test
    void find_결과가_0개나_2개면_카탈로그_경로_지정을_안내한다() {
        findOut = "/app/a.jar\n/app/b.jar\n";
        assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
                .isInstanceOf(ArtifactPublisher.ArtifactException.class)
                .hasMessageContaining("레포 카탈로그").hasMessageContaining("/app/a.jar, /app/b.jar");
        findOut = "exec: \"find\": executable file not found";
        assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
                .hasMessageContaining("후보: 없음");
    }

    @Test
    void 이미지가_없으면_산출물_없이_마무리를_안내한다() {
        inspectExit = 1;
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .isInstanceOf(ArtifactPublisher.ArtifactException.class)
                .hasMessage("산출물: 이미지 없음(netis-task-7:abcdef1) — 재시도하거나 '산출물 없이 마무리'");
    }

    @Test
    void 꺼내기가_실패해도_임시_컨테이너는_지운다() {
        cpExit = 1;
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessageStartingWith("산출물: 이미지에서 꺼내기 실패(/app/x.war)");
        assertThat(calls.get(calls.size() - 1)).containsExactly("rm", "-f", "netis-artifact-7");
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
    }

    @Test
    void war나_jar가_아닌_경로는_거절한다() {
        assertThatThrownBy(() -> publisher().publish(task("/app/x.ear", "acme/w")))
                .hasMessageContaining(".war 또는 .jar");
    }

    @Test
    void 파일_이름은_경로_마지막_조각을_정규화한다() {
        assertThat(ArtifactPublisher.fileName(task(null, "group/sub/my proj"), "/app/x.war"))
                .isEqualTo("my_proj-task-7-abcdef1.war");
        assertThat(ArtifactPublisher.candidates("x\n/a/b.war\n/a/b.war\n/c.txt\n")).containsExactly("/a/b.war");
    }

    @Test
    void 업로드_중_연결이_끊기면_종료실패_사유로_쓸_메시지로_바꾸고_작업_폴더를_지운다() throws Exception {
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IOException("Broken pipe"));
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .isInstanceOf(ArtifactPublisher.ArtifactException.class)
                .hasMessage("산출물: 업로드 실패 — Broken pipe");
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
    }

    @Test
    void 업로드_예외에_메시지가_없어도_사유가_비지_않고_자격증명은_가린다() throws Exception {
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IOException())
                .thenThrow(new IOException("https://oauth2:tok123@git.local/x 실패"));
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessage("산출물: 업로드 실패 — IOException");
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessageContaining("***@").hasMessageNotContaining("tok123");
    }

    @Test
    void 업로드가_시간을_넘기면_초_단위_사유로_안내한다() throws Exception {
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new java.net.http.HttpTimeoutException("request timed out"));
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessage("산출물: 업로드 실패 — 시간 초과(600초)");
    }

    @Test
    void 연결_시간_초과는_업로드_시간_초과와_구분한다() throws Exception {
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"));
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessage("산출물: 업로드 실패 — API 서버 연결 시간 초과");
    }

    @Test
    void 같은_작업_폴더의_다른_파일은_건드리지_않는다() throws Exception {
        Path other = Files.createDirectories(tmp.resolve("close/task-7")).resolve("note.txt");
        Files.writeString(other, "노트 작업물");
        publisher().publish(task("/app/x.war", "acme/w"));
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
        assertThat(other).exists();   // 노트 단계가 쓰는 같은 폴더의 다른 파일은 건드리지 않는다
    }

    @Test
    void 이미지_태그_계산은_배포와_산출물이_같다() {
        assertThat(DeployService.shortSha(SHA)).isEqualTo("abcdef1");
        assertThat(DeployService.shortSha("abc")).isEqualTo("abc");
        assertThat(DeployService.shortSha(null)).isEqualTo("latest");
        assertThat(DeployService.imageName(7L, SHA)).isEqualTo("netis-task-7:abcdef1");
        assertThat(DeployService.imageName(7L, null)).isEqualTo("netis-task-7:latest");
    }
}
