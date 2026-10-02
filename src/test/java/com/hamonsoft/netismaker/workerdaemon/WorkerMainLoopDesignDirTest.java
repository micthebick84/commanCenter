package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code WorkerMainLoop.deleteDesignDir}(커밋 전 목업 참조 폴더 제거) 단위 테스트.
 *
 * 구현 단계는 claude가 권한 우회로 worktree를 마음대로 만지므로 .design 안에 링크·정션이
 * 생길 수 있다. 그 정리 과정이 링크를 따라가 worktree 밖을 지우면 안 된다.
 * processImplementation 전체는 협력 객체 6개 이상을 엮어야 해서 헬퍼를 직접 검증한다.
 */
class WorkerMainLoopDesignDirTest {

    @TempDir Path tmp;

    /** mklink /J는 관리자 권한 없이 만들 수 있다. 실패하면(정책 등) 테스트를 건너뛴다. */
    private static void junction(Path link, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        assumeTrue(done && p.exitValue() == 0, "mklink /J 실패 — 정션을 만들 수 없는 환경");
        BasicFileAttributes a = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assumeTrue(a.isOther() && !Files.isSymbolicLink(link), "정션이 isOther로 보이지 않는 환경");
    }

    @Test
    void removes_the_design_dir_with_its_mockup_files() throws Exception {
        Path design = Files.createDirectories(tmp.resolve("wt/.design/pages"));
        Files.writeString(design.resolve("index.html"), "<html/>");
        Files.writeString(tmp.resolve("wt/.design/DESIGN.md"), "# 디자인");
        Files.writeString(tmp.resolve("wt/src.txt"), "code");

        WorkerMainLoop.deleteDesignDir(tmp.resolve("wt/.design"));

        assertThat(tmp.resolve("wt/.design")).doesNotExist();
        assertThat(tmp.resolve("wt/src.txt")).hasContent("code");
    }

    @Test
    void a_missing_design_dir_is_not_an_error() {
        WorkerMainLoop.deleteDesignDir(tmp.resolve("wt/.design"));

        assertThat(tmp.resolve("wt/.design")).doesNotExist();
    }

    /** .design 안에 worktree 밖을 가리키는 정션이 있어도 정션만 지우고 대상은 남겨야 한다. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void does_not_follow_a_junction_out_of_the_design_dir() throws Exception {
        Path design = Files.createDirectories(tmp.resolve("wt/.design"));
        Files.writeString(design.resolve("DESIGN.md"), "# 디자인");

        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "precious");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("sub/keep2.txt"), "precious2");
        junction(design.resolve("node_modules"), outside);

        WorkerMainLoop.deleteDesignDir(design);

        assertThat(outside.resolve("keep.txt")).hasContent("precious");
        assertThat(outside.resolve("sub/keep2.txt")).hasContent("precious2");
        assertThat(design).doesNotExist();
    }
}
