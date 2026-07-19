package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignResultHarvesterTest {

    private final DesignResultHarvester harvester = new DesignResultHarvester();

    private void write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void 정상_산출물을_수확한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/DESIGN.md", "# 디자인 문서");
        write(wt, ".design-out/screens/main.html", "<html>메인</html>");
        write(wt, ".design-out/result.json",
                "{\"screens\":[{\"path\":\"screens/main.html\",\"title\":\"메인 화면\"}]," +
                "\"designProjectId\":\"p1\",\"designUrl\":\"https://u\"}");

        DesignResultHarvester.HarvestResult r = harvester.harvest(wt.toFile());

        assertThat(r.designMarkdown()).isEqualTo("# 디자인 문서");
        assertThat(r.designProjectId()).isEqualTo("p1");
        assertThat(r.mockupFilesJson()).contains("\"title\":\"메인 화면\"")
                .contains("<html>메인</html>");
    }

    @Test
    void result_json이_없어도_screens_디렉토리에서_수확한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/DESIGN.md", "# D");
        write(wt, ".design-out/screens/a.html", "<html>a</html>");
        DesignResultHarvester.HarvestResult r = harvester.harvest(wt.toFile());
        assertThat(r.designProjectId()).isNull();
        assertThat(r.mockupFilesJson()).contains("screens/a.html");
    }

    @Test
    void ERROR_txt가_있으면_사유와_함께_실패한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/ERROR.txt", "DesignSync 인증 실패");
        assertThatThrownBy(() -> harvester.harvest(wt.toFile()))
                .isInstanceOf(DesignResultHarvester.HarvestException.class)
                .hasMessageContaining("DesignSync 인증 실패");
    }

    @Test
    void DESIGN_md_없으면_실패한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/screens/a.html", "<html></html>");
        assertThatThrownBy(() -> harvester.harvest(wt.toFile()))
                .isInstanceOf(DesignResultHarvester.HarvestException.class);
    }
}
