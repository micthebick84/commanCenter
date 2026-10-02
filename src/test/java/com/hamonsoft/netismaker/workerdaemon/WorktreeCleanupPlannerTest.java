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
}
