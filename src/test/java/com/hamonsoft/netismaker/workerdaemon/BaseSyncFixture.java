package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 기준 브랜치 반영 시험용 실제 git 준비물 — bare 원격(main·task), 얕은 캐시, 작업 브랜치 끝의 detached worktree.
 * main과 task는 공통 기준 커밋(BASE_FILES)에서 갈라지고, 각자 파일 몇 개를 바꾼 커밋을 더 가진다.
 */
final class BaseSyncFixture {

    static final String TASK = "task";
    static final String BASE = "main";
    static final Map<String, String> BASE_FILES = Map.of(
            "PLAN.md", "제목\n본문\n",
            "README.md", "readme\n",
            "other.txt", "other\n",
            "docs/작업 기록.md", "기록\n",
            "img.bin", "A\0B\0C");

    final Path bare;
    final RepoRef ref;
    final GitRepoCache cache;
    final WorktreeService worktrees;
    final GitOpsService gitOps;
    final BaseSyncMerger merger;
    final String repoKey;
    GitRepoCache.BaseSyncRepo repo;
    WorktreeService.CreatedWorktree wt;

    BaseSyncFixture(Path tmp, Map<String, String> mainEdits, Map<String, String> taskEdits) throws Exception {
        this(tmp, mainEdits, taskEdits, 0);
    }

    /** @param extraCommits 각 브랜치에 더하는 의미 없는 커밋 수(깊이 시험용) */
    BaseSyncFixture(Path tmp, Map<String, String> mainEdits, Map<String, String> taskEdits, int extraCommits)
            throws Exception {
        WorkerProperties p = props(tmp);
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", BASE);
        git(seed.toFile(), "config", "core.autocrlf", "false");
        commit(seed, BASE_FILES, "base");
        git(seed.toFile(), "checkout", "-b", TASK);
        for (int i = 0; i < extraCommits; i++) commit(seed, Map.of("task-extra-" + i + ".txt", "t" + i), "task extra " + i);
        commit(seed, taskEdits, "task edit");
        git(seed.toFile(), "checkout", BASE);
        for (int i = 0; i < extraCommits; i++) commit(seed, Map.of("main-extra-" + i + ".txt", "m" + i), "main extra " + i);
        commit(seed, mainEdits, "main edit");
        bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        repoKey = GitRemotes.localKey(ref);
        cache = new GitRepoCache(p);
        worktrees = new WorktreeService(p, cache);
        gitOps = new GitOpsService(p);
        merger = new BaseSyncMerger(p);
    }

    /** 얕은 캐시에서 두 브랜치를 받고 worktree를 작업 브랜치 끝에 둔다. */
    BaseSyncFixture prepare() throws Exception {
        repo = cache.fetchForBaseSync(ref, TASK, BASE);
        wt = worktrees.createOnBranch(repo.dir(), repoKey, TASK, 42L);
        return this;
    }

    File dir() { return wt.dir(); }

    static WorkerProperties props(Path tmp) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null,
                "netisMaker", "worker@example.com", null, null, null, null,
                deploy, new WorkerProperties.RepoCacheCleanup(false, null, 30), null);
    }

    static String git(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    static int exit(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(dir, cmd, 60).exitCode();
    }

    static void commit(Path dir, Map<String, String> files, String msg) throws Exception {
        for (var e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        git(dir.toFile(), "add", "-A");
        git(dir.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-m", msg);
    }
}
