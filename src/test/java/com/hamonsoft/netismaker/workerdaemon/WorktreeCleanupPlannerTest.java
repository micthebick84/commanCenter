package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPlanner.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeCleanupPlannerTest {

    @TempDir Path tmp;

    private static final Instant NOW = Instant.parse("2026-10-02T02:30:00Z");

    @Test
    void parses_only_exact_kind_and_positive_decimal_id() {
        assertThat(WorktreeKind.parse("task-12")).contains(new WorktreeKind.Parsed(WorktreeKind.TASK, 12));
        assertThat(WorktreeKind.parse("deploy-1")).contains(new WorktreeKind.Parsed(WorktreeKind.DEPLOY, 1));
        assertThat(WorktreeKind.parse("design-305")).contains(new WorktreeKind.Parsed(WorktreeKind.DESIGN, 305));
        for (String bad : new String[]{"task-", "task-0", "task-01", "task--1", "task-1x", "task-1.bak",
                "Task-1", "TASK-1", "tasks-1", "task-1-2", " task-1", "task-12345678901234567890", ".worktree-cleanup.lock"}) {
            assertThat(WorktreeKind.parse(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void dir_name_round_trips_through_parse() {
        for (WorktreeKind k : WorktreeKind.values()) {
            assertThat(WorktreeKind.parse(k.dirName(42))).contains(new WorktreeKind.Parsed(k, 42));
        }
    }

    @Test
    void active_task_is_protected_regardless_of_age() {
        Instant old = NOW.minus(Duration.ofDays(400));
        assertThat(WorktreeCleanupPlanner.judge(7, Set.of(7L), old, NOW, 7)).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void exactly_retention_days_old_is_removed_one_second_less_is_kept() {
        Instant edge = NOW.minus(Duration.ofDays(7));
        assertThat(WorktreeCleanupPlanner.judge(7, Set.of(), edge, NOW, 7)).isEqualTo(Verdict.REMOVE);
        assertThat(WorktreeCleanupPlanner.judge(7, Set.of(), edge.plusSeconds(1), NOW, 7)).isEqualTo(Verdict.TOO_NEW);
    }

    @Test
    void unreadable_last_activity_is_unknown() {
        assertThat(WorktreeCleanupPlanner.judge(7, Set.of(), null, NOW, 7)).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void last_activity_is_the_later_of_the_dir_and_its_dot_git_file() throws Exception {
        Path wt = Files.createDirectories(tmp.resolve("task-1"));
        Path gitFile = Files.writeString(wt.resolve(".git"), "gitdir: x\n");
        Instant dirTime = NOW.minus(Duration.ofDays(30));
        Instant gitTime = NOW.minus(Duration.ofDays(2));
        Files.setLastModifiedTime(gitFile, FileTime.from(gitTime));
        Files.setLastModifiedTime(wt, FileTime.from(dirTime));   // .git 쓰기가 폴더 mtime을 바꾸므로 나중에

        assertThat(WorktreeCleanupPlanner.lastActivity(wt)).isEqualTo(gitTime);
    }

    @Test
    void last_activity_without_dot_git_uses_the_dir_and_missing_dir_is_null() throws Exception {
        Path wt = Files.createDirectories(tmp.resolve("deploy-3"));
        Instant dirTime = NOW.minus(Duration.ofDays(9));
        Files.setLastModifiedTime(wt, FileTime.from(dirTime));

        assertThat(WorktreeCleanupPlanner.lastActivity(wt)).isEqualTo(dirTime);
        assertThat(WorktreeCleanupPlanner.lastActivity(tmp.resolve("nope"))).isNull();
    }

    /** {repos}/{a}/{b}에 .git 디렉터리를 만든 캐시 흉내. */
    private static Path fakeCache(Path repos, String key) throws Exception {
        Path cache = repos.resolve(key);
        Files.createDirectories(cache.resolve(".git").resolve("worktrees"));
        return cache;
    }

    /** 참조 폴더와 그 .git 파일(내용 그대로). */
    private Path refWithGitFile(String name, String content) throws Exception {
        Path ref = Files.createDirectories(tmp.resolve("worktrees/_refs/task-5").resolve(name));
        Files.writeString(ref.resolve(".git"), content);
        return ref;
    }

    private static String gitdirLine(Path meta) {
        return "gitdir: " + meta.toString().replace('\\', '/') + "\n";
    }

    @Test
    void ref_source_key_follows_the_gitdir_into_the_repos_dir() throws Exception {
        Path repos = Files.createDirectories(tmp.resolve("repos"));
        Path reposReal = repos.toRealPath();
        Path cache = fakeCache(repos, "acme/widgets");
        Path meta = cache.resolve(".git/worktrees/12");

        Path absolute = refWithGitFile("12", gitdirLine(meta));
        assertThat(WorktreeCleanupPlanner.refSourceKey(absolute, reposReal)).contains("acme/widgets");

        // git 2.48+ relative 모드 — 참조 폴더 기준 상대 경로
        Path relative = Files.createDirectories(tmp.resolve("worktrees/_refs/task-6/12"));
        Files.writeString(relative.resolve(".git"),
                "gitdir: " + relative.relativize(meta).toString().replace('\\', '/') + "\n");
        assertThat(WorktreeCleanupPlanner.refSourceKey(relative, reposReal)).contains("acme/widgets");

        // 같은 캐시의 두 번째 worktree는 메타 이름에 번호가 붙는다(12 → 121) — 폴더 이름으로 메타를 짐작하지 않는다
        Path numbered = refWithGitFile("13", gitdirLine(cache.resolve(".git/worktrees/121")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(numbered, reposReal)).contains("acme/widgets");

        // GitLab 캐시 키도 2단계(_gitlab/{경로의 '/'→'+'})
        Path gitlab = fakeCache(repos, "_gitlab/team+app");
        Path gl = refWithGitFile("14", gitdirLine(gitlab.resolve(".git/worktrees/14")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(gl, reposReal)).contains("_gitlab/team+app");
    }

    @Test
    void ref_source_key_is_empty_when_it_cannot_be_trusted() throws Exception {
        Path repos = Files.createDirectories(tmp.resolve("repos"));
        Path reposReal = repos.toRealPath();
        Path cache = fakeCache(repos, "acme/widgets");
        Path elsewhere = fakeCache(Files.createDirectories(tmp.resolve("elsewhere")), "acme/widgets");
        Path deep = fakeCache(repos, "a/b/c");
        Path noGit = Files.createDirectories(repos.resolve("acme/gone"));
        Path trashed = fakeCache(repos, ".trash/acme+widgets-1");

        Path missing = Files.createDirectories(tmp.resolve("worktrees/_refs/task-5/1"));
        assertThat(WorktreeCleanupPlanner.refSourceKey(missing, reposReal)).as(".git 없음").isEmpty();

        Path garbage = refWithGitFile("2", "garbage");
        assertThat(WorktreeCleanupPlanner.refSourceKey(garbage, reposReal)).as("형식 이상").isEmpty();

        Path outside = refWithGitFile("3", gitdirLine(elsewhere.resolve(".git/worktrees/3")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(outside, reposReal)).as("repos 밖").isEmpty();

        Path reclaimed = refWithGitFile("4", gitdirLine(noGit.resolve(".git/worktrees/4")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(reclaimed, reposReal)).as("캐시에 .git 디렉터리 없음").isEmpty();

        Path notWorktrees = refWithGitFile("5", gitdirLine(cache.resolve(".git/modules/5")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(notWorktrees, reposReal)).as(".git/worktrees 아래 아님").isEmpty();
        Path notDotGit = refWithGitFile("6", gitdirLine(cache.resolve("worktrees/6")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(notDotGit, reposReal)).as(".git 아래 아님").isEmpty();

        Path threeLevels = refWithGitFile("7", gitdirLine(deep.resolve(".git/worktrees/7")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(threeLevels, reposReal)).as("3단계 경로").isEmpty();

        // repos-dir 내부용(.trash 등) 아래 캐시는 레포 키가 아니다 — 가짜 키로 락 파일을 만들지 않게
        Path inTrash = refWithGitFile("8", gitdirLine(trashed.resolve(".git/worktrees/8")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(inTrash, reposReal)).as("repos-dir 내부용 폴더").isEmpty();

        Path dotGitDir = Files.createDirectories(tmp.resolve("worktrees/_refs/task-5/9/.git"));
        assertThat(WorktreeCleanupPlanner.refSourceKey(dotGitDir.getParent(), reposReal)).as(".git이 파일 아님").isEmpty();

        Path good = refWithGitFile("10", gitdirLine(cache.resolve(".git/worktrees/10")));
        assertThat(WorktreeCleanupPlanner.refSourceKey(good, null)).as("repos-dir 모름").isEmpty();
    }
}
