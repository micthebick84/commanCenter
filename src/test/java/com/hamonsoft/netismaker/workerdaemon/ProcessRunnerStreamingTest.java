package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerStreamingTest {

    @Test
    void runStreaming_invokes_callback_per_line_and_returns_full_output() throws Exception {
        List<String> lines = new ArrayList<>();
        ProcessRunner.Result r = ProcessRunner.runStreaming(
                new File("."), List.of("sh", "-c", "printf 'one\\ntwo\\nthree\\n'"), 10, lines::add);
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(lines).containsExactly("one", "two", "three");
        assertThat(r.stdout()).isEqualTo("one\ntwo\nthree\n");
    }
}
