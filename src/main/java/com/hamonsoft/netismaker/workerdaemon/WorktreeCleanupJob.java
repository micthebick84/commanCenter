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
