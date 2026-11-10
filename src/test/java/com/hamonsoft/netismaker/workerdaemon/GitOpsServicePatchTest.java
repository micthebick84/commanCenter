package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 패치용 git 읽기 — parents / diffNameStatus / remoteBranchTip. 임시 레포·file:// 원격. */
class GitOpsServicePatchTest {

    @TempDir Path tmp;

    private File repo;
    private String root;
    private String c2;
    private String merge;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0,
                tmp.resolve("repos").toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null, null, null, null, null,
                null, null, null, null, deploy, null, null);
    }

    private static String git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false",
                "-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60).trim();
    }

    private static void write(File dir, String name, String text) throws Exception {
        Files.writeString(dir.toPath().resolve(name), text);
        git(dir, "add", "-A");
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tmp.resolve("repo")).toFile();
        git(repo, "init", "-b", "main");
        write(repo, "a.txt", "one");
        git(repo, "commit", "-m", "c1");
        root = git(repo, "rev-parse", "HEAD");
        write(repo, "b.txt", "two");
        write(repo, "a.txt", "one-changed");
        git(repo, "commit", "-m", "c2");
        c2 = git(repo, "rev-parse", "HEAD");
        git(repo, "checkout", "-b", "feature", root);
        write(repo, "c.txt", "three");
        git(repo, "commit", "-m", "f1");
        git(repo, "checkout", "main");
        git(repo, "merge", "--no-ff", "-m", "merge", "feature");
        merge = git(repo, "rev-parse", "HEAD");
    }

    @Test
    void parentsOfMergeCommitHasTwo() throws Exception {
        List<String> parents = new GitOpsService(props()).parents(repo, merge);
        assertThat(parents).hasSize(2);
        assertThat(parents.get(0)).isEqualTo(c2);
    }

    @Test
    void parentsOfRootIsEmpty() throws Exception {
        assertThat(new GitOpsService(props()).parents(repo, root)).isEmpty();
    }

    @Test
    void diffNameStatusListsChangedFile() throws Exception {
        List<String> lines = new GitOpsService(props()).diffNameStatus(repo, root, c2);
        assertThat(lines).containsExactlyInAnyOrder("M\ta.txt", "A\tb.txt");
    }

    @Test
    void remoteBranchTipReadsTipFromRemote() throws Exception {
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", repo.toString(), bare.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitOpsService ops = new GitOpsService(props());

        assertThat(ops.remoteBranchTip(ref, "main")).isEqualTo(merge);
        assertThat(ops.remoteBranchTip(ref, "no-such-branch")).isNull();
    }

    @Test
    void remoteBranchTipIsNullWhenRemoteUnreachable() {
        RepoRef ref = new RepoRef("github", "acme/widgets", tmp.resolve("missing.git").toUri().toString());
        assertThat(new GitOpsService(props()).remoteBranchTip(ref, "main")).isNull();
    }
}
