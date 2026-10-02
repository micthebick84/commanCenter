# worktree 보존 정책 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 성공한 작업의 worktree는 그 자리에서 지우고, 실패·과거 누적분은 만든 지 7일 지나면 새벽 정리 잡이 지워서 레포 캐시 정리가 실제로 회수되게 한다.

**Architecture:** 워커 데몬(`workerdaemon`)에 ① `WorktreeService.discard`(레포 락 안 제거 절차)와 구현·배포 성공 경로의 호출, ② 순수 판정 `WorktreeCleanupPlanner` + 스케줄 잡 `WorktreeCleanupJob`을 더한다. 잡은 API `GET /worker/active-worktree-tasks`로 진행 중 작업을 받아 보호하고, 조회 실패면 회차 전체를 건너뛴다(fail-closed).

**Tech Stack:** Java 21, Spring Boot(worker 프로파일 `@Component`/`SchedulingConfigurer`, api 프로파일 `@RestController`), JPA, JUnit 5 + AssertJ + Mockito, 실제 `git` CLI(테스트는 file:// bare 원격).

**Spec:** `docs/superpowers/specs/2026-10-02-worktree-retention-design.md`

## Global Constraints

- 보존 기간 기본 **7일**(`WORKTREE_CLEANUP_RETENTION_DAYS`), **0 이하면 바인딩 실패**. cron 기본 **`0 30 2 * * *`**(`WORKTREE_CLEANUP_CRON`, `-`=끔, 잘못된 식은 바인딩 실패), `WORKTREE_CLEANUP_ENABLED` 기본 `true`.
- 진행 중 상태는 정확히 **`IMPLEMENTING`·`DESIGNING`·`DEPLOYING`·`UNDEPLOYING`** 네 개. soft-delete된 작업은 제외(상태가 영영 안 바뀌어 worktree가 영구 보호되는 것을 막는다 — 방금 삭제된 진행 중 작업은 7일 나이 판정이 보호).
- worktree 경로 규칙: `worktree-root/{localKey}/{task|deploy|design}-{taskId}`, `localKey`는 항상 2단계(`owner/repo` 또는 `_gitlab/{평탄화}`).
- 정리 실패는 작업 결과(보고·상태)를 바꾸지 않는다 — 즉시 정리는 best-effort.
- 진행 중 조회 실패(네트워크·4xx·404 포함) → 그 회차는 **아무것도 지우지 않는다**.
- 삭제는 링크·Windows 정션을 따라가지 않고(`RepoCacheCleanupJob.deleteTree`), Windows 읽기 전용 파일은 `FileDeletion`이 처리.
- `git worktree lock`이 걸린 worktree는 지우지 않는다(사람이 일부러 남긴 것).
- 코드 스타일: 기존 파일 관례(한국어 주석, 2-space가 아닌 4-space Java, `log.warn` 한국어 메시지).
- 테스트 실행은 Git Bash에서 `./gradlew test --tests "<FQCN>"`처럼 **클래스명을 명시**(글롭은 `netismaker-interview-service` 디렉터리로 확장돼 실패). `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1`. Docker가 없어 Testcontainers 테스트는 이 PC에서 skip(CI가 돌린다). 라이브 스택의 Gradle 데몬은 **죽이지 말 것**.

## Review Focus

- **접두 일치 번호** — `task-1` 정리가 `task-12-…` 브랜치나 `task-12` 폴더를 지우면 안 된다(Task 2 테스트 `deletes_only_this_tasks_branches`).
- **재시도 경합** — 정리 잡이 1차 판정한 뒤 같은 작업이 재시도돼 worktree가 새로 만들어지면, 락 안 재판정이 새 mtime을 보고 남겨야 한다(Task 6 테스트 `rejudges_inside_the_repo_lock`).
- **사람이 남긴 worktree** — `git worktree lock`된 worktree는 즉시 정리·주기 정리 모두 지우지 않는다(Task 2 `keeps_a_git_locked_worktree`, Task 6 `counts_locked_worktrees_without_deleting`).
- **링크·이상한 이름** — worktree-root 아래 심볼릭 링크나 `task-01`·`Task-1`·`task-1.bak` 같은 이름은 손대지 않는다(Task 1 이름 해석, Task 6 `ignores_unrecognized_names_and_symlinks`).
- **설정 생략과 0** — `WORKTREE_CLEANUP_RETENTION_DAYS`를 비우면 7일, `0`이면 부팅 실패(Task 5 바인딩 테스트).

---

## File Structure

| 파일 | 책임 | Task |
|---|---|---|
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeKind.java` (신규) | `task-`/`deploy-`/`design-` 폴더 이름 규칙의 단일 출처(생성·해석) | 1 |
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPlanner.java` (신규) | 판정 순수 함수(진행 중·기간·판단 불가) + 마지막 활동 시각 | 1 |
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java` | `worktreeDir`·`discard`·`discardUnderLock`·`isGitLocked`, 생성 경로를 `WorktreeKind`로 | 2 |
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` | 구현 성공 뒤 `discard(TASK)`, 디자인 수확 뒤 `discard(DESIGN)` | 2·3 |
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java` | 배포 성공 직후 `discard(DEPLOY)` | 3 |
| `src/main/java/com/hamonsoft/netismaker/dto/ActiveWorktreeTaskSummary.java` (신규) | API 응답 항목 | 4 |
| `repository/TaskRepository.java`, `service/WorkerService.java`, `controller/WorkerController.java`, `workerdaemon/WorkerHttpClient.java` | 진행 중 작업 조회 API와 클라이언트 | 4 |
| `workerdaemon/WorkerProperties.java`, `src/main/resources/application-worker.yml` | `WorktreeCleanup` 설정 | 5 |
| `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupJob.java` (신규) | 스케줄·잡 락·열거·레포 락·제거·회차 로그 | 6 |
| `RepoCacheCleanupJob.java` 주석, `application-worker.yml` 경고, `CLAUDE.md`, `TODOS.md` | 문서 정정 | 7 |

---

### Task 1: `WorktreeKind` + `WorktreeCleanupPlanner` (순수 판정)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeKind.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPlanner.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPlannerTest.java`

**Interfaces:**
- Produces:
  - `enum WorktreeKind { TASK, DEPLOY, DESIGN }` — `String dirName(long taskId)`, `static Optional<WorktreeKind.Parsed> parse(String dirName)`, `record Parsed(WorktreeKind kind, long taskId)`
  - `final class WorktreeCleanupPlanner` — `enum Verdict { REMOVE, ACTIVE, TOO_NEW, UNKNOWN }`, `static Verdict judge(long taskId, Set<Long> activeTaskIds, Instant lastActivity, Instant now, int retentionDays)`, `static Instant lastActivity(Path worktreeDir)` (못 읽으면 `null`)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPlannerTest"`
Expected: 컴파일 실패 — `WorktreeKind`·`WorktreeCleanupPlanner` 없음

- [ ] **Step 3: `WorktreeKind` 구현**

```java
package com.hamonsoft.netismaker.workerdaemon;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * worktree-root/{localKey}/{kind}-{taskId} 의 kind. 폴더 이름 규칙의 단일 출처 —
 * WorktreeService가 만들 때와 WorktreeCleanupJob이 해석할 때 같은 규칙을 쓴다.
 */
public enum WorktreeKind {
    TASK("task-"),
    DEPLOY("deploy-"),
    DESIGN("design-");

    /** 양의 10진 정수, 앞자리 0 없음, long 범위 안(18자리까지). */
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

    private final String prefix;

    WorktreeKind(String prefix) {
        this.prefix = prefix;
    }

    public String dirName(long taskId) {
        return prefix + taskId;
    }

    /** 정확히 {prefix}{id} 형태일 때만. 대소문자·앞자리 0·부호·접미사가 다르면 empty — 모르는 폴더는 건드리지 않는다. */
    public static Optional<Parsed> parse(String dirName) {
        if (dirName == null) return Optional.empty();
        for (WorktreeKind k : values()) {
            if (dirName.startsWith(k.prefix)) {
                String rest = dirName.substring(k.prefix.length());
                if (ID.matcher(rest).matches()) return Optional.of(new Parsed(k, Long.parseLong(rest)));
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public record Parsed(WorktreeKind kind, long taskId) {}
}
```

- [ ] **Step 4: `WorktreeCleanupPlanner` 구현**

```java
package com.hamonsoft.netismaker.workerdaemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * worktree 정리 판정(순수 함수) — WorktreeCleanupJob 전용. 파일 시스템·락은 잡이 다룬다.
 * 모르면 안 지운다: 진행 중이거나, 보존 기간 안이거나, 시각을 못 읽으면 남긴다.
 */
final class WorktreeCleanupPlanner {

    enum Verdict { REMOVE, ACTIVE, TOO_NEW, UNKNOWN }

    private WorktreeCleanupPlanner() {}

    /**
     * @param lastActivity  {@link #lastActivity(Path)} 결과. null이면 UNKNOWN
     * @param retentionDays 1 이상(WorkerProperties.WorktreeCleanup이 보장)
     */
    static Verdict judge(long taskId, Set<Long> activeTaskIds, Instant lastActivity, Instant now, int retentionDays) {
        if (activeTaskIds.contains(taskId)) return Verdict.ACTIVE;
        if (lastActivity == null) return Verdict.UNKNOWN;
        if (lastActivity.isAfter(now.minus(Duration.ofDays(retentionDays)))) return Verdict.TOO_NEW;
        return Verdict.REMOVE;
    }

    /**
     * 폴더와 그 안 .git 파일의 수정 시각 중 늦은 쪽. worktree는 작업 시작 때 만들어지므로 사실상 '만든 시각'.
     * 폴더 시각을 못 읽거나 .git이 부재 외의 이유로 안 읽히면 null(판단 불가). .git이 없으면 폴더 시각만.
     */
    static Instant lastActivity(Path worktreeDir) {
        Instant dir;
        try {
            dir = Files.getLastModifiedTime(worktreeDir, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            return null;
        }
        try {
            Instant git = Files.getLastModifiedTime(worktreeDir.resolve(".git"), LinkOption.NOFOLLOW_LINKS).toInstant();
            return git.isAfter(dir) ? git : dir;
        } catch (NoSuchFileException e) {
            return dir;
        } catch (IOException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPlannerTest"`
Expected: PASS (7 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeKind.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPlanner.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPlannerTest.java
git commit -m "feat(worker): worktree 정리 판정 — 폴더 이름 규칙(WorktreeKind)과 순수 판정(WorktreeCleanupPlanner)"
```

---

### Task 2: `WorktreeService.discard` — 레포 락 안 제거 절차

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java` (클래스 주석 26행, `doCreate` 71행·`doCreateForDeploy` 123행·`doCreateForDesign` 160행 경로, `remove` 180–191행 교체)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java:348-349` (디자인 수확 뒤 정리 호출)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeServiceDiscardTest.java`

**Interfaces:**
- Consumes: `WorktreeKind` (Task 1), `GitRepoCache.withRepoLock(String, RepoOp<T>)`, `RepoCacheCleanupJob.deleteTree(Path)` → `DeleteResult(long freedBytes, int failures)` (같은 패키지 static)
- Produces:
  - `public Path worktreeDir(String repoKey, WorktreeKind kind, long taskId)`
  - `public void discard(String repoKey, WorktreeKind kind, long taskId)` — 레포 락을 잡고 `discardUnderLock`, 예외는 경고 로그로 삼킴
  - `public DiscardResult discardUnderLock(String repoKey, WorktreeKind kind, long taskId) throws IOException, InterruptedException` — 호출자가 레포 락 보유. 폴더 삭제 실패 시 `IOException`
  - `public enum DiscardResult { REMOVED, ABSENT, GIT_LOCKED }`
  - `static boolean isGitLocked(Path worktreeDir)`
  - 기존 `public void remove(File, File)`는 **삭제**(유일한 호출부를 `discard`로 교체)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
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

/** WorktreeService 제거 절차를 실제 git(file:// bare 원격)으로 검증. */
class WorktreeServiceDiscardTest {

    @TempDir Path tmp;

    private WorkerProperties props;
    private GitRepoCache cache;
    private WorktreeService worktrees;
    private RepoRef ref;
    private String key;
    private File cacheDir;

    private static void git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private static String gitOut(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());

        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        props = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, tmp.resolve("worktrees").toString(), null, null, null, null, null, null,
                null, deploy, null);
        cache = new GitRepoCache(props);
        worktrees = new WorktreeService(props, cache);
        ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        key = GitRemotes.localKey(ref);
        cacheDir = cache.ensureFresh(ref, "main").dir();
    }

    @Test
    void discards_a_task_worktree_its_metadata_and_its_local_branch() throws Exception {
        WorktreeService.CreatedWorktree wt = worktrees.create(cacheDir, key, "main", 7, "Add login");

        worktrees.discard(key, WorktreeKind.TASK, 7);

        assertThat(wt.dir()).doesNotExist();
        assertThat(gitOut(cacheDir, "worktree", "list", "--porcelain")).doesNotContain("task-7");
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-7*")).isBlank();
    }

    @Test
    void deletes_only_this_tasks_branches() throws Exception {
        worktrees.create(cacheDir, key, "main", 1, "one");
        worktrees.create(cacheDir, key, "main", 12, "twelve");

        worktrees.discard(key, WorktreeKind.TASK, 1);

        assertThat(worktrees.worktreeDir(key, WorktreeKind.TASK, 1)).doesNotExist();
        assertThat(worktrees.worktreeDir(key, WorktreeKind.TASK, 12)).exists();
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-12-twelve")).contains("task-12-twelve");
        assertThat(gitOut(cacheDir, "branch", "--list", "netismaker/task-1-one")).isBlank();
    }

    @Test
    void discards_a_deploy_worktree() throws Exception {
        File wt = worktrees.createForDeploy(cacheDir, key, "main", 3);

        worktrees.discard(key, WorktreeKind.DEPLOY, 3);

        assertThat(wt).doesNotExist();
        assertThat(gitOut(cacheDir, "worktree", "list", "--porcelain")).doesNotContain("deploy-3");
    }

    @Test
    void keeps_a_git_locked_worktree() throws Exception {
        WorktreeService.CreatedWorktree wt = worktrees.create(cacheDir, key, "main", 5, "keep me");
        git(cacheDir, "worktree", "lock", wt.dir().getAbsolutePath());

        WorktreeService.DiscardResult r = cache.withRepoLock(key,
                () -> worktrees.discardUnderLock(key, WorktreeKind.TASK, 5));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.GIT_LOCKED);
        assertThat(wt.dir()).exists();
    }

    @Test
    void absent_worktree_is_reported_absent() throws Exception {
        WorktreeService.DiscardResult r = cache.withRepoLock(key,
                () -> worktrees.discardUnderLock(key, WorktreeKind.DESIGN, 99));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.ABSENT);
    }

    @Test
    void without_a_cache_the_folder_is_deleted_directly() throws Exception {
        Path orphan = Files.createDirectories(worktrees.worktreeDir("ghost/repo", WorktreeKind.TASK, 4));
        Files.writeString(orphan.resolve("left.txt"), "x");

        WorktreeService.DiscardResult r = cache.withRepoLock("ghost/repo",
                () -> worktrees.discardUnderLock("ghost/repo", WorktreeKind.TASK, 4));

        assertThat(r).isEqualTo(WorktreeService.DiscardResult.REMOVED);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void discard_never_throws() {
        // 캐시도 폴더도 없는 키 — 경고만 남기고 조용히 끝나야 한다
        worktrees.discard("nobody/nothing", WorktreeKind.TASK, 1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceDiscardTest"`
Expected: 컴파일 실패 — `discard`·`discardUnderLock`·`worktreeDir`·`DiscardResult` 없음

- [ ] **Step 3: `WorktreeService` 수정**

클래스 주석 26행을 다음으로 바꾼다:

```java
 *  정리: 성공한 작업은 호출자가 끝에 discard로 지운다(구현=PR 생성 뒤, 배포=빌드·실행 뒤, 디자인=수확 뒤).
 *  실패분은 디버그용으로 남기고 WorktreeCleanupJob이 보존 기간(기본 7일) 뒤 지운다.
```

경로 계산을 한 곳으로 모은다 — `doCreate`의 `Paths.get(props.worktreeRoot(), repoKey, "task-" + taskId)`를 `worktreeDir(repoKey, WorktreeKind.TASK, taskId)`로, `doCreateForDeploy`의 `"deploy-" + taskId`를 `worktreeDir(repoKey, WorktreeKind.DEPLOY, taskId)`로, `doCreateForDesign`의 `"design-" + taskId`를 `worktreeDir(repoKey, WorktreeKind.DESIGN, taskId)`로 바꾼다.

기존 `remove(File repoCacheDir, File worktreeDir)` 메서드(180–191행)를 지우고 아래를 넣는다(import: `java.nio.file.LinkOption`):

```java
    public Path worktreeDir(String repoKey, WorktreeKind kind, long taskId) {
        return Paths.get(props.worktreeRoot(), repoKey, kind.dirName(taskId));
    }

    public enum DiscardResult { REMOVED, ABSENT, GIT_LOCKED }

    /**
     * 작업이 끝난 worktree 정리(best-effort) — 레포 락 안에서 discardUnderLock.
     * 실패는 경고만 남기고 삼킨다: 정리 실패가 작업 결과를 바꾸면 안 되고, 남은 폴더는
     * WorktreeCleanupJob이 보존 기간 뒤 다시 시도한다.
     */
    public void discard(String repoKey, WorktreeKind kind, long taskId) {
        try {
            DiscardResult r = repos.withRepoLock(repoKey, () -> discardUnderLock(repoKey, kind, taskId));
            if (r == DiscardResult.GIT_LOCKED) {
                log.info("worktree 정리 건너뜀(git worktree lock): {}", worktreeDir(repoKey, kind, taskId));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("worktree 정리 실패(주기 정리가 다시 시도): {} ({})",
                    worktreeDir(repoKey, kind, taskId), e.getMessage());
        }
    }

    /**
     * 제거 절차. 호출자가 repoKey의 레포 락을 쥐고 있어야 한다(WorktreeCleanupJob은 tryWithRepoLock 안에서 부른다).
     *  1) git worktree lock이 걸려 있으면 손대지 않는다(사람이 일부러 남긴 것)
     *  2) 캐시가 있으면 git worktree remove --force
     *  3) 폴더가 남았으면 링크를 따라가지 않는 재귀 삭제(Windows 읽기 전용 포함) — 하나라도 실패하면 IOException
     *  4) 캐시가 있으면 git worktree prune, 구현 worktree면 그 작업의 로컬 브랜치 삭제(이미 원격에 푸시됨)
     */
    public DiscardResult discardUnderLock(String repoKey, WorktreeKind kind, long taskId)
            throws IOException, InterruptedException {
        Path dir = worktreeDir(repoKey, kind, taskId);
        Path cache = Paths.get(props.reposDir(), repoKey);
        boolean hasCache = Files.isDirectory(cache.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        boolean present = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);

        if (present && isGitLocked(dir)) return DiscardResult.GIT_LOCKED;
        if (present && hasCache) {
            ProcessRunner.Result r = ProcessRunner.run(cache.toFile(),
                    List.of("git", "worktree", "remove", "--force", dir.toString()), GIT_TIMEOUT_SECONDS);
            if (r.exitCode() != 0) log.debug("git worktree remove 실패(폴더 직접 삭제로 진행): {}", r.stdout());
        }
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
            if (d.failures() > 0) {
                throw new IOException("worktree 폴더 삭제 실패 " + d.failures() + "건: " + dir);
            }
        }
        if (hasCache) {
            ProcessRunner.run(cache.toFile(), List.of("git", "worktree", "prune"), GIT_TIMEOUT_SECONDS);
            if (kind == WorktreeKind.TASK) deleteTaskBranches(cache.toFile(), taskId);
        }
        if (present) log.info("worktree 정리: {}", dir);
        return present ? DiscardResult.REMOVED : DiscardResult.ABSENT;
    }

    /**
     * {branch-prefix}task-{id} 와 {branch-prefix}task-{id}-{slug} 만 지운다 — task-1 정리가 task-12-…를 지우지 않게
     * '-' 경계로 매칭한다(doCreate의 브랜치 규칙과 같다). 다른 worktree가 체크아웃 중이면 git이 거부 → 경고만.
     */
    private void deleteTaskBranches(File cacheDir, long taskId) throws IOException, InterruptedException {
        String base = "refs/heads/" + props.branchPrefix() + "task-" + taskId;
        String out = ProcessRunner.requireSuccess(cacheDir,
                List.of("git", "for-each-ref", "--format=%(refname:short)", base, base + "-*"),
                GIT_TIMEOUT_SECONDS);
        String exact = props.branchPrefix() + "task-" + taskId;
        for (String branch : out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!branch.equals(exact) && !branch.startsWith(exact + "-")) continue;
            ProcessRunner.Result r = ProcessRunner.run(cacheDir, List.of("git", "branch", "-D", branch),
                    GIT_TIMEOUT_SECONDS);
            if (r.exitCode() != 0) log.warn("로컬 브랜치 삭제 실패: {} ({})", branch, r.stdout().trim());
        }
    }

    /**
     * worktree의 .git 파일이 가리키는 메타 디렉터리에 locked 표시가 있으면 true.
     * .git이 없으면 false. 읽을 수 없거나 형식이 다르면 true — 모르면 안 지운다.
     * gitdir은 절대경로 또는(git 2.48+ relative 모드) worktree 기준 상대경로.
     */
    static boolean isGitLocked(Path worktreeDir) {
        Path gitFile = worktreeDir.resolve(".git");
        if (Files.notExists(gitFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String line = Files.readString(gitFile).trim();
            if (!line.startsWith("gitdir:")) return true;
            Path meta = worktreeDir.resolve(line.substring("gitdir:".length()).trim()).normalize();
            return !Files.notExists(meta.resolve("locked"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
```

`deleteRecursively`는 `doCreate*`가 계속 쓰므로 남긴다.

- [ ] **Step 4: 디자인 수확 뒤 호출 교체** — `WorkerMainLoop.java` 348–349행:

```java
        // 수확 완료 후 design worktree는 best-effort 정리 (산출물은 DB로 감 — 보존 불필요)
        worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.DESIGN, task.id());
```

- [ ] **Step 5: 통과 확인 + 기존 테스트 회귀 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJobTest" --tests "com.hamonsoft.netismaker.workerdaemon.GitRepoCacheTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeServiceDiscardTest.java
git commit -m "feat(worker): worktree 제거 절차를 레포 락 안으로 — remove·잔여 삭제·prune·작업 브랜치 삭제, git lock 존중"
```

---

### Task 3: 성공 즉시 정리 — 구현(PR 생성 뒤)·배포(빌드·실행 뒤)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java:279-289` (구현 성공 보고 뒤)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:98-100` (`target.deploy` 성공 직후)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeImmediateDiscardTest.java`

**Interfaces:**
- Consumes: `WorktreeService.discard(String, WorktreeKind, long)` (Task 2), `GitRemotes.localKey(RepoRef)`
- Produces: 없음(동작 변경)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployFailedException;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 성공한 작업만 그 자리에서 worktree를 지우고, 실패·정리 예외는 결과에 영향이 없는지. */
class WorktreeImmediateDiscardTest {

    @TempDir Path tmp;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null);
    }

    private static WorkerTaskResponse task(WorkerTaskResponse.Kind kind) {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc", kind,
                List.of(), null, null, "netismaker/task-7-add-login", "abc1234def", List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    // ---- 구현 ----

    private record Loop(WorkerMainLoop loop, WorktreeService worktrees, ResultReporter reporter,
                        GitOpsService gitOps, ClaudeExecAdapter claude) {}

    private Loop implementationLoop(int claudeExit) throws Exception {
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        GitRepoCache repos = mock(GitRepoCache.class);
        ClaudeExecAdapter claude = mock(ClaudeExecAdapter.class);
        WorktreeService worktrees = mock(WorktreeService.class);
        GitOpsService gitOps = mock(GitOpsService.class);
        ResultReporter reporter = mock(ResultReporter.class);
        File dir = Files.createDirectories(tmp.resolve("wt")).toFile();

        when(http.nextTask()).thenReturn(Optional.of(task(WorkerTaskResponse.Kind.IMPLEMENTATION)));
        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(dir, "base123"));
        when(worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login")))
                .thenReturn(new WorktreeService.CreatedWorktree(dir, "netismaker/task-7-add-login"));
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(claudeExit, "done", 10, null));
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));

        WorkerMainLoop loop = new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class));
        return new Loop(loop, worktrees, reporter, gitOps, claude);
    }

    @Test
    void successful_implementation_discards_its_worktree_after_reporting() throws Exception {
        Loop l = implementationLoop(0);

        l.loop().pollAndProcess();

        InOrder order = inOrder(l.reporter(), l.worktrees());
        order.verify(l.reporter()).reportTerminal(eq(7L), any());
        order.verify(l.worktrees()).discard("acme/widgets", WorktreeKind.TASK, 7L);
    }

    @Test
    void failed_implementation_keeps_its_worktree() throws Exception {
        Loop l = implementationLoop(1);   // claude exit=1 → 구현실패 경로

        l.loop().pollAndProcess();

        verify(l.reporter()).reportTerminal(eq(7L), any());
        verify(l.worktrees(), never()).discard(anyString(), any(), anyLong());
    }

    // ---- 배포 ----

    private DeployService deployService(WorktreeService worktrees, DeployTarget target) throws Exception {
        GitRepoCache repos = mock(GitRepoCache.class);
        Path wt = Files.createDirectories(tmp.resolve("deploy-7"));
        Files.writeString(wt.resolve("Dockerfile"), "FROM scratch\nEXPOSE 8080\n");
        when(repos.fetchOnly(any(), eq("netismaker/task-7-add-login")))
                .thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "head456"));
        when(worktrees.createForDeploy(any(), eq("acme/widgets"), eq("netismaker/task-7-add-login"), eq(7L)))
                .thenReturn(wt.toFile());
        return new DeployService(props(), repos, worktrees, mock(ClaudeExecAdapter.class), target);
    }

    private static DeployTarget target(boolean succeed) {
        return new DeployTarget() {
            @Override
            public DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) {
                if (!succeed) throw new DeployFailedException("health check failed", "log");
                return new DeployResult("http://h:19000", "cid", 19000, spec.imageName(), "");
            }
            @Override public void stop(String containerName) { }
            @Override public DeployStatus status(String containerName) { return DeployStatus.UNKNOWN; }
        };
    }

    @Test
    void successful_deploy_discards_its_worktree() throws Exception {
        WorktreeService worktrees = mock(WorktreeService.class);

        deployService(worktrees, target(true)).deploy(task(WorkerTaskResponse.Kind.DEPLOY), line -> { });

        verify(worktrees).discard("acme/widgets", WorktreeKind.DEPLOY, 7L);
    }

    @Test
    void failed_deploy_keeps_its_worktree() throws Exception {
        WorktreeService worktrees = mock(WorktreeService.class);

        assertThatThrownBy(() -> deployService(worktrees, target(false))
                .deploy(task(WorkerTaskResponse.Kind.DEPLOY), line -> { }))
                .isInstanceOf(DeployService.DeployException.class);
        verify(worktrees, never()).discard(anyString(), any(), anyLong());
    }
}
```

> 구현자 메모: `DeployFailedException`·`DeployResult`의 생성자 인자 순서는 `workerdaemon/deploy/` 실제 코드에 맞춘다(테스트 컴파일 오류가 알려 준다). `WorkerTaskResponse` 생성자는 23개 필드 — 순서는 `dto/WorkerTaskResponse.java` 21–51행. 구현 경로의 프롬프트·PR 본문 렌더링이 null 필드로 NPE를 내면 해당 필드에 빈 값을 넣어 테스트용 task를 채운다(동작 코드는 바꾸지 않는다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest"`
Expected: FAIL — `successful_implementation_discards_its_worktree_after_reporting`·`successful_deploy_discards_its_worktree`에서 "Wanted but not invoked: worktreeService.discard(...)". 실패 경로 2개는 통과.

- [ ] **Step 3: 구현 성공 경로** — `WorkerMainLoop.java`의 `log.info("구현 완료 + PR 생성: …")` 바로 뒤에:

```java
        // 산출물은 원격 브랜치·PR에 있다 — worktree는 더 쓰지 않으므로 바로 정리(실패해도 결과 무관, 주기 정리가 재시도)
        worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.TASK, task.id());
```

- [ ] **Step 4: 배포 성공 경로** — `DeployService.java`의 `DeployTarget.DeployResult r = target.deploy(spec, logSink);` 바로 뒤에:

```java
            // 컨테이너는 이미지로 돈다 — 빌드 컨텍스트(worktree)는 더 쓰지 않는다. 재배포는 createForDeploy가 새로 만든다.
            worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.DEPLOY, task.id());
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeImmediateDiscardTest.java
git commit -m "feat(worker): 구현(PR 생성 뒤)·배포(실행 뒤) 성공 시 worktree 즉시 정리 — 실패분은 보존"
```

---

### Task 4: 진행 중 작업 조회 API `GET /worker/active-worktree-tasks` + 워커 클라이언트

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ActiveWorktreeTaskSummary.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java` (142행 `findDeployReconcilable` 뒤)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (486행 `listDeployReconcilable` 뒤)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/WorkerController.java` (클래스 주석 26행 목록, 69행 `deployedTasks` 뒤)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java` (81행 `deployedTasks` 뒤)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceWorktreeActiveTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/WorkerActiveWorktreeTasksIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record ActiveWorktreeTaskSummary(Long id, TaskStatus status)`
  - `List<Task> TaskRepository.findWorktreeActive()`
  - `List<ActiveWorktreeTaskSummary> WorkerService.listWorktreeActive()`
  - `List<ActiveWorktreeTaskSummary> WorkerHttpClient.activeWorktreeTasks()` — 실패 시 예외 전파

- [ ] **Step 1: 실패하는 단위 테스트 작성**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkerServiceWorktreeActiveTest {

    @Test
    void maps_worktree_active_tasks_to_id_and_status() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        WorkerService service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class),
                mock(TaskDesignRepository.class), mock(RepoCatalogRepository.class),
                mock(TaskStatusHistoryRepository.class), mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 21L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        when(taskRepo.findWorktreeActive()).thenReturn(List.of(t));

        assertThat(service.listWorktreeActive())
                .containsExactly(new ActiveWorktreeTaskSummary(21L, TaskStatus.IMPLEMENTING));
    }
}
```

- [ ] **Step 2: 실패하는 통합 테스트 작성** (Testcontainers — 이 PC에선 skip, CI가 실행)

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerActiveWorktreeTasksIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Long save(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", status.name(), "d", "user1", 3, List.of(), "claude-opus-4-8", "high");
        t.setStatus(status);
        return taskRepo.save(t).getId();
    }

    @Test
    void returns_only_the_four_worktree_in_use_statuses() throws Exception {
        List<Integer> expected = new ArrayList<>();
        for (TaskStatus s : TaskStatus.values()) {
            Long id = save(s);
            if (s == TaskStatus.IMPLEMENTING || s == TaskStatus.DESIGNING
                    || s == TaskStatus.DEPLOYING || s == TaskStatus.UNDEPLOYING) {
                expected.add(id.intValue());
            }
        }

        mvc.perform(get("/worker/active-worktree-tasks").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(expected.toArray())));
    }

    @Test
    void rejects_a_request_without_the_worker_key() throws Exception {
        mvc.perform(get("/worker/active-worktree-tasks"))
                .andExpect(status().is4xxClientError());
    }
}
```

> 구현자 메모: `Task.create`의 requester `"user1"`이 FK에 걸리면 `TaskDesignFlowIntegrationTest`처럼 `/api/tasks` POST로 만든 뒤 상태를 바꾼다.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceWorktreeActiveTest"`
Expected: 컴파일 실패 — `ActiveWorktreeTaskSummary`·`findWorktreeActive`·`listWorktreeActive` 없음

- [ ] **Step 4: DTO**

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;

/**
 * worktree 정리 잡(WorktreeCleanupJob)의 보호 목록 항목 — 워커가 claim해 worktree를 쓰는 중인 task.
 * worktree 경로는 워커가 규칙({kind}-{id})으로 유도하므로 id만 쓴다.
 */
public record ActiveWorktreeTaskSummary(Long id, TaskStatus status) {
}
```

- [ ] **Step 5: 리포지토리** — `TaskRepository.java`의 `findDeployReconcilable();` 뒤에:

```java
    /**
     * worktree 정리 잡 보호 목록 — 워커가 claim해 worktree를 쓰는 중인 task(구현중·디자인중·배포중·배포중지중).
     * 대기 상태는 아직 worktree를 안 쓰고 재시도 때 새로 만든다. soft-delete된 작업은 제외(상태가 안 바뀌어 영구 보호되는 것을 막음 — 방금 삭제분은 7일 나이 판정이 보호).
     * 워커가 죽어도 StaleTaskRecoveryJob이 네 상태를 회수하므로 목록에 영구히 남지 않는다.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.IMPLEMENTING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DESIGNING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOYING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOYING)
    """)
    List<Task> findWorktreeActive();
```

- [ ] **Step 6: 서비스** — `WorkerService.java`의 `listDeployReconcilable()` 뒤에:

```java
    /** worktree 정리 잡 보호 목록(구현중·디자인중·배포중·배포중지중). 워커 WorktreeCleanupJob이 회차마다 조회. */
    @Transactional(readOnly = true)
    public List<com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary> listWorktreeActive() {
        return taskRepo.findWorktreeActive().stream()
                .map(t -> new com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary(t.getId(), t.getStatus()))
                .toList();
    }
```

- [ ] **Step 7: 컨트롤러** — `WorkerController.java` 클래스 주석의 `GET  /worker/deployed-tasks` 줄 뒤에 ` *   GET  /worker/active-worktree-tasks ─► worktree 정리 보호 목록(구현중·디자인중·배포중·배포중지중)`를 넣고, `deployedTasks()` 뒤에:

```java
    @GetMapping("/active-worktree-tasks")
    public List<com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary> activeWorktreeTasks() {
        return workerService.listWorktreeActive();
    }
```

- [ ] **Step 8: 워커 클라이언트** — `WorkerHttpClient.java`의 `deployedTasks()` 뒤에:

```java
    /** worktree 정리 보호 목록(구현중·디자인중·배포중·배포중지중). 실패 시 예외 전파 — 호출부가 회차를 건너뛴다. */
    public List<com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary> activeWorktreeTasks() {
        List<com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary> body = http.get()
                .uri("/worker/active-worktree-tasks")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return body == null ? List.of() : body;
    }
```

- [ ] **Step 9: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceWorktreeActiveTest" --tests "com.hamonsoft.netismaker.controller.WorkerActiveWorktreeTasksIntegrationTest"`
Expected: 단위 PASS, 통합은 이 PC에서 SKIPPED(`RUN_TESTCONTAINERS` 없음) — CI에서 PASS 확인

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/ActiveWorktreeTaskSummary.java src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/main/java/com/hamonsoft/netismaker/controller/WorkerController.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceWorktreeActiveTest.java src/test/java/com/hamonsoft/netismaker/controller/WorkerActiveWorktreeTasksIntegrationTest.java
git commit -m "feat(api): GET /worker/active-worktree-tasks — worktree 정리 보호 목록(구현중·디자인중·배포중·배포중지중)"
```

---

### Task 5: 설정 `WorkerProperties.WorktreeCleanup` + yml

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java` (레코드 컴포넌트 끝 `RepoCacheCleanup repoCacheCleanup` 뒤, compact 생성자 181행 근처)
- Modify: `src/main/resources/application-worker.yml` (46–49행 `repo-cache-cleanup` 블록 뒤)
- Modify(생성자 인자 1개 추가 `, null`): `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PublicDnsConfigurationTest.java`, `DeployReconcileJobTest.java`, `GitOpsServicePushTest.java`, `GitRepoCacheTest.java`, `RepoCacheCleanupJobTest.java`, 그리고 Task 2·3에서 만든 `WorktreeServiceDiscardTest.java`, `WorktreeImmediateDiscardTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupPropertiesTest.java`

**Interfaces:**
- Produces: `record WorkerProperties.WorktreeCleanup(Boolean enabled, String cron, Integer retentionDays)` — 정규화 후 `enabled` non-null, `cron` non-blank, `retentionDays` ≥ 1. `WorkerProperties.worktreeCleanup()` non-null.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorktreeCleanupPropertiesTest {

    private static WorkerProperties bind(Map<String, Object> env) throws Exception {
        StandardEnvironment e = new StandardEnvironment();
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"))) {
            e.getPropertySources().addLast(ps);
        }
        e.getPropertySources().addFirst(new MapPropertySource("t", env));
        return Binder.get(e).bind("netis-maker.worker", WorkerProperties.class).get();
    }

    @Test
    void defaults_are_enabled_0230_and_seven_days() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of()).worktreeCleanup();
        assertThat(c.enabled()).isTrue();
        assertThat(c.cron()).isEqualTo("0 30 2 * * *");
        assertThat(c.retentionDays()).isEqualTo(7);
    }

    @Test
    void env_overrides_bind() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of(
                "WORKTREE_CLEANUP_ENABLED", "false",
                "WORKTREE_CLEANUP_CRON", "-",
                "WORKTREE_CLEANUP_RETENTION_DAYS", "14")).worktreeCleanup();
        assertThat(c.enabled()).isFalse();
        assertThat(c.cron()).isEqualTo("-");
        assertThat(c.retentionDays()).isEqualTo(14);
    }

    @Test
    void blank_values_fall_back_to_defaults() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of(
                "WORKTREE_CLEANUP_CRON", "",
                "WORKTREE_CLEANUP_RETENTION_DAYS", "")).worktreeCleanup();
        assertThat(c.cron()).isEqualTo("0 30 2 * * *");
        assertThat(c.retentionDays()).isEqualTo(7);
    }

    @Test
    void zero_or_negative_retention_fails_binding_naming_the_setting() {
        for (String v : new String[]{"0", "-3"}) {
            assertThatThrownBy(() -> bind(Map.of("WORKTREE_CLEANUP_RETENTION_DAYS", v)))
                    .isInstanceOf(BindException.class)
                    .rootCause().hasMessageContaining("WORKTREE_CLEANUP_RETENTION_DAYS");
        }
    }

    @Test
    void invalid_cron_fails_binding_naming_the_setting() {
        assertThatThrownBy(() -> bind(Map.of("WORKTREE_CLEANUP_CRON", "every night")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("WORKTREE_CLEANUP_CRON");
    }

    @Test
    void absent_block_gets_defaults_in_the_record_itself() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        WorkerProperties p = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        assertThat(p.worktreeCleanup().retentionDays()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPropertiesTest"`
Expected: 컴파일 실패 — `worktreeCleanup()`·`WorktreeCleanup` 없음

- [ ] **Step 3: 레코드 추가** — `WorkerProperties.java` 컴포넌트 목록 끝을

```java
        // 레포 캐시 자동 정리 (RepoCacheCleanupJob)
        RepoCacheCleanup repoCacheCleanup,
        // worktree 보존 기간 정리 (WorktreeCleanupJob)
        WorktreeCleanup worktreeCleanup
) {
```

로 바꾸고, `RepoCacheCleanup` 레코드 정의 뒤에:

```java
    /**
     * worktree 보존 기간 정리. 성공한 작업의 worktree는 작업 끝에 바로 지우고(WorktreeService.discard),
     * 이 잡은 실패 보존분·과거 누적분 중 만든 지 retention-days가 지난 것을 지운다(진행 중 작업은 API로 보호).
     * retention-days는 생략·빈 값이면 7, 0 이하는 설정 실수로 보고 부팅을 막는다(조용히 기본값으로 바꾸지 않는다).
     */
    public record WorktreeCleanup(Boolean enabled, String cron, Integer retentionDays) {
        public WorktreeCleanup {
            if (enabled == null) enabled = true;
            cron = (cron == null || cron.isBlank()) ? "0 30 2 * * *" : cron.trim();
            if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(cron) && !CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException(
                        "WORKTREE_CLEANUP_CRON은 6필드 cron(초 분 시 일 월 요일) 또는 '-'이어야 한다: '" + cron + "'");
            }
            if (retentionDays == null) retentionDays = 7;
            if (retentionDays <= 0) {
                throw new IllegalArgumentException(
                        "WORKTREE_CLEANUP_RETENTION_DAYS는 1 이상이어야 한다: " + retentionDays);
            }
        }
    }
```

compact 생성자 끝(`if (repoCacheCleanup == null) …` 뒤)에:

```java
        if (worktreeCleanup == null) worktreeCleanup = new WorktreeCleanup(null, null, null);
```

- [ ] **Step 4: yml** — `application-worker.yml`의 `unused-days: ${REPO_CACHE_CLEANUP_UNUSED_DAYS:30}` 뒤에 같은 들여쓰기로:

```yaml
    # worktree 보존 기간 정리 (WorktreeCleanupJob) — 성공한 작업의 worktree는 작업 끝에 바로 지우고,
    # 이 잡은 실패 보존분·과거 누적분 중 만든 지 retention-days가 지난 것을 지운다(진행 중 작업은 API 조회로 보호,
    # 조회 실패면 그 회차는 아무것도 안 지움). 03:00 레포 캐시 정리보다 먼저 돌아 같은 밤에 캐시 회수 후보가 된다.
    worktree-cleanup:
      enabled: ${WORKTREE_CLEANUP_ENABLED:true}
      cron: ${WORKTREE_CLEANUP_CRON:0 30 2 * * *}
      retention-days: ${WORKTREE_CLEANUP_RETENTION_DAYS:7}
```

- [ ] **Step 5: 기존 생성자 호출부에 인자 추가** — 위 Files 목록의 테스트 7개에서 `new WorkerProperties(…, deploy, X)` 마지막 인자 `X` 뒤에 `, null`을 붙인다(예: `RepoCacheCleanupJobTest`는 `new WorkerProperties.RepoCacheCleanup(enabled, cron, 30))` → `new WorkerProperties.RepoCacheCleanup(enabled, cron, 30), null)`).

Run: `grep -rn "new WorkerProperties(" src/test src/main` — 모든 호출부가 27개 인자인지 확인.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPropertiesTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesYamlBindingTest" --tests "com.hamonsoft.netismaker.workerdaemon.RepoCacheCleanupJobTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceDiscardTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeImmediateDiscardTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java src/main/resources/application-worker.yml src/test/java
git commit -m "feat(worker): worktree 정리 설정 — 02:30·7일 기본, 보존 기간 0 이하·잘못된 cron은 부팅 실패"
```

---

### Task 6: `WorktreeCleanupJob` — 새벽 주기 정리

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupJob.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupJobTest.java`

**Interfaces:**
- Consumes: `WorktreeKind.parse` · `WorktreeCleanupPlanner.judge/lastActivity` (Task 1), `WorktreeService.discardUnderLock` → `DiscardResult` (Task 2), `WorkerHttpClient.activeWorktreeTasks()` (Task 4), `WorkerProperties.worktreeCleanup()` (Task 5), `GitRepoCache.tryWithRepoLock(String, RepoOp<T>)` → `Optional<T>`
- Produces: `enum WorktreeCleanupJob.Outcome { REMOVED, ACTIVE, TOO_NEW, BUSY, GIT_LOCKED, UNKNOWN, FAILED }`, `record Result(boolean skipped, String skipReason, Map<Path, Outcome> outcomes)` + `long count(Outcome)`, `Result runOnce()`(package-private, 테스트 직접 호출), `void cleanup()`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupJob.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** worktree 주기 정리를 실제 git 캐시/worktree로 검증한다. 나이는 폴더·.git mtime을 과거로 돌려 흉내 낸다. */
class WorktreeCleanupJobTest {

    @TempDir Path tmp;

    private Path worktreeRoot;
    private WorkerProperties props;
    private GitRepoCache cache;
    private WorktreeService worktrees;
    private WorkerHttpClient http;
    private WorktreeCleanupJob job;
    private String key;
    private File cacheDir;

    private static void git(File dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    private WorkerProperties propsWith(Boolean enabled) {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, tmp.resolve("repos").toString(), null,
                null, null, null, null, null, worktreeRoot.toString(), null, null, null, null, null, null,
                null, deploy, null, new WorkerProperties.WorktreeCleanup(enabled, null, 7));
    }

    @BeforeEach
    void setUp() throws Exception {
        // 잡은 worktree-root 실경로 기준으로 결과 키를 만든다 — @TempDir가 단축·링크 경로여도 같은 기준이 되게
        worktreeRoot = Files.createDirectories(tmp.resolve("worktrees")).toRealPath();
        props = propsWith(true);
        cache = new GitRepoCache(props);
        worktrees = new WorktreeService(props, cache);
        http = mock(WorkerHttpClient.class);
        when(http.activeWorktreeTasks()).thenReturn(List.of());
        job = new WorktreeCleanupJob(props, cache, worktrees, http);

        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("a.txt"), "hello");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());
        key = GitRemotes.localKey(ref);
        cacheDir = cache.ensureFresh(ref, "main").dir();
    }

    private static void age(Path wt, int days) throws Exception {
        FileTime t = FileTime.from(Instant.now().minus(Duration.ofDays(days)));
        Path gitFile = wt.resolve(".git");
        if (Files.exists(gitFile)) Files.setLastModifiedTime(gitFile, t);
        Files.setLastModifiedTime(wt, t);
    }

    @Test
    void removes_old_worktrees_and_keeps_new_and_active_ones() throws Exception {
        Path oldTask = worktrees.create(cacheDir, key, "main", 1, "old").dir().toPath();
        Path oldDeploy = worktrees.createForDeploy(cacheDir, key, "main", 2).toPath();
        Path fresh = worktrees.create(cacheDir, key, "main", 3, "fresh").dir().toPath();
        Path active = worktrees.create(cacheDir, key, "main", 4, "running").dir().toPath();
        age(oldTask, 8);
        age(oldDeploy, 30);
        age(fresh, 6);
        age(active, 30);
        when(http.activeWorktreeTasks()).thenReturn(List.of(new ActiveWorktreeTaskSummary(4L, TaskStatus.IMPLEMENTING)));

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.skipped()).isFalse();
        assertThat(r.outcomes())
                .containsEntry(oldTask, Outcome.REMOVED)
                .containsEntry(oldDeploy, Outcome.REMOVED)
                .containsEntry(fresh, Outcome.TOO_NEW)
                .containsEntry(active, Outcome.ACTIVE);
        assertThat(oldTask).doesNotExist();
        assertThat(oldDeploy).doesNotExist();
        assertThat(fresh).exists();
        assertThat(active).exists();
    }

    @Test
    void active_lookup_failure_skips_the_whole_round() throws Exception {
        Path oldTask = worktrees.create(cacheDir, key, "main", 1, "old").dir().toPath();
        age(oldTask, 30);
        when(http.activeWorktreeTasks()).thenThrow(new RestClientException("404 Not Found"));

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.skipped()).isTrue();
        assertThat(r.outcomes()).isEmpty();
        assertThat(oldTask).exists();
    }

    @Test
    void counts_locked_worktrees_without_deleting() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 5, "keep").dir().toPath();
        git(cacheDir, "worktree", "lock", wt.toString());
        age(wt, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(wt, Outcome.GIT_LOCKED);
        assertThat(wt).exists();
    }

    @Test
    void a_busy_repo_lock_skips_that_worktree_this_round() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 6, "busy").dir().toPath();
        age(wt, 30);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                cache.withRepoLock(key, () -> { held.countDown(); release.await(); return null; });
            } catch (Exception ignored) { }
        });
        holder.start();
        held.await();
        try {
            WorktreeCleanupJob.Result r = job.runOnce();
            assertThat(r.outcomes()).containsEntry(wt, Outcome.BUSY);
            assertThat(wt).exists();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void rejudges_inside_the_repo_lock() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 7, "retry").dir().toPath();
        age(wt, 30);
        // 락 밖 1차 판정(=REMOVE)과 락 획득 사이에 재시도가 worktree를 새로 만든 상황:
        // 락을 잡기 직전에 mtime을 지금으로 바꾸는 GitRepoCache로 끼워 넣는다
        GitRepoCache racing = new GitRepoCache(props) {
            @Override
            public <T> Optional<T> tryWithRepoLock(String repoKey, RepoOp<T> op)
                    throws IOException, InterruptedException {
                Files.setLastModifiedTime(wt, FileTime.from(Instant.now()));
                return super.tryWithRepoLock(repoKey, op);
            }
        };
        WorktreeCleanupJob racingJob = new WorktreeCleanupJob(props, racing, new WorktreeService(props, racing), http);

        WorktreeCleanupJob.Result r = racingJob.runOnce();

        assertThat(r.outcomes()).containsEntry(wt, Outcome.TOO_NEW);
        assertThat(wt).exists();
    }

    @Test
    void ignores_unrecognized_names_and_symlinks() throws Exception {
        Path odd = Files.createDirectories(worktreeRoot.resolve("acme/widgets/task-01"));
        Path notes = Files.createDirectories(worktreeRoot.resolve("acme/widgets/notes"));
        age(odd, 30);
        age(notes, 30);
        Path outside = Files.createDirectories(tmp.resolve("outside/keep"));
        Files.writeString(outside.resolve("precious.txt"), "x");
        Path link = worktreeRoot.resolve("acme/widgets/task-77");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            assumeTrue(false, "심볼릭 링크 권한 없음(Windows 개발자 모드 꺼짐)");
        }

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).doesNotContainKeys(odd, notes, link);
        assertThat(odd).exists();
        assertThat(notes).exists();
        assertThat(outside.resolve("precious.txt")).exists();
    }

    @Test
    void orphan_worktree_without_cache_is_deleted_directly() throws Exception {
        Path orphan = Files.createDirectories(worktreeRoot.resolve("ghost/repo/design-9"));
        Files.writeString(orphan.resolve("left.txt"), "x");
        age(orphan, 30);

        WorktreeCleanupJob.Result r = job.runOnce();

        assertThat(r.outcomes()).containsEntry(orphan, Outcome.REMOVED);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void another_process_holding_the_job_lock_skips_the_round() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 8, "x").dir().toPath();
        age(wt, 30);
        try (FileChannel ch = FileChannel.open(worktreeRoot.resolve(WorktreeCleanupJob.JOB_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            WorktreeCleanupJob.Result r = job.runOnce();
            assertThat(r.skipped()).isTrue();
        }
        assertThat(wt).exists();
    }

    @Test
    void disabled_job_does_nothing() throws Exception {
        Path wt = worktrees.create(cacheDir, key, "main", 9, "x").dir().toPath();
        age(wt, 30);
        WorktreeCleanupJob disabled = new WorktreeCleanupJob(propsWith(false), cache, worktrees, http);

        disabled.cleanup();

        assertThat(wt).exists();
        verify(http, never()).activeWorktreeTasks();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupJobTest"`
Expected: 컴파일 실패 — `WorktreeCleanupJob` 없음

- [ ] **Step 3: 구현**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * worktree 보존 기간 정리 잡 (worker 프로파일). worktree-cleanup.cron(기본 매일 02:30)마다
 * worktree-root/{localKey}/{task|deploy|design}-{id} 중 만든 지 retention-days(기본 7일)가 지난 것을 지운다.
 * 성공한 작업의 worktree는 작업 끝에 이미 지워지므로(WorktreeService.discard) 대상은 실패 보존분과 과거 누적분이다.
 * 03:00 레포 캐시 정리보다 먼저 돌아, worktree가 빠진 캐시가 같은 밤 회수 후보가 된다.
 *
 *  건너뜀 (모르면 안 지운다 — fail-closed):
 *   - 진행 중: API(GET /worker/active-worktree-tasks)의 구현중·디자인중·배포중·배포중지중 작업. 조회가 실패하면
 *              (네트워크·인증·구버전 API 404) 회차 전체를 건너뛴다.
 *   - 기간 미달 / 판단 불가: WorktreeCleanupPlanner.
 *   - 사용 중: 그 레포의 락(GitRepoCache.tryWithRepoLock)을 즉시 못 잡으면 이번 회차는 넘긴다.
 *              락 안에서 다시 판정한다 — 그사이 재시도가 worktree를 새로 만들었으면 mtime이 새것이라 남는다.
 *   - git worktree lock: 사람이 일부러 남긴 것(WorktreeService.isGitLocked).
 *   - 이름이 규칙과 다르거나(WorktreeKind.parse) 어느 단계든 링크·정션이면 손대지 않는다.
 *
 *  다중 워커: 같은 worktree-root를 쓰는 워커들은 worktree-root/.worktree-cleanup.lock을 비차단으로 잡은 한 프로세스만 정리.
 *  스케줄은 정규화된 설정값(cfg.cron())으로 등록한다(RepoCacheCleanupJob과 같은 이유).
 */
@Component
@Profile("worker")
@Slf4j
public class WorktreeCleanupJob implements SchedulingConfigurer {

    static final String JOB_LOCK = ".worktree-cleanup.lock";

    private final WorkerProperties props;
    private final GitRepoCache repos;
    private final WorktreeService worktrees;
    private final WorkerHttpClient http;
    private final WorkerProperties.WorktreeCleanup cfg;

    public WorktreeCleanupJob(WorkerProperties props, GitRepoCache repos, WorktreeService worktrees,
                              WorkerHttpClient http) {
        this.props = props;
        this.repos = repos;
        this.worktrees = worktrees;
        this.http = http;
        this.cfg = props.worktreeCleanup();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (Boolean.FALSE.equals(cfg.enabled())) return;
        registrar.addCronTask(this::cleanup, cfg.cron());
    }

    enum Outcome { REMOVED, ACTIVE, TOO_NEW, BUSY, GIT_LOCKED, UNKNOWN, FAILED }

    /**
     * @param skipped    회차 전체를 건너뜀(skipReason에 이유)
     * @param outcomes   후보 worktree 경로 → 결과
     */
    record Result(boolean skipped, String skipReason, Map<Path, Outcome> outcomes) {
        long count(Outcome o) {
            return outcomes.values().stream().filter(o::equals).count();
        }

        static Result skip(String reason) {
            return new Result(true, reason, Map.of());
        }
    }

    public void cleanup() {
        if (Boolean.FALSE.equals(cfg.enabled())) return;
        long start = System.currentTimeMillis();
        try {
            Result r = runOnce();
            if (r.skipped()) {
                log.info("worktree 정리 건너뜀 — {}", r.skipReason());
                return;
            }
            log.info("worktree 정리 완료 ({}일 기준, {}ms) — 삭제 {} / 진행 중 {} / 기간 미달 {} / 사용 중(락) {} "
                            + "/ git lock {} / 판단 불가 {} · 실패 {}",
                    cfg.retentionDays(), System.currentTimeMillis() - start,
                    r.count(Outcome.REMOVED), r.count(Outcome.ACTIVE), r.count(Outcome.TOO_NEW),
                    r.count(Outcome.BUSY), r.count(Outcome.GIT_LOCKED), r.count(Outcome.UNKNOWN),
                    r.count(Outcome.FAILED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("worktree 정리 실패 (다음 주기 재시도): {}", e.toString());
        }
    }

    /** 1회 실행. 스케줄 없이 테스트에서 직접 호출. */
    Result runOnce() throws IOException, InterruptedException {
        Path root = guardedRoot();
        if (root == null) return new Result(false, null, Map.of());

        try (FileChannel ch = FileChannel.open(root.resolve(JOB_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock jobLock = tryLockOrNull(ch);
            if (jobLock == null) return Result.skip("같은 worktree-root를 다른 워커가 정리 중");
            try (jobLock) {
                Set<Long> active;
                try {
                    active = http.activeWorktreeTasks().stream()
                            .map(ActiveWorktreeTaskSummary::id)
                            .collect(Collectors.toSet());
                } catch (Exception e) {
                    log.warn("worktree 정리: 진행 중 작업 조회 실패 — 이번 회차 건너뜀 (fail-closed): {}", e.getMessage());
                    return Result.skip("진행 중 작업 조회 실패");
                }
                return cleanUnder(root, active);
            }
        }
    }

    /** worktree-root 실경로. 없으면 null(할 일 없음). 비었거나 홈·루트면 거부. */
    private Path guardedRoot() throws IOException {
        String dir = props.worktreeRoot();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("worktree-root(WORKTREE_ROOT)가 비어 있어 정리를 거부한다");
        }
        Path configured = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(configured)) return null;
        Path root = configured.toRealPath();
        Path home = Paths.get(System.getProperty("user.home")).toRealPath();
        if (root.getParent() == null || root.equals(home)) {
            throw new IllegalStateException("worktree-root가 홈·파일시스템 루트라 정리를 거부한다: " + root);
        }
        return root;
    }

    private Result cleanUnder(Path root, Set<Long> active) throws IOException, InterruptedException {
        Map<Path, Outcome> outcomes = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (Path a : plainDirs(root)) {
            for (Path b : plainDirs(a)) {
                String localKey = a.getFileName() + "/" + b.getFileName();
                for (Path wt : plainDirs(b)) {
                    Optional<WorktreeKind.Parsed> parsed = WorktreeKind.parse(wt.getFileName().toString());
                    if (parsed.isEmpty()) continue;
                    outcomes.put(wt, cleanOne(wt, localKey, parsed.get(), active, now));
                }
            }
        }
        return new Result(false, null, outcomes);
    }

    private Outcome cleanOne(Path wt, String localKey, WorktreeKind.Parsed p, Set<Long> active, Instant now)
            throws InterruptedException {
        Outcome pre = toOutcome(WorktreeCleanupPlanner.judge(p.taskId(), active,
                WorktreeCleanupPlanner.lastActivity(wt), now, cfg.retentionDays()));
        if (pre != Outcome.REMOVED) return pre;
        try {
            Optional<Outcome> locked = repos.tryWithRepoLock(localKey, () -> {
                // 락 안 재판정: 1차 판정 뒤 재시도가 worktree를 새로 만들었을 수 있다
                Outcome again = toOutcome(WorktreeCleanupPlanner.judge(p.taskId(), active,
                        WorktreeCleanupPlanner.lastActivity(wt), Instant.now(), cfg.retentionDays()));
                if (again != Outcome.REMOVED) return again;
                return switch (worktrees.discardUnderLock(localKey, p.kind(), p.taskId())) {
                    case REMOVED, ABSENT -> Outcome.REMOVED;
                    case GIT_LOCKED -> Outcome.GIT_LOCKED;
                };
            });
            return locked.orElse(Outcome.BUSY);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("worktree 정리 실패: {} ({})", wt, e.getMessage());
            return Outcome.FAILED;
        }
    }

    private static Outcome toOutcome(WorktreeCleanupPlanner.Verdict v) {
        return switch (v) {
            case REMOVE -> Outcome.REMOVED;
            case ACTIVE -> Outcome.ACTIVE;
            case TOO_NEW -> Outcome.TOO_NEW;
            case UNKNOWN -> Outcome.UNKNOWN;
        };
    }

    /** dir 바로 아래 '진짜' 디렉터리만(링크·정션·파일·'.'으로 시작하는 잡 내부 항목 제외). */
    private static List<Path> plainDirs(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (p.getFileName().toString().startsWith(".")) continue;
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther()) out.add(p);
            }
        }
        return out;
    }

    private static FileLock tryLockOrNull(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }
}
```

> 구현자 메모: `outcomes`의 키는 `worktreeRoot`의 **실경로**(`toRealPath`) 기준이다. 테스트 `setUp`이 `worktreeRoot`를 실경로로 잡아 같은 기준을 쓴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupJobTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest"`
Expected: PASS (심볼릭 링크 테스트는 권한 없으면 SKIPPED)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupJob.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeCleanupJobTest.java
git commit -m "feat(worker): worktree 보존 기간 정리 잡 — 02:30, 7일, 진행 중 API 보호(fail-closed)·레포 락 안 재판정"
```

---

### Task 7: 문서 정정 + 전체 테스트

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/RepoCacheCleanupJob.java:54-56` (클래스 주석 한계 문구), 124–128행(회차 로그 주석)
- Modify: `src/main/resources/application-worker.yml:42-43` (레포 캐시 정리 ⚠️ 경고)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java:48` (`RepoCacheCleanup` 주석 괄호)
- Modify: `CLAUDE.md` (환경 메모 "레포 캐시 자동 정리" 항목의 ⚠️ 문장, 자주 보는 코드 표)
- Modify: `TODOS.md` (worktree 보존 정책 → 완료)

- [ ] **Step 1: `RepoCacheCleanupJob` 주석** — 54–56행의 `⚠️ 한계: …회차 로그에 남긴다.`를 다음으로 교체:

```java
 *               성공한 작업의 worktree는 작업 끝에(WorktreeService.discard), 실패 보존분은 WorktreeCleanupJob이
 *               보존 기간(기본 7일) 뒤에 지우므로, 그 뒤 캐시가 미사용 기간을 넘기면 이 잡이 회수한다.
 *               어떤 worktree가 붙잡고 있는지는 회차 로그에 남긴다.
```

124행 주석 `// worktree 보존분은 지금 자동으로 정리되지 않는다 — 무엇을 치워야 디스크가 회수되는지 남긴다`를 `// 캐시를 붙잡고 있는 worktree(진행 중·보존 기간 안·git lock) — 왜 회수되지 않았는지 남긴다`로.

- [ ] **Step 2: yml 경고** — 42–43행을:

```yaml
    # 살아 있는 worktree(진행 중·보존 기간 안의 실패분·git lock)가 붙은 캐시와 락이 잡힌(사용 중) 캐시는 건너뛴다.
    # worktree는 성공 시 작업 끝에, 실패분은 worktree-cleanup이 보존 기간 뒤 지운다 — 붙잡은 worktree 경로는 회차 로그에 나온다.
```

- [ ] **Step 3: `WorkerProperties` 48행** — `(살아 있는 worktree가 붙은 캐시·사용 중인 캐시는 제외 — RepoCacheCleanupJob 참고)`는 그대로 두되 다음 줄에 ` * worktree 자체의 수명은 WorktreeCleanup 참고.`를 넣는다.

- [ ] **Step 4: `CLAUDE.md`** — 환경 메모의 레포 캐시 정리 항목에서 `⚠️ 구현 성공·배포 worktree는 지우는 곳이 없어 그 캐시는 회수되지 않는다 — 회차 로그 \`레포 캐시 보존(worktree N개가 사용 중)\`에 붙잡은 worktree 경로가 나온다(TODOS 'worktree 보존 정책').`를 다음으로 교체:

```markdown
캐시를 붙잡은 worktree는 회차 로그 `레포 캐시 보존(worktree N개가 사용 중)`에 경로가 나온다.
- **worktree 보존(워커)**: 성공한 작업의 worktree는 작업 끝에 바로 지운다(구현=PR 생성 보고 뒤 + 로컬 `task-N` 브랜치, 배포=빌드·실행 뒤, 디자인=수확 뒤 — `WorktreeService.discard`, best-effort). 실패분은 디버그용으로 남기고 `WorktreeCleanupJob`이 매일 02:30(`WORKTREE_CLEANUP_CRON`, `-`=끔) 만든 지 `WORKTREE_CLEANUP_RETENTION_DAYS`(기본 7, 0 이하는 부팅 실패)일 지난 것을 지운다(`WORKTREE_CLEANUP_ENABLED=false`로 끔). 진행 중 작업(구현중·디자인중·배포중·배포중지중)은 `GET /worker/active-worktree-tasks`로 보호하고 조회 실패면 회차 전체를 건너뛴다. 레포 락을 못 잡으면 다음 회차, 락 안에서 나이 재판정. `git worktree lock`된 worktree는 지우지 않는다(일부러 남기고 싶을 때 쓸 것). 배포 순서: API → 워커(구 API면 404 → 정리 건너뜀).
```

자주 보는 코드 표의 레포 캐시 정리 행 뒤에:

```markdown
| worktree 보존 정책 (성공 즉시 정리 · 7일 주기 정리 · 진행 중 API 보호 · git lock 존중) | `workerdaemon/WorktreeService.java`(`discard`/`discardUnderLock`), `workerdaemon/WorktreeCleanupJob.java`, `workerdaemon/WorktreeCleanupPlanner.java`(순수 판정), `workerdaemon/WorktreeKind.java`(폴더 이름 규칙), API `GET /worker/active-worktree-tasks`(`WorkerService.listWorktreeActive`) |
```

- [ ] **Step 5: `TODOS.md`** — `### [ ] worktree 보존 정책 (구현 성공·배포 worktree 정리)` 절 전체(What/Why/Context/우선순위)를 지우고, `## 완료` 아래 맨 위에:

```markdown
### [x] worktree 보존 정책 (2026-10-02)
성공한 작업의 worktree는 작업 끝에 바로 정리, 실패분은 `WorktreeCleanupJob`이 7일 뒤 정리(진행 중은 API로 보호, fail-closed). PR 병합 상태 추적은 하지 않는다 — 성공분은 원격 브랜치·이미지에 이미 남아 있어서. 스펙 `docs/superpowers/specs/2026-10-02-worktree-retention-design.md`.
```

- [ ] **Step 6: 전체 백엔드 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Windows 심볼릭 링크 권한 실패 3건은 main과 같은 기존 실패(2026-10-01 기록) — 그 외 실패가 없어야 한다. Testcontainers 테스트는 SKIPPED.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/RepoCacheCleanupJob.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java src/main/resources/application-worker.yml CLAUDE.md TODOS.md
git commit -m "docs: worktree 보존 정책 반영 — 레포 캐시 정리 한계 문구 정정, CLAUDE.md·TODOS 갱신"
```
