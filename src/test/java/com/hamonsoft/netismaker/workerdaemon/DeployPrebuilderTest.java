package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse.PrebuildSpec;
import com.hamonsoft.netismaker.util.StackSecretEnv;
import com.hamonsoft.netismaker.workerdaemon.DeployPrebuilder.PrebuildException;
import com.hamonsoft.netismaker.workerdaemon.DeployPrebuilder.Staged;
import com.hamonsoft.netismaker.workerdaemon.PrebuildProperties.Toolchain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeployPrebuilderTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path root;

    private static DeployPrebuilder prebuilder(Duration timeout, Map<String, Toolchain> toolchains) {
        return new DeployPrebuilder(new PrebuildProperties(timeout, toolchains));
    }

    /** t/a.war를 만드는 OS별 셸 명령 본문(shellCommand가 cmd /c · sh -c로 감싼다). */
    private static String makeWarCommand() {
        return WINDOWS ? "mkdir t& echo war> t\\a.war& echo built" : "mkdir -p t && echo war > t/a.war && echo built";
    }

    @Test
    void shellCommand_OS별() {
        assertThat(DeployPrebuilder.shellCommand("mvn package", true))
                .containsExactly("cmd.exe", "/d", "/s", "/c", "\"mvn package\"");
        // 앞뒤가 모두 따옴표인 명령도 바깥 한 쌍을 더 씌운다(cmd /s가 그 쌍만 벗긴다)
        assertThat(DeployPrebuilder.shellCommand("\"C:\\x\\where.exe\" \"cmd.exe\"", true))
                .containsExactly("cmd.exe", "/d", "/s", "/c", "\"\"C:\\x\\where.exe\" \"cmd.exe\"\"");
        assertThat(DeployPrebuilder.shellCommand("mvn package", false))
                .containsExactly("sh", "-c", "mvn package");
        assertThat(DeployPrebuilder.shellCommand("\"a\" \"b\"", false))
                .containsExactly("sh", "-c", "\"a\" \"b\"");
    }

    @Test
    void run_앞뒤_따옴표_명령이_Windows에서_그대로_돈다() throws Exception {
        assumeTrue(WINDOWS, "Windows cmd 따옴표 규칙");
        String where = System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\where.exe";
        // 첫 글자와 끝 글자가 모두 " — 예전에는 cmd /s가 명령 자체의 따옴표를 벗겨 깨졌다
        String cmd = "\"" + where + "\" \"cmd.exe\"";
        List<String> sink = new CopyOnWriteArrayList<>();
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/a.war"), "x");

        prebuilder(Duration.ofMinutes(1), Map.of()).run(new PrebuildSpec(cmd, "t/a.war", null), root, sink::add);

        assertThat(sink).anySatisfy(line -> assertThat(line.toLowerCase(Locale.ROOT)).endsWith("\\cmd.exe"));
    }

    @Test
    void toolchainEnv_PATH_앞붙임() {
        Map<String, String> env = DeployPrebuilder.toolchainEnv(new Toolchain("C:/jdk8", "C:/mvn/bin"), "X", ";");
        assertThat(env).containsEntry("JAVA_HOME", "C:/jdk8")
                .containsEntry("PATH", "C:/jdk8/bin;C:/mvn/bin;X")
                .hasSize(2);
    }

    @Test
    void toolchainEnv_여러_조각_빈_조각_무시() {
        Map<String, String> env = DeployPrebuilder.toolchainEnv(new Toolchain("/opt/jdk8", "/opt/mvn/bin: :/opt/x"), "/usr/bin", ":");
        assertThat(env.get("PATH")).isEqualTo("/opt/jdk8/bin:/opt/mvn/bin:/opt/x:/usr/bin");
    }

    @Test
    void toolchainEnv_역슬래시_javaHome은_역슬래시_bin() {
        Map<String, String> env = DeployPrebuilder.toolchainEnv(new Toolchain("C:\\jdk8\\", null), null, ";");
        assertThat(env.get("PATH")).isEqualTo("C:\\jdk8\\bin");
    }

    @Test
    void configured_javaHome_빈값은_없음() {
        PrebuildProperties props = new PrebuildProperties(null, Map.of(
                "jdk8-maven", new Toolchain("  ", "C:/mvn/bin"),
                "jdk11", new Toolchain("C:/jdk11", null)));
        assertThat(props.configured("jdk8-maven")).isEmpty();
        assertThat(props.configured("없는도구")).isEmpty();
        assertThat(props.configured(null)).isEmpty();
        assertThat(props.configured("jdk11")).contains(new Toolchain("C:/jdk11", null));
    }

    @Test
    void properties_기본값() {
        PrebuildProperties props = new PrebuildProperties(null, null);
        assertThat(props.timeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(props.toolchains()).isEmpty();
    }

    @Test
    void properties_yml_바인딩() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"));
        loaded.forEach(ps -> env.getPropertySources().addLast(ps));
        env.getPropertySources().addFirst(new MapPropertySource("test-env", Map.of(
                "PREBUILD_JDK8_JAVA_HOME", "C:/jdk8",
                "PREBUILD_JDK8_PATH", "C:/mvn/bin")));

        PrebuildProperties props = Binder.get(env)
                .bind("netis-maker.worker.deploy.prebuild", PrebuildProperties.class).get();
        assertThat(props.timeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(props.configured("jdk8-maven")).contains(new Toolchain("C:/jdk8", "C:/mvn/bin"));

        // 같은 yml에서 기존 Deploy 레코드도 계속 바인딩된다(prebuild 하위 키는 무시)
        WorkerProperties wp = Binder.get(env).bind("netis-maker.worker", WorkerProperties.class).get();
        assertThat(wp.deploy().buildTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    // ── resolveArtifact ──

    @Test
    void resolveArtifact_하나() throws Exception {
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/a.war"), "x");
        Files.writeString(root.resolve("t/a.txt"), "x");
        assertThat(DeployPrebuilder.resolveArtifact(root, "t/a.war")).isEqualTo(root.resolve("t/a.war"));
        assertThat(DeployPrebuilder.resolveArtifact(root, "t/*.war")).isEqualTo(root.resolve("t/a.war"));
    }

    @Test
    void resolveArtifact_역슬래시_패턴() throws Exception {
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/a.war"), "x");
        assertThat(DeployPrebuilder.resolveArtifact(root, "t\\*.war")).isEqualTo(root.resolve("t/a.war"));
    }

    @Test
    void resolveArtifact_루트_바로_아래() throws Exception {
        Files.writeString(root.resolve("app.jar"), "x");
        assertThat(DeployPrebuilder.resolveArtifact(root, "*.jar")).isEqualTo(root.resolve("app.jar"));
    }

    @Test
    void resolveArtifact_없음() throws Exception {
        Files.createDirectories(root.resolve("t"));
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "t/*.war"))
                .isInstanceOf(PrebuildException.class)
                .hasMessage("미리 빌드 산출물 없음: t/*.war");
        // 상위 폴더 자체가 없어도 같은 사유
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "nope/*.war"))
                .isInstanceOf(PrebuildException.class)
                .hasMessage("미리 빌드 산출물 없음: nope/*.war");
    }

    @Test
    void resolveArtifact_여러개() throws Exception {
        Files.createDirectories(root.resolve("t"));
        for (int i = 1; i <= 6; i++) Files.writeString(root.resolve("t/a" + i + ".war"), "x");
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "t/*.war"))
                .isInstanceOf(PrebuildException.class)
                .hasMessage("미리 빌드 산출물이 여러 개: a1.war, a2.war, a3.war, a4.war, a5.war, …");
    }

    @Test
    void resolveArtifact_두개는_말줄임_없음() throws Exception {
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/a.war"), "x");
        Files.writeString(root.resolve("t/a-sources.war"), "x");
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "t/a*.war"))
                .hasMessage("미리 빌드 산출물이 여러 개: a-sources.war, a.war");
    }

    @Test
    void resolveArtifact_폴더와_파일이_함께_맞으면_파일만() throws Exception {
        Files.createDirectories(root.resolve("t/hm-dash"));
        Files.writeString(root.resolve("t/hm-dash.war"), "x");
        assertThat(DeployPrebuilder.resolveArtifact(root, "t/hm-dash*")).isEqualTo(root.resolve("t/hm-dash.war"));
    }

    @Test
    void resolveArtifact_폴더만_맞으면() throws Exception {
        Files.createDirectories(root.resolve("t/hm-dash"));
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "t/hm-dash*"))
                .isInstanceOf(PrebuildException.class)
                .hasMessage("미리 빌드 산출물이 일반 파일이 아님: t/hm-dash*");
    }

    @Test
    void resolveArtifact_루트_밖으로_나가는_경로는_없음() throws Exception {
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root.resolve("sub"), "../*"))
                .isInstanceOf(PrebuildException.class)
                .hasMessageStartingWith("미리 빌드 산출물 없음: ");
    }

    @Test
    void resolveArtifact_Windows에서_쓸_수_없는_상위_조각은_없음() {
        assumeTrue(WINDOWS, "?는 Windows 경로에서만 InvalidPathException");
        assertThatThrownBy(() -> DeployPrebuilder.resolveArtifact(root, "t?/*.war"))
                .isInstanceOf(PrebuildException.class)
                .hasMessage("미리 빌드 산출물 없음: t?/*.war");
    }

    // ── 프롬프트·Dockerfile 검사 ──

    @Test
    void promptSection_본문() {
        String s = DeployPrebuilder.promptSection("hm-dash.war", "hm-dash/target/*.war");
        // 첫 항목 = 우선순위 — 생성 프롬프트 템플릿의 멀티스테이지 지침보다 앞선다
        assertThat(s.lines().skip(1).findFirst())
                .contains("- 이 절은 위의 Dockerfile 작성 지침(멀티스테이지 빌드 등)보다 우선합니다.");
        assertThat(s).startsWith("## 미리 빌드된 산출물 (필수)\n")
                .contains("`.netis-prebuild/hm-dash.war`")
                .contains("`hm-dash/target/*.war`")
                .contains("ROOT.war")
                .contains("jar")
                .doesNotContain("<이름>")
                .doesNotContain("<산출물 경로>");
    }

    @Test
    void dockerfileUsesStage() {
        assertThat(DeployPrebuilder.dockerfileUsesStage("FROM tomcat\nCOPY .netis-prebuild/a.war /x\n")).isTrue();
        assertThat(DeployPrebuilder.dockerfileUsesStage("FROM tomcat\nCOPY target/a.war /x\n")).isFalse();
        assertThat(DeployPrebuilder.dockerfileUsesStage(null)).isFalse();
    }

    @Test
    void dockerignore_상수() {
        assertThat(DeployPrebuilder.STAGE_DIR).isEqualTo(".netis-prebuild");
        assertThat(DeployPrebuilder.DOCKERIGNORE).isEqualTo("*\n!.netis-prebuild/\n!.netis-prebuild/**\n");
    }

    // ── run (실제 프로세스) ──

    @Test
    void run_성공() throws Exception {
        List<String> sink = new CopyOnWriteArrayList<>();
        Staged staged = prebuilder(Duration.ofMinutes(1), Map.of())
                .run(new PrebuildSpec(makeWarCommand(), "t/*.war", null), root, sink::add);

        Path stagedFile = root.resolve(".netis-prebuild/a.war");
        assertThat(stagedFile).isRegularFile();
        assertThat(staged.fileName()).isEqualTo("a.war");
        assertThat(staged.sourceRelative()).isEqualTo("t/a.war");
        assertThat(staged.sizeBytes()).isEqualTo(Files.size(stagedFile)).isPositive();
        assertThat(sink.get(0)).isEqualTo("[미리 빌드] $ " + makeWarCommand() + " (도구=워커 env)");
        assertThat(sink).contains("built");
        assertThat(sink.get(sink.size() - 1))
                .startsWith("[미리 빌드 산출물] t/a.war (")
                .endsWith("MB) → .netis-prebuild/a.war");
    }

    @Test
    void run_도구_이름을_로그에() throws Exception {
        String javaHome = System.getProperty("java.home");
        List<String> sink = new CopyOnWriteArrayList<>();
        prebuilder(Duration.ofMinutes(1), Map.of("jdk8-maven", new Toolchain(javaHome, null)))
                .run(new PrebuildSpec(makeWarCommand(), "t/a.war", "jdk8-maven"), root, sink::add);
        assertThat(sink.get(0)).endsWith(" (도구=jdk8-maven)");
    }

    @Test
    void run_툴체인_env가_자식에_실제로_들어간다() throws Exception {
        String javaHome = root.resolve("fake-jdk").toString();
        String mvnBin = root.resolve("fake-mvn").resolve("bin").toString();
        String cmd = WINDOWS
                ? "echo JH=%JAVA_HOME%& echo P=%PATH%& " + makeWarCommand()
                : "echo JH=$JAVA_HOME; echo P=$PATH; " + makeWarCommand();
        List<String> sink = new CopyOnWriteArrayList<>();
        prebuilder(Duration.ofMinutes(1), Map.of("jdk8-maven", new Toolchain(javaHome, mvnBin)))
                .run(new PrebuildSpec(cmd, "t/a.war", "jdk8-maven"), root, sink::add);

        assertThat(sink).contains("JH=" + javaHome);
        String bin = javaHome + (WINDOWS ? "\\bin" : "/bin");
        assertThat(sink).anySatisfy(line ->
                assertThat(line).startsWith("P=" + bin + java.io.File.pathSeparator + mvnBin + java.io.File.pathSeparator));
    }

    @Test
    void run_비밀env_제거() {
        assertThat(DeployPrebuilder.removedEnv()).containsExactlyInAnyOrderElementsOf(StackSecretEnv.NAMES);
    }

    @Test
    void run_실패_exit() {
        String cmd = WINDOWS ? "echo failing-output& exit /b 3" : "echo failing-output; exit 3";
        List<String> sink = new CopyOnWriteArrayList<>();
        PrebuildException e = catchThrowableOfType(PrebuildException.class,
                () -> prebuilder(Duration.ofMinutes(1), Map.of())
                        .run(new PrebuildSpec(cmd, "t/*.war", null), root, sink::add));
        assertThat(e).hasMessage("미리 빌드 실패 (exit=3)");
        assertThat(e.getLogTail().lines()).contains("failing-output");   // 머리줄(명령 원문)이 아니라 출력 줄
        // 머리줄은 화면(sink)에만 — 꼬리에도 있으면 deploy_log에 두 번 적힌다
        assertThat(sink.get(0)).startsWith("[미리 빌드] $ ");
        assertThat(e.getLogTail()).doesNotContain("[미리 빌드] $");
    }

    @Test
    void run_시간초과() {
        String cmd = WINDOWS ? "echo before-sleep& ping -n 8 127.0.0.1 >nul" : "echo before-sleep; sleep 8";
        long started = System.nanoTime();
        PrebuildException e = catchThrowableOfType(PrebuildException.class,
                () -> prebuilder(Duration.ofSeconds(2), Map.of())
                        .run(new PrebuildSpec(cmd, "t/*.war", null), root, line -> {}));
        assertThat(e.getMessage()).startsWith("미리 빌드 시간 초과(");
        assertThat(e.getLogTail().lines()).contains("before-sleep");   // 시간 초과 때도 sleep 전 출력이 꼬리에
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void run_도구_미설정() {
        PrebuildException e = catchThrowableOfType(PrebuildException.class,
                () -> prebuilder(Duration.ofMinutes(1), Map.of())
                        .run(new PrebuildSpec("mvn package", "t/*.war", "jdk8-maven"), root, line -> {}));
        assertThat(e).hasMessage("미리 빌드 도구 'jdk8-maven'가 이 워커에 설정되지 않음");
        assertThat(root.resolve(DeployPrebuilder.STAGE_DIR)).doesNotExist();
    }

    @Test
    void run_산출물_없으면_사유와_꼬리() {
        String cmd = "echo nothing-built";
        PrebuildException e = catchThrowableOfType(PrebuildException.class,
                () -> prebuilder(Duration.ofMinutes(1), Map.of())
                        .run(new PrebuildSpec(cmd, "t/*.war", null), root, line -> {}));
        assertThat(e).hasMessage("미리 빌드 산출물 없음: t/*.war");
        assertThat(e.getLogTail().lines()).contains("nothing-built");
    }

    @Test
    void logTail_끝_8000자() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("line-").append(i).append('\n');
        String tail = DeployPrebuilder.tail(sb);
        // LocalDockerTarget의 build 실패 꼬리와 같은 규칙: 잘렸으면 "…" + 끝 8000자
        assertThat(tail).hasSize(8001).startsWith("…").endsWith("line-1999\n");
        assertThat(DeployPrebuilder.tail(new StringBuilder("short"))).isEqualTo("short");
    }
}
