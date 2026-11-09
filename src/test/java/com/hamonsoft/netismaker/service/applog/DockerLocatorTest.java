package com.hamonsoft.netismaker.service.applog;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DockerLocatorTest {

    private static final String WIN_HOME = "C:\\Users\\mic";
    private static final Path WIN_LOCAL_BIN = Path.of(WIN_HOME, ".local", "bin", "docker.exe");

    @Test
    void 설정값이_있으면_그것() {
        Path configured = Path.of("D:/tools/docker.exe");
        var found = DockerLocator.resolve(configured.toString(), "Windows 11",
                p -> Set.of(configured, WIN_LOCAL_BIN).contains(p), "", WIN_HOME);
        assertThat(found).contains(configured);
    }

    @Test
    void 설정_경로가_존재하지_않으면_다음_후보로_넘어간다() {
        var found = DockerLocator.resolve("D:/missing/docker.exe", "Windows 11", WIN_LOCAL_BIN::equals, "", WIN_HOME);
        assertThat(found).contains(WIN_LOCAL_BIN);
    }

    @Test
    void 윈도우는_PATH의_docker_exe를_먼저_본다() {
        // 드라이브 문자(C:)를 쓰지 않는다 — Linux CI에서는 PATH 구분자가 ':'라 "C:/bin"이 "C"와 "/bin"으로 쪼개진다
        Path inPath = Path.of("/bin", "docker.exe");
        var found = DockerLocator.resolve(null, "Windows 11", p -> p.equals(inPath) || p.equals(WIN_LOCAL_BIN),
                "/none" + File.pathSeparator + "/bin", WIN_HOME);
        assertThat(found).contains(inPath);
    }

    @Test
    void 윈도우는_PATH의_docker_exe_다음_로컬_bin() {
        // PATH에 없고 ~\.local\bin\docker.exe만 있다 — 예약 작업으로 뜬 API가 그 폴더를 PATH에 갖는지 확인되지 않았다
        var found = DockerLocator.resolve("", "Windows 11", WIN_LOCAL_BIN::equals, "C:/none", WIN_HOME);
        assertThat(found).contains(Path.of(WIN_HOME, ".local", "bin", "docker.exe"));
    }

    @Test
    void 윈도우는_확장자_없는_docker는_PATH에서_찾지_않는다() {
        // PATH를 드라이브 문자 없이 "/bin" 하나로 — Linux에서도 "/bin"으로 제대로 쪼개져 docker.exe만 후보가 된다
        Path bare = Path.of("/bin", "docker");
        assertThat(DockerLocator.resolve(null, "Windows 11", bare::equals, "/bin", WIN_HOME)).isEmpty();
    }

    @Test
    void 리눅스는_PATH의_docker() {
        Path inPath = Path.of("/usr/bin", "docker");
        String pathEnv = "/opt/none" + File.pathSeparator + "/usr/bin";
        var found = DockerLocator.resolve(null, "Linux", inPath::equals, pathEnv, "/home/u");
        assertThat(found).contains(inPath);
    }

    @Test
    void 리눅스는_docker_exe나_로컬_bin을_보지_않는다() {
        Path exe = Path.of("/usr/bin", "docker.exe");
        Path localBin = Path.of("/home/u", ".local", "bin", "docker.exe");
        assertThat(DockerLocator.resolve(null, "Linux", p -> p.equals(exe) || p.equals(localBin), "/usr/bin", "/home/u"))
                .isEmpty();
    }

    @Test
    void 못_찾으면_empty() {
        assertThat(DockerLocator.resolve("", "Windows 11", p -> false, "/a" + File.pathSeparator + "/b", WIN_HOME)).isEmpty();
        assertThat(DockerLocator.resolve(null, "Linux", p -> false, null, null)).isEmpty();
        assertThat(DockerLocator.resolve(null, null, p -> false, "", "")).isEmpty();
    }

    @Test
    void 윈도우여도_userHome이_비면_로컬_bin은_건너뛴다() {
        assertThat(DockerLocator.resolve(null, "Windows 11", p -> true, "", null)).isEmpty();
        assertThat(DockerLocator.resolve(null, "Windows 11", p -> true, "", " ")).isEmpty();
    }

    @Test
    void 경로로_쓸_수_없는_PATH_항목이나_설정값은_건너뛴다() {
        // 기동 중 예외로 API가 못 뜨면 안 된다 — 기능만 꺼져야 한다. NUL 문자는 Windows·Linux 모두 경로로 만들 수 없다.
        Path good = Path.of("/x", "docker");
        var found = DockerLocator.resolve("bad\0path", "Linux", good::equals, "bad\0dir" + File.pathSeparator + "/x", "/home/u");
        assertThat(found).contains(good);
    }

    @Test
    void 빈_PATH_항목은_건너뛴다() {
        Path inPath = Path.of("/x", "docker");
        var found = DockerLocator.resolve(null, "Linux", inPath::equals, File.pathSeparator + " " + File.pathSeparator + "/x", "/h");
        assertThat(found).contains(inPath);
    }
}
