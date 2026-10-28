package com.hamonsoft.netismaker.service.render;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SofficeLocatorTest {

    private static final Path WIN_DEFAULT = Path.of("C:\\Program Files\\LibreOffice\\program\\soffice.com");

    @Test
    void 설정_경로가_있고_존재하면_가장_먼저_쓴다() {
        Path configured = Path.of("D:/office/soffice.exe");
        var found = SofficeLocator.resolve(configured.toString(), "Windows 10",
                p -> Set.of(configured, WIN_DEFAULT).contains(p), "");
        assertThat(found).contains(configured);
    }

    @Test
    void 설정이_비었으면_Windows_기본_위치() {
        var found = SofficeLocator.resolve("", "Windows 10", WIN_DEFAULT::equals, "");
        assertThat(found).contains(WIN_DEFAULT);
    }

    @Test
    void 비Windows는_기본_위치를_보지_않고_PATH만_본다() {
        Path inPath = Path.of("/usr/bin", "soffice");
        String pathEnv = "/opt/none" + File.pathSeparator + "/usr/bin";
        var found = SofficeLocator.resolve(null, "Linux", p -> p.equals(inPath) || p.equals(WIN_DEFAULT), pathEnv);
        assertThat(found).contains(inPath);
    }

    @Test
    void PATH에서_soffice_com도_찾는다() {
        Path com = Path.of("/x", "soffice.com");
        var found = SofficeLocator.resolve(null, "Linux", com::equals, "/x");
        assertThat(found).contains(com);
    }

    @Test
    void 아무것도_없으면_empty() {
        assertThat(SofficeLocator.resolve("", "Windows 10", p -> false, "/a" + File.pathSeparator + "/b")).isEmpty();
        assertThat(SofficeLocator.resolve(null, "Linux", p -> false, null)).isEmpty();
    }

    @Test
    void 설정_경로가_존재하지_않으면_다음_후보로_넘어간다() {
        var found = SofficeLocator.resolve("D:/missing/soffice", "Windows 10", WIN_DEFAULT::equals, "");
        assertThat(found).contains(WIN_DEFAULT);
    }
}
