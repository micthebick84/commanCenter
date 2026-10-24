package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessRunnerStreamingTest {

    /** sh가 PATH에 없는 환경(cmd에서 돌린 Windows gradle)이면 건너뛴다 — CI(Linux)·Git Bash에서는 돈다. */
    private static boolean shOnPath() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, "sh").isFile() || new File(dir, "sh.exe").isFile()) return true;
        }
        return false;
    }

    @Test
    void runStreaming_invokes_callback_per_line_and_returns_full_output() throws Exception {
        assumeTrue(shOnPath(), "sh가 PATH에 없는 환경");
        List<String> lines = new ArrayList<>();
        ProcessRunner.Result r = ProcessRunner.runStreaming(
                new File("."), List.of("sh", "-c", "printf 'one\\ntwo\\nthree\\n'"), 10, lines::add);
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(lines).containsExactly("one", "two", "three");
        assertThat(r.stdout()).isEqualTo("one\ntwo\nthree\n");
    }
}
