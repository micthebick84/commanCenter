package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * worktree 재생성 시 기존 폴더 정리를 실제 git 캐시/worktree로 검증한다(file:// bare 원격 — 네트워크 없음).
 */
class WorktreeServiceTest {

    @TempDir Path tmp;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, new WorkerProperties.RepoCacheCleanup(false, null, 30), null);
    }

    private static void git(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

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

    /** 재시도 때 기존 worktree 안에 남은 정션을 따라가 worktree 밖 파일을 지우면 안 된다. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void recreating_a_worktree_does_not_follow_a_junction_out_of_it() throws Exception {
        WorkerProperties p = props();
        GitRepoCache cache = new GitRepoCache(p);
        RepoRef ref = new RepoRef("github", "acme/widgets", bareRemote().toUri().toString());
        File repoDir = cache.ensureFresh(ref, "main").dir();
        String key = GitRemotes.localKey(ref);
        WorktreeService worktrees = new WorktreeService(p, cache);
        File first = worktrees.create(repoDir, key, "main", 5, "retry").dir();

        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "precious");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("sub/keep2.txt"), "precious2");
        junction(first.toPath().resolve("node_modules"), outside);

        File second = worktrees.create(repoDir, key, "main", 5, "retry").dir();

        assertThat(outside.resolve("keep.txt")).hasContent("precious");
        assertThat(outside.resolve("sub/keep2.txt")).hasContent("precious2");
        assertThat(second.toPath().resolve("a.txt")).hasContent("hello");
        assertThat(second.toPath().resolve("node_modules")).doesNotExist();
    }
}
