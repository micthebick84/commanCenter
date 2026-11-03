package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * push는 origin의 자격증명에 기대지 않는다 — 캐시/워크트리 config에 토큰을 남기지 않기 위해
 * {@code git push <인증 URL> HEAD:refs/heads/<branch>}로 보낸다.
 * 인증 URL이 argv에 실리므로 실패 메시지는 반드시 마스킹된 채로 올라와야 한다.
 */
class GitOpsServicePushTest {

    @TempDir Path tmp;

    private WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0,
                tmp.resolve("repos").toString(), null,
                null, "ghp_SECRETPAT", "glpat-SECRETTOKEN", null, null, null, null, null, null,
                null, null, null, null, deploy, null, null);
    }

    private static void git(File dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    /** file:// bare 레포 + main 브랜치 커밋 1개. */
    private Path bareRemote(String name) throws Exception {
        Path seed = Files.createDirectories(tmp.resolve(name + "-seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve(name + ".git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    /** bare에서 clone → 새 브랜치 체크아웃 → 커밋되지 않은 변경 1개. */
    private Path workingClone(Path bare, String dirName, String branch) throws Exception {
        Path clone = tmp.resolve(dirName);
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        git(clone.toFile(), "checkout", "-b", branch);
        Files.writeString(clone.resolve("impl.txt"), "구현 산출물");
        return clone;
    }

    @Test
    void push_uses_the_ref_url_so_a_stale_origin_does_not_break_it() throws Exception {
        Path bare = bareRemote("r1");
        Path clone = workingClone(bare, "work1", "netismaker/task-1");
        // origin에는 자격증명도 없고 최신 URL도 아니다 — push가 origin에 기대면 실패한다
        git(clone.toFile(), "remote", "set-url", "origin", "file:///nonexistent/stale.git");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        String sha = new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-1", "feat: 구현");

        assertThat(sha).hasSize(40);
        String pushed = ProcessRunner.requireSuccess(bare.toFile(),
                List.of("git", "rev-parse", "refs/heads/netismaker/task-1"), 30).trim();
        assertThat(pushed).isEqualTo(sha);
    }

    @Test
    void a_failing_push_throws_an_ioexception_whose_message_hides_the_token() throws Exception {
        Path bare = bareRemote("r2");
        Path clone = workingClone(bare, "work2", "netismaker/task-2");
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-2", "feat: 구현"))
                .isInstanceOf(IOException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage())
                            .doesNotContain("glpat-SECRETTOKEN")
                            .doesNotContain("oauth2:glpat");
                    // 원본 예외는 마스킹되지 않은 메시지를 갖고 있으므로 cause로 달지 않는다
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void nothing_to_commit_still_reports_the_dedicated_reason() throws Exception {
        Path bare = bareRemote("r3");
        Path clone = tmp.resolve("work3");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-3", "feat: 구현"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nothing to commit");
    }

    private static String head(Path dir) throws Exception {
        return ProcessRunner.requireSuccess(dir.toFile(), List.of("git", "rev-parse", "HEAD"), 30).trim();
    }

    private static String remoteSha(Path bare, String branch) throws Exception {
        return ProcessRunner.requireSuccess(bare.toFile(), List.of("git", "rev-parse", "refs/heads/" + branch), 30).trim();
    }

    @Test
    void commits_the_agent_already_made_are_pushed_even_when_the_tree_is_clean() throws Exception {
        Path bare = bareRemote("r4");
        Path clone = workingClone(bare, "work4", "netismaker/task-4");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        String sha = new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-4", "feat: 구현", base);

        assertThat(sha).isEqualTo(head(clone));
        assertThat(remoteSha(bare, "netismaker/task-4")).isEqualTo(sha);
    }

    @Test
    void leftover_changes_after_agent_commits_get_one_more_commit() throws Exception {
        Path bare = bareRemote("r5");
        Path clone = workingClone(bare, "work5", "netismaker/task-5");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        Files.writeString(clone.resolve("extra.txt"), "남은 변경");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        new GitOpsService(props()).commitAndPush(clone.toFile(), ref, "netismaker/task-5", "feat: 구현", base);

        assertThat(ProcessRunner.requireSuccess(clone.toFile(),
                List.of("git", "rev-list", "--count", base + "..HEAD"), 30).trim()).isEqualTo("2");
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "status", "--porcelain"), 30)).isBlank();
    }

    @Test
    void with_a_base_and_nothing_at_all_it_is_still_nothing_to_commit() throws Exception {
        Path bare = bareRemote("r6");
        Path clone = tmp.resolve("work6");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-6", "feat: 구현", head(clone)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nothing to commit");
    }

    @Test
    void partial_work_is_committed_as_wip_and_pushed() throws Exception {
        Path bare = bareRemote("r7");
        Path clone = workingClone(bare, "work7", "netismaker/task-7");   // impl.txt 미커밋
        String base = head(clone);
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-7", base, "wip: 구현 중단(시간 초과) — task #7");

        assertThat(w.pushed()).isTrue();
        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.headSha()).isEqualTo(head(clone));
        assertThat(remoteSha(bare, "netismaker/task-7")).isEqualTo(w.headSha());
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "log", "-1", "--format=%s"), 30).trim())
                .isEqualTo("wip: 구현 중단(시간 초과) — task #7");
    }

    @Test
    void nothing_to_preserve_reports_zero_commits_without_pushing() throws Exception {
        Path bare = bareRemote("r8");
        Path clone = tmp.resolve("work8");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props())
                .preservePartialWork(clone.toFile(), ref, "netismaker/task-8", head(clone), "wip");

        assertThat(w.commits()).isZero();
        assertThat(w.pushed()).isFalse();
        assertThat(w.error()).isNull();
        assertThat(ProcessRunner.run(bare.toFile(), List.of("git", "rev-parse", "--verify", "refs/heads/netismaker/task-8"), 30)
                .exitCode()).isNotZero();
    }

    @Test
    void a_failed_partial_push_keeps_the_commit_and_masks_the_error() throws Exception {
        Path bare = bareRemote("r9");
        Path clone = workingClone(bare, "work9", "netismaker/task-9");
        String base = head(clone);
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        GitOpsService.PartialWork w = new GitOpsService(props())
                .preservePartialWork(clone.toFile(), ref, "netismaker/task-9", base, "wip");

        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.pushed()).isFalse();
        assertThat(w.error()).isNotBlank().doesNotContain("glpat-SECRETTOKEN");
    }

    @Test
    void partial_work_that_is_only_agent_commits_is_pushed_without_a_new_wip_commit() throws Exception {
        Path bare = bareRemote("r10");
        Path clone = workingClone(bare, "work10", "netismaker/task-10");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        String agentHead = head(clone);   // 작업 트리는 깨끗하다
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-10", base, "wip: 구현 중단(비정상 종료) — task #10");

        assertThat(w.pushed()).isTrue();
        assertThat(w.commits()).isEqualTo(1);             // 에이전트 커밋 수 그대로
        assertThat(w.headSha()).isEqualTo(agentHead);     // WIP 커밋을 새로 만들지 않았다
        assertThat(w.error()).isNull();
        assertThat(remoteSha(bare, "netismaker/task-10")).isEqualTo(agentHead);
    }

    @Test
    void partial_work_with_agent_commits_and_leftover_changes_gets_one_wip_commit_on_top() throws Exception {
        Path bare = bareRemote("r11");
        Path clone = workingClone(bare, "work11", "netismaker/task-11");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        Files.writeString(clone.resolve("extra.txt"), "남은 변경");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-11", base, "wip: 구현 중단(시간 초과) — task #11");

        assertThat(w.pushed()).isTrue();
        assertThat(w.commits()).isEqualTo(2);             // 에이전트 커밋 1개 + WIP 커밋 1개
        assertThat(w.headSha()).isEqualTo(head(clone));
        assertThat(w.error()).isNull();
        assertThat(remoteSha(bare, "netismaker/task-11")).isEqualTo(w.headSha());
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "log", "-1", "--format=%s"), 30).trim())
                .isEqualTo("wip: 구현 중단(시간 초과) — task #11");
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "status", "--porcelain"), 30)).isBlank();
    }

    /** 죽은 claude가 남긴 index.lock — 이후 `git add`가 "Unable to create index.lock"으로 실패한다(WIP 커밋 실패 재현). */
    private static void staleIndexLock(Path clone) throws IOException {
        Files.writeString(clone.resolve(".git/index.lock"), "");
    }

    /** 최종 리뷰: WIP 커밋이 실패했다고 몇 시간치 SDD 태스크 커밋까지 원격에 못 올리면 안 된다. */
    @Test
    void a_failed_wip_commit_still_pushes_the_agent_commits_and_reports_what_stayed_behind() throws Exception {
        Path bare = bareRemote("r12");
        Path clone = workingClone(bare, "work12", "netismaker/task-12");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        String agentHead = head(clone);
        Files.writeString(clone.resolve("extra.txt"), "커밋되지 못할 변경");
        staleIndexLock(clone);
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-12", base, "wip: 구현 중단(시간 초과) — task #12");

        assertThat(w.pushed()).isTrue();                  // push는 성공 — 에이전트 커밋은 원격에 있다
        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.headSha()).isEqualTo(agentHead);     // WIP 커밋은 못 만들었으니 에이전트 마지막 커밋
        assertThat(w.error()).isNull();
        assertThat(w.wipError()).isNotBlank().contains("index.lock");   // 커밋 안 된 변경이 PC에만 남은 이유
        assertThat(remoteSha(bare, "netismaker/task-12")).isEqualTo(agentHead);
        assertThat(clone.resolve("extra.txt")).exists();
    }

    @Test
    void a_failed_wip_commit_without_agent_commits_reports_nothing_was_preserved() throws Exception {
        Path bare = bareRemote("r13");
        Path clone = workingClone(bare, "work13", "netismaker/task-13");   // impl.txt 미커밋
        String base = head(clone);
        staleIndexLock(clone);
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-13", base, "wip: 구현 중단(시간 초과) — task #13");

        assertThat(w.commits()).isZero();
        assertThat(w.pushed()).isFalse();
        assertThat(w.headSha()).isNull();
        assertThat(w.error()).isNotBlank().contains("index.lock");   // 예전과 같다 — 보존 실패 사유
        assertThat(ProcessRunner.run(bare.toFile(),
                List.of("git", "rev-parse", "--verify", "refs/heads/netismaker/task-13"), 30).exitCode()).isNotZero();
    }

    @Test
    void when_the_wip_commit_and_the_push_both_fail_both_errors_are_kept_and_masked() throws Exception {
        Path bare = bareRemote("r14");
        Path clone = workingClone(bare, "work14", "netismaker/task-14");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        Files.writeString(clone.resolve("extra.txt"), "커밋되지 못할 변경");
        staleIndexLock(clone);
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-14", base, "wip");

        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.pushed()).isFalse();                 // push 실패 — pushed()는 push가 성공했을 때만
        assertThat(w.error()).isNotBlank().doesNotContain("glpat-SECRETTOKEN");
        assertThat(w.wipError()).isNotBlank().contains("index.lock").doesNotContain("glpat-SECRETTOKEN");
    }

    @Test
    void the_three_argument_result_has_no_wip_failure() {
        GitOpsService.PartialWork w = new GitOpsService.PartialWork(2, "abc", null);

        assertThat(w.wipError()).isNull();
        assertThat(w.pushed()).isTrue();
    }

    // ---- 세션이 쓴 스크립트 파일 강제 추가 (스펙 2026-11-03 §6.6, Review Focus 3) — 실제 git ----

    private static final String SCRIPT = "db/symphony/task-42.sql";

    /** .gitignore가 `*.sql`·`db/`를 무시하는 레포의 bare remote(main 커밋 1개: .gitignore·a.txt). */
    private Path ignoringRemote(String name) throws Exception {
        Path seed = Files.createDirectories(tmp.resolve(name + "-seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve(".gitignore"), "*.sql\ndb/\n");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve(name + ".git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    /** 무시 규칙이 있는 레포에서 새 브랜치 + 세션이 쓴 스크립트 파일 하나(다른 변경 없음). */
    private Path cloneWithIgnoredScript(Path bare, String dirName, String branch) throws Exception {
        Path clone = tmp.resolve(dirName);
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        git(clone.toFile(), "checkout", "-b", branch);
        Files.createDirectories(clone.resolve("db/symphony"));
        Files.writeString(clone.resolve(SCRIPT), "CREATE TABLE t_new (id int);\n");
        return clone;
    }

    private static String namesIn(File dir, String... showArgs) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(showArgs));
        return ProcessRunner.requireSuccess(dir, cmd, 30);
    }

    @Test
    void 무시된_파일은_강제추가_없이는_변경으로_안_보인다() throws Exception {
        Path bare = ignoringRemote("i1");
        Path clone = cloneWithIgnoredScript(bare, "iwork1", "netismaker/task-42");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        assertThat(new GitOpsService(props()).hasChanges(clone.toFile())).isFalse();
        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-42", "db: 구현", head(clone)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nothing to commit");
    }

    @Test
    void 무시된_파일도_forceAdd면_커밋과_wip에_들어간다() throws Exception {
        // 성공 경로 — commitAndPush
        Path bare = ignoringRemote("i2");
        Path clone = cloneWithIgnoredScript(bare, "iwork2", "netismaker/task-42");
        String base = head(clone);
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        GitOpsService ops = new GitOpsService(props());

        ops.forceAdd(clone.toFile(), SCRIPT);
        assertThat(ops.hasChanges(clone.toFile())).isTrue();
        String sha = ops.commitAndPush(clone.toFile(), ref, "netismaker/task-42", "db: 구현", base);

        assertThat(namesIn(clone.toFile(), "show", "--name-only", "--format=", "HEAD")).contains(SCRIPT);
        assertThat(namesIn(bare.toFile(), "ls-tree", "-r", "--name-only", sha)).contains(SCRIPT);

        // wip 경로 — preservePartialWork
        Path bare2 = ignoringRemote("i3");
        Path clone2 = cloneWithIgnoredScript(bare2, "iwork3", "netismaker/task-43");
        String base2 = head(clone2);
        RepoRef ref2 = new RepoRef("github", "acme/widgets", bare2.toUri().toString());

        ops.forceAdd(clone2.toFile(), SCRIPT);
        GitOpsService.PartialWork w = ops.preservePartialWork(clone2.toFile(), ref2, "netismaker/task-43", base2,
                "wip: 구현 중단(시간 초과) — task #43");

        assertThat(w.pushed()).isTrue();
        assertThat(w.commits()).isEqualTo(1);
        assertThat(namesIn(clone2.toFile(), "show", "--name-only", "--format=", "HEAD")).contains(SCRIPT);
        assertThat(namesIn(bare2.toFile(), "ls-tree", "-r", "--name-only", w.headSha())).contains(SCRIPT);
    }

    @Test
    void 무시된_파일은_wip에도_강제추가_없이는_빠진다() throws Exception {
        Path bare = ignoringRemote("i4");
        Path clone = cloneWithIgnoredScript(bare, "iwork4", "netismaker/task-44");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-44", head(clone), "wip");

        assertThat(w.commits()).isZero();   // 올릴 것이 없다 — 강제 추가가 이 구멍을 막는다
    }

    @Test
    void forceAdd는_다른_변경과_함께_커밋되고_이미_추적_중인_파일은_그대로_둔다() throws Exception {
        Path bare = ignoringRemote("i5");
        Path clone = cloneWithIgnoredScript(bare, "iwork5", "netismaker/task-45");
        Files.writeString(clone.resolve("impl.txt"), "구현 산출물");
        GitOpsService ops = new GitOpsService(props());

        ops.forceAdd(clone.toFile(), SCRIPT);
        ops.forceAdd(clone.toFile(), SCRIPT);   // 두 번 불러도 같다

        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        ops.commitAndPush(clone.toFile(), ref, "netismaker/task-45", "db: 구현", head(clone));
        assertThat(namesIn(clone.toFile(), "show", "--name-only", "--format=", "HEAD"))
                .contains(SCRIPT).contains("impl.txt");
    }

    @Test
    void forceAdd는_없는_파일이면_예외() throws Exception {
        Path bare = ignoringRemote("i6");
        Path clone = tmp.resolve("iwork6");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());

        assertThatThrownBy(() -> new GitOpsService(props()).forceAdd(clone.toFile(), SCRIPT))
                .isInstanceOf(IOException.class);
    }
}
