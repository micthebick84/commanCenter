package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.dto.DeployOverrides.FileOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarPropertiesOverrideTest {

    @TempDir
    Path tmp;

    private static LinkedHashMap<String, String> map(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Properties load(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    private Path war(String entry, byte[] bytes) throws IOException {
        Path war = tmp.resolve("app.war");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(war))) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(bytes);
            z.closeEntry();
        }
        return war;
    }

    @Test
    void 원본_끝에_덧붙인다() {
        String original = "# 주석\njdbc.url=old\njdbc.username=a\n";
        String out = WarPropertiesOverride.append(original,
                map("jdbc.url", "new", "jdbc.username", "u", "jdbc.password", "p"));

        assertThat(out).startsWith(original);
        String[] lines = out.split("\n");
        assertThat(lines).contains("# 주석", "jdbc.url=old");
        assertThat(lines[lines.length - 4]).isEqualTo("# Symphony 배포 덮어쓰기");
        assertThat(lines[lines.length - 3]).isEqualTo("jdbc.url=new");
        assertThat(lines[lines.length - 2]).isEqualTo("jdbc.username=u");
        assertThat(lines[lines.length - 1]).isEqualTo("jdbc.password=p");
    }

    @Test
    void Properties로_읽으면_덮어쓴_값() throws IOException {
        String out = WarPropertiesOverride.append("jdbc.url=old\nx=1\n", map("jdbc.url", "new"));
        Properties p = load(out);
        assertThat(p.getProperty("jdbc.url")).isEqualTo("new");
        assertThat(p.getProperty("x")).isEqualTo("1");
    }

    @Test
    void 특수문자_비밀번호_왕복() throws IOException {
        String pw = "a=b:c\\d#e! f@g%h한\t";
        String out = WarPropertiesOverride.append("", map("jdbc.password", pw, "k ey=:#!", " lead"));
        Properties p = load(out);
        assertThat(p.getProperty("jdbc.password")).isEqualTo(pw);
        assertThat(p.getProperty("k ey=:#!")).isEqualTo(" lead");
        // 블록의 값 줄은 순수 ASCII
        String block = out.substring(out.indexOf("jdbc.password"));
        assertThat(block.chars().allMatch(c -> c < 0x7F)).isTrue();
    }

    @Test
    void 원본이_줄바꿈없이_끝나도() throws IOException {
        String out = WarPropertiesOverride.append("a=1", map("b", "2"));
        assertThat(out).isEqualTo("a=1\n# Symphony 배포 덮어쓰기\nb=2\n");
        assertThat(load(out).getProperty("a")).isEqualTo("1");
    }

    @Test
    void WAR에_없는_파일은_NoSuchFile() throws IOException {
        Path war = war("WEB-INF/classes/a.properties", "a=1".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> WarPropertiesOverride.readEntry(war, "WEB-INF/classes/none.properties"))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("WEB-INF/classes/none.properties");
    }

    @Test
    void writeOverrides가_경로대로_쓴다() throws IOException {
        byte[] orig = "name=한글\njdbc.url=old\n".getBytes(StandardCharsets.UTF_8);
        Path war = war("WEB-INF/classes/config/jdbc_maria.properties", orig);
        Path root = tmp.resolve("override");

        WarPropertiesOverride.writeOverrides(war,
                List.of(new FileOverride("WEB-INF/classes/config/jdbc_maria.properties", map("jdbc.url", "new"))), root);

        Path target = root.resolve("WEB-INF/classes/config/jdbc_maria.properties");
        byte[] written = Files.readAllBytes(target);
        // 원본 바이트 그대로 + UTF-8 블록
        assertThat(written).startsWith(orig);
        String all = new String(written, StandardCharsets.UTF_8);
        assertThat(all).endsWith("# Symphony 배포 덮어쓰기\njdbc.url=new\n");
        Properties p = load(all);
        assertThat(p.getProperty("jdbc.url")).isEqualTo("new");
        assertThat(p.getProperty("name")).isEqualTo("한글");
    }

    @Test
    void 루트_밖으로_나가는_경로는_거부() throws IOException {
        Path war = war("WEB-INF/classes/a.properties", "a=1\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> WarPropertiesOverride.writeOverrides(war,
                List.of(new FileOverride("../evil.properties", map("a", "b"))),
                tmp.resolve("o")))
                .isInstanceOf(IOException.class);
    }
}
