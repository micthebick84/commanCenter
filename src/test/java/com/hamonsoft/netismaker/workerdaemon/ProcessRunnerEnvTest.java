package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ProcessRunner의 env 제거 오버로드 — 참조 repo git 호출이 운영자 PC의 askpass(GIT_ASKPASS·SSH_ASKPASS)를
 * 물려받지 않게 지운 뒤 넣는다(스펙 2026-10-24 §6.2). 기존 오버로드는 부모 env를 그대로 물려준다.
 *
 * 자식은 ProcessRunnerTimeoutTest처럼 JDK java로 띄우는 단일 파일 소스 — 인자로 받은 이름의 env를 출력한다(없으면 "null").
 */
class ProcessRunnerEnvTest {

    private static final String CHILD_SOURCE = """
            public class Child {
                public static void main(String[] a) {
                    System.out.println(System.getenv(a[0]));
                }
            }
            """;

    /** 지워도 자식 java 기동에 지장 없는 이름 후보 — PATH·SystemRoot·JAVA*처럼 기동에 쓰일 수 있는 이름은 넣지 않는다. */
    private static final List<String> HARMLESS = List.of("USERNAME", "COMPUTERNAME", "USER", "LOGNAME", "OS");

    @TempDir Path tmp;
    private Path source;

    @BeforeEach
    void writeChildSource() throws IOException {
        source = tmp.resolve("Child.java");
        Files.writeString(source, CHILD_SOURCE);
    }

    private List<String> child(String name) {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        return new ArrayList<>(List.of(java, source.toString(), name));
    }

    /** 부모 env에 실제로 있는, PATH·SystemRoot·JAVA*가 아닌 이름 하나. */
    private static Optional<String> parentVariable() {
        Map<String, String> env = System.getenv();
        return HARMLESS.stream()
                .filter(n -> env.get(n) != null && !env.get(n).isBlank())
                .findFirst();
    }

    @Test
    void apply_env_removes_names_case_insensitively_then_adds_extras() {
        Map<String, String> env = new HashMap<>(Map.of("GIT_ASKPASS", "a", "ssh_askpass", "b", "PATH", "p"));

        ProcessRunner.applyEnv(env, Map.of("GIT_TERMINAL_PROMPT", "0"), Set.of("GIT_ASKPASS", "SSH_ASKPASS"));

        assertThat(env).containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "p", "GIT_TERMINAL_PROMPT", "0"));
    }

    @Test
    void apply_env_replaces_a_parent_key_that_differs_only_in_case() {
        // Windows ProcessBuilder.environment()는 대소문자를 가리는 HashMap 복제본 — 부모 키가 "Path"면
        // put("PATH")가 키를 하나 더 만들고, 자식이 어느 쪽을 받을지는 맵 순서에 달린다.
        Map<String, String> env = new HashMap<>(Map.of("Path", "orig", "java_home", "old", "OTHER", "o"));

        ProcessRunner.applyEnv(env, Map.of("PATH", "new", "JAVA_HOME", "j"), Set.of());

        assertThat(env).containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "new", "JAVA_HOME", "j", "OTHER", "o"));
    }

    @Test
    void a_removed_parent_variable_never_reaches_the_child() throws Exception {
        Optional<String> name = parentVariable();
        assumeTrue(name.isPresent(), "지워 볼 부모 env가 없다");

        // 이름은 소문자로 넘긴다 — 대소문자를 무시하고 지워야 한다
        ProcessRunner.Result r = ProcessRunner.run(tmp.toFile(), child(name.get()),
                Map.of(), Set.of(name.get().toLowerCase(Locale.ROOT)), 60);

        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout().strip()).isEqualTo("null");
    }

    @Test
    void the_existing_overloads_keep_the_parent_environment() throws Exception {
        Optional<String> name = parentVariable();
        assumeTrue(name.isPresent(), "확인할 부모 env가 없다");
        String expected = System.getenv(name.get());

        ProcessRunner.Result plain = ProcessRunner.run(tmp.toFile(), child(name.get()), 60);
        ProcessRunner.Result withExtras = ProcessRunner.run(tmp.toFile(), child(name.get()), Map.of("NETIS_X", "1"), 60);

        assertThat(plain.stdout().strip()).isEqualTo(expected);
        assertThat(withExtras.stdout().strip()).isEqualTo(expected);
    }
}
