package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.util.StackSecretEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBuilderStarterTest {

    @TempDir
    Path tmp;

    @Test
    void soffice_자식_env에서_스택_비밀을_뺀다() {
        Map<String, String> parent = new HashMap<>();
        for (String name : StackSecretEnv.NAMES) parent.put(name, "secret");
        parent.put("mail_password", "secret");
        parent.put("PATH", "C:/bin");
        parent.put("SOFFICE_PATH", "C:/lo/soffice.com");

        ProcessBuilder pb = ProcessBuilderStarter.builder(List.of("soffice", "--headless"), tmp, parent);

        assertThat(pb.environment().keySet())
                .noneMatch(k -> StackSecretEnv.NAMES.stream().anyMatch(k::equalsIgnoreCase));
        assertThat(pb.environment()).containsEntry("PATH", "C:/bin").containsEntry("SOFFICE_PATH", "C:/lo/soffice.com");
        assertThat(pb.directory()).isEqualTo(tmp.toFile());
        assertThat(pb.command()).containsExactly("soffice", "--headless");
    }
}
