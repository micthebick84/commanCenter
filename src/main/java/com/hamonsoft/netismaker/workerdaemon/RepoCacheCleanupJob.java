package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.util.FileDeletion;
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 레포 캐시 자동 정리 잡 (worker 프로파일). repo-cache-cleanup.cron(기본 매일 03:00)마다
 * repos-dir 아래 캐시 중 unused-days(기본 30일) 동안 쓰이지 않은 것을 지운다. 지운 캐시는 다음 사용 때
 * ensureFresh/fetchOnly가 디렉터리 부재를 보고 다시 clone한다.
 *
 *  대상: repos-dir/{localKey} — GitHub {owner}/{repo}, GitLab _gitlab/{경로 평탄화}. 둘 다 2단계라
 *        repos-dir/＊/＊ 중 `.git` 디렉터리가 있고 netisMaker 소유 표식({localKey}.lock 형제 파일 —
 *        withRepoLock이 clone 전에 항상 만든다)이 있는 것만 캐시로 본다. REPOS_DIR을 운영자 레포 폴더로
 *        잘못 잡아도 netisMaker가 만든 적 없는 레포는 건드리지 않는다. '.'으로 시작하는 최상위 항목은 잡 내부용.
 *        repos-dir이 비었거나(= JVM 현재 디렉터리) 홈·파일시스템 루트면 정리 자체를 거부한다.
 *
 *  건너뜀 (모르면 안 지운다 — fail-closed):
 *   - 최근 사용: {localKey}.last-used 마커와 git 메타 파일(FETCH_HEAD·HEAD·index·config 등) mtime 중 최댓값.
 *                마커가 없는 기존 캐시도 git 메타 파일로 판단하고, 읽을 수 있는 시각이 하나도 없거나
 *                부재가 아닌 이유(권한 등)로 못 읽는 근거가 하나라도 있으면 판단불가.
 *   - worktree: .git/worktrees/＊/gitdir이 실제 존재하는 경로를 가리키면 살아 있는 worktree
 *               (구현 실패 보존분·배포·디자인). 가리키는 곳이 확실히 없는(NoSuchFile) stale 메타만 무시한다
 *               (캐시째 지워진다). locked 표시, 읽을 수 없는 메타, 권한·I/O 오류로 존재 여부를 판단할 수
 *               없는 경로는 살아 있다고 본다 — Files.exists는 '모름'도 false라 쓰지 않는다. 부재가 확인돼도
 *               가장 가까운 존재 조상이 worktree-root 밖이거나 worktree-root 자체가 안 보이면(외장 볼륨 분리 등) 보존한다.
 *               ⚠️ 한계: 지금은 구현 성공(PR 생성) worktree와 배포 worktree도 지우는 곳이 없어 영구 보존된다
 *               (WorktreeService 'Phase 1은 보존'). 그래서 구현·배포를 한 번이라도 거친 캐시는 이 잡이 회수하지
 *               않는다 — worktree 보존 정책이 생겨야 회수된다. 어떤 worktree가 붙잡고 있는지는 회차 로그에 남긴다.
 *   - 사용 중: per-repo 락(GitRepoCache.tryWithRepoLock)을 즉시 못 잡으면 이번 회차는 넘긴다.
 *
 *  삭제 절차: 락 안에서 판단을 다시 한 번 확인한 뒤 캐시를 repos-dir/.trash/ 로 rename(원자적)만 하고
 *   락을 푼다. 실제 삭제는 락 밖에서 — 수GB 삭제 동안 같은 레포를 쓰려는 워커를 붙잡지 않고,
 *   삭제가 중간에 실패해도 반쯤 지워진 캐시가 원래 경로에 남지 않는다(다음 사용은 깨끗이 재clone).
 *   .trash에 남은 잔여물은 다음 회차에 다시 지운다. 락 파일은 지우지 않는다(다른 프로세스가
 *   열어 둔 파일을 지우면 락이 둘로 갈린다).
 *
 *  다중 워커(V1.2): 같은 repos-dir을 쓰는 워커들이 같은 시각에 잡을 돌려도 repos-dir/.repo-cache-cleanup.lock을
 *   비차단으로 잡은 한 프로세스만 정리하고 나머지는 이번 회차를 건너뛴다.
 *
 *  삭제는 repos-dir 하위로 엄격히 한정(정규화 + containment 확인)하고 심볼릭 링크·Windows 정션은 따라가지
 *   않는다(링크 자체만 지운다). Windows의 읽기 전용 파일(git pack/idx)은 속성을 풀고 지운다.
 *
 *  스케줄은 @Scheduled 플레이스홀더가 아니라 정규화된 설정값(cfg.cron())으로 등록한다(configureTasks).
 *
 *  관리자 UI 수동 트리거는 없다 — 워커는 HTTP 포트를 열지 않는다(web-application-type: none).
 */
@Component
@Profile("worker")
@Slf4j
public class RepoCacheCleanupJob implements SchedulingConfigurer {

    /** 삭제 대기 캐시를 옮겨 두는 repos-dir 하위 디렉터리. GitHub owner는 '.'으로 시작할 수 없어 충돌 없음. */
    static final String TRASH_DIR = ".trash";
    /** 같은 repos-dir을 쓰는 워커 간 잡 단위 배타 락. */
    static final String JOB_LOCK = ".repo-cache-cleanup.lock";

    /** 마커가 없을 때 '마지막 사용'으로 볼 git 메타(캐시 기준 상대경로). clone·fetch·checkout·set-url·worktree add가 갱신한다. */
    private static final List<String> GIT_ACTIVITY_FILES = List.of(
            ".git/FETCH_HEAD", ".git/HEAD", ".git/index", ".git/config", ".git/logs/HEAD", ".git/worktrees");

    private final WorkerProperties props;
    private final GitRepoCache repos;
    private final WorkerProperties.RepoCacheCleanup cfg;

    public RepoCacheCleanupJob(WorkerProperties props, GitRepoCache repos) {
        this.props = props;
        this.repos = repos;
        this.cfg = props.repoCacheCleanup();
    }

    /**
     * 스케줄 등록. @Scheduled(cron = "${...}")를 쓰지 않는 이유: 플레이스홀더는 빈 값(REPO_CACHE_CLEANUP_CRON=)을
     * 기본값으로 바꾸지 않아 워커 부팅이 통째로 실패한다. WorkerProperties가 정규화·검증한 값 하나만 쓴다.
     * enabled=false면 등록하지 않고, '-'(CRON_DISABLED)도 addCronTask가 등록하지 않는다.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (Boolean.FALSE.equals(cfg.enabled())) return;
        registrar.addCronTask(this::cleanup, cfg.cron());
    }

    public void cleanup() {
        if (Boolean.FALSE.equals(cfg.enabled())) return;
        long start = System.currentTimeMillis();
        try {
            Result r = runOnce();
            if (r.skipped()) {
                log.info("레포 캐시 정리 건너뜀 — 같은 repos-dir을 다른 워커가 정리 중");
                return;
            }
            log.info("레포 캐시 정리 완료 ({}일 미사용 기준, {}ms): 검사 {} · 삭제 {} (해제 {}) · 건너뜀 — "
                            + "최근 사용 {} / 사용 중(락) {} / worktree {} / 판단불가 {} · 실패 {} · 휴지통 잔여 {}",
                    cfg.unusedDays(), System.currentTimeMillis() - start,
                    r.outcomes().size(), r.count(Outcome.DELETED), humanBytes(r.freedBytes()),
                    r.count(Outcome.RECENT), r.count(Outcome.BUSY), r.count(Outcome.WORKTREE),
                    r.count(Outcome.UNKNOWN), r.count(Outcome.FAILED), r.trashLeft());
            // worktree 보존분은 지금 자동으로 정리되지 않는다 — 무엇을 치워야 디스크가 회수되는지 남긴다
            r.worktreeHolders().forEach((localKey, holders) ->
                    log.info("레포 캐시 보존(worktree {}개가 사용 중): {} ← {}",
                            holders.size(), localKey, abbreviate(holders)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("레포 캐시 정리 실패 (다음 주기 재시도): {}", e.toString());
        }
    }

    /** 캐시 1개에 대한 판단 결과. */
    enum Outcome { DELETED, RECENT, BUSY, WORKTREE, UNKNOWN, FAILED }

    /**
     * @param skipped         다른 워커가 잡 락을 쥐고 있어 이번 회차를 통째로 건너뜀
     * @param outcomes        localKey → 판단 결과 (검사한 캐시 전부)
     * @param worktreeHolders WORKTREE로 건너뛴 localKey → 캐시를 붙잡고 있는 worktree 설명(경로 등)
     * @param freedBytes      실제로 지운 파일 크기 합(이전 회차 휴지통 잔여 포함)
     * @param trashLeft       삭제가 끝나지 않아 .trash에 남은 항목 수(다음 회차 재시도)
     */
    record Result(boolean skipped, Map<String, Outcome> outcomes, Map<String, List<String>> worktreeHolders,
                  long freedBytes, int trashLeft) {
        long count(Outcome o) {
            return outcomes.values().stream().filter(o::equals).count();
        }
    }

    /** 1회 실행. 스케줄 없이 테스트에서 직접 호출. */
    Result runOnce() throws IOException, InterruptedException {
        String dir = props.reposDir();
        if (dir == null || dir.isBlank()) {
            // REPOS_DIR= (빈 값)이면 플레이스홀더 기본값이 아니라 ""로 바인딩된다 → JVM 현재 디렉터리를 지우게 된다
            throw new IllegalStateException("repos-dir(REPOS_DIR)이 비어 있어 정리를 거부한다");
        }
        Path configured = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(configured)) return new Result(false, Map.of(), Map.of(), 0, 0);
        // 링크로 잡힌 repos-dir이라도 containment는 실제 경로 기준으로 본다
        Path base = configured.toRealPath();
        if (base.getParent() == null || base.equals(realHome())) {
            throw new IllegalStateException("repos-dir이 홈·파일시스템 루트라 정리를 거부한다: " + base);
        }

        try (FileChannel ch = FileChannel.open(base.resolve(JOB_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock jobLock = tryLockOrNull(ch);
            if (jobLock == null) return new Result(true, Map.of(), Map.of(), 0, 0);
            try (jobLock) {
                return cleanUnder(base);
            }
        }
    }

    /** 홈 디렉터리 실제 경로. 못 구하면 null(비교 생략). */
    private static Path realHome() {
        try {
            return Paths.get(System.getProperty("user.home")).toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 다른 프로세스가 쥐고 있으면 null. 같은 JVM의 다른 인스턴스가 쥐고 있어도(OverlappingFileLockException) null. */
    private static FileLock tryLockOrNull(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private Result cleanUnder(Path base) throws IOException, InterruptedException {
        Path trash = base.resolve(TRASH_DIR);
        Files.createDirectories(trash);
        if (!Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) {
            // 누군가 .trash를 링크로 바꿔 놓았다 — 그 너머로 옮기거나 지우지 않는다
            throw new IOException("휴지통 경로가 디렉터리가 아님(링크?): " + trash);
        }

        // 1) 캐시 검사 — 대상은 락 안에서 .trash로 옮기기만 한다
        Instant cutoff = Instant.now().minus(Duration.ofDays(cfg.unusedDays()));
        Path worktreeRoot = realDirOrNull(props.worktreeRoot());
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        Map<String, List<String>> holders = new LinkedHashMap<>();
        for (Path owner : list(base)) {
            String ownerName = owner.getFileName().toString();
            if (ownerName.startsWith(".")) continue;                                // .trash·잡 락 등 내부용
            if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) continue;     // 락/마커 파일, 링크
            List<Path> cacheDirs;
            try {
                cacheDirs = list(owner);
            } catch (IOException e) {
                log.warn("레포 캐시 목록 읽기 실패 {} (이 항목만 건너뜀): {}", ownerName, e.toString());
                continue;
            }
            for (Path cacheDir : cacheDirs) {
                if (!Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) continue;
                String localKey = ownerName + "/" + cacheDir.getFileName();
                if (!Files.isDirectory(cacheDir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                    outcomes.put(localKey, Outcome.UNKNOWN);   // clone 캐시가 아님 — 손대지 않는다
                    continue;
                }
                // 소유 표식은 락 시도 전에 본다 — tryWithRepoLock이 락 파일을 새로 만들기 때문
                if (!Files.isRegularFile(owner.resolve(cacheDir.getFileName() + GitRepoCache.LOCK_SUFFIX),
                        LinkOption.NOFOLLOW_LINKS)) {
                    outcomes.put(localKey, Outcome.UNKNOWN);   // netisMaker가 만든 캐시라는 표식이 없음
                    continue;
                }
                Outcome o;
                try {
                    o = cleanOne(base, trash, cacheDir, localKey, cutoff, worktreeRoot, holders);
                } catch (IOException | RuntimeException e) {
                    log.warn("레포 캐시 정리 실패 {}: {}", localKey, e.toString());
                    o = Outcome.FAILED;
                }
                outcomes.put(localKey, o);
            }
        }

        // 2) .trash 비우기 (락 밖) — 이번 회차에 옮긴 것 + 이전 회차에 못 지운 잔여물
        long freed = 0;
        int left = 0;
        for (Path moved : list(trash)) {
            DeleteResult d = deleteContained(base, trash, moved);
            freed += d.freedBytes();
            if (d.failures() > 0) left++;
        }
        return new Result(false, outcomes, holders, freed, left);
    }

    /** 캐시 1개 판단 + (대상이면) 락 안에서 .trash로 이동. 실제 삭제는 호출자가 락 밖에서. */
    private Outcome cleanOne(Path base, Path trash, Path cacheDir, String localKey, Instant cutoff,
                             Path worktreeRoot, Map<String, List<String>> holders)
            throws IOException, InterruptedException {
        // 락 없이 1차 판단 — 대부분 여기서 끝나 락 경합을 만들지 않는다
        Outcome pre = judge(base, cacheDir, localKey, cutoff, worktreeRoot, holders);
        if (pre != null) return pre;

        Optional<Outcome> locked = repos.tryWithRepoLock(localKey, () -> {
            // 락 안 재확인: 1차 판단 뒤 다른 워커가 이 캐시를 쓰기 시작했을 수 있다(마커·worktree 갱신)
            Outcome again = judge(base, cacheDir, localKey, cutoff, worktreeRoot, holders);
            if (again != null) return again;
            requireCacheUnder(base, cacheDir);
            Path dest = trash.resolve(localKey.replace('/', '+') + "-" + System.currentTimeMillis());
            // 같은 파일시스템 안 rename — 원자적. 안 되면(열린 파일 등) 아무것도 바뀌지 않은 채 예외
            Files.move(cacheDir, dest, StandardCopyOption.ATOMIC_MOVE);
            log.info("레포 캐시 삭제: {} ({}일 넘게 미사용)", localKey, cfg.unusedDays());
            try {
                // 락 안이라 마커를 쓰는 쪽과 겹치지 않는다. 남아도 다음 사용 때 덮어써지므로 실패는 무시.
                Files.deleteIfExists(base.resolve(localKey + GitRepoCache.LAST_USED_SUFFIX));
            } catch (IOException ignore) {
                // 무해
            }
            return Outcome.DELETED;
        });
        return locked.orElse(Outcome.BUSY);
    }

    /**
     * 삭제 대상이면 null, 아니면 건너뛸 사유. 판단 근거를 못 읽으면 삭제하지 않는 쪽으로 기운다.
     * WORKTREE면 붙잡고 있는 worktree 목록을 holders에 남긴다(회차 로그용).
     */
    private Outcome judge(Path base, Path cacheDir, String localKey, Instant cutoff, Path worktreeRoot,
                          Map<String, List<String>> holders) {
        Optional<Instant> lastUsed = lastUsed(base, cacheDir, localKey);
        if (lastUsed.isEmpty()) return Outcome.UNKNOWN;
        if (!lastUsed.get().isBefore(cutoff)) return Outcome.RECENT;
        List<String> live = liveWorktrees(cacheDir, worktreeRoot);
        if (!live.isEmpty()) {
            holders.put(localKey, live);
            return Outcome.WORKTREE;
        }
        return null;
    }

    /**
     * 마커와 git 메타 파일 mtime 중 가장 최근. 하나도 못 읽거나, 없는 게 아니라 못 읽는(권한·I/O 오류)
     * 근거가 하나라도 있으면 empty(판단불가) — 그 근거가 '최근 사용'일 수 있다.
     */
    static Optional<Instant> lastUsed(Path base, Path cacheDir, String localKey) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(base.resolve(localKey + GitRepoCache.LAST_USED_SUFFIX));
        for (String rel : GIT_ACTIVITY_FILES) candidates.add(cacheDir.resolve(rel));
        Instant latest = null;
        for (Path p : candidates) {
            try {
                Instant t = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (latest == null || t.isAfter(latest)) latest = t;
            } catch (NoSuchFileException ignore) {
                // 없는 파일은 근거에서 뺀다
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 캐시를 붙잡고 있는 worktree 설명 목록 — .git/worktrees/＊/gitdir 중 실제 존재하는 경로를 가리키는 것들.
     * 비었으면 살아 있는 worktree 없음. 보통 worktree 디렉터리 경로이고, 경로를 알 수 없는 항목은 메타 디렉터리 경로 + 사유.
     * gitdir 내용은 worktree의 .git 파일 경로 — git 2.48+ relative 모드면 메타 디렉터리 기준 상대경로.
     * .git 파일만 사라지고 worktree 디렉터리가 남아 있어도 살아 있다고 본다(보존분 산출물 보호).
     * '없음'은 확인된 부재(Files.notExists = NoSuchFile)일 때만 — 권한·I/O 오류로 판단할 수 없으면 살아 있다고 본다.
     * 부재가 확인돼도 그 위치가 지금 보이는 worktree-root 아래일 때만 믿는다(unconfirmedAbsence).
     *
     * @param worktreeRoot worktree-root 실경로. 지금 접근할 수 없으면 null(부재를 하나도 믿지 않는다).
     */
    static List<String> liveWorktrees(Path cacheDir, Path worktreeRoot) {
        Path meta = cacheDir.resolve(".git").resolve("worktrees");
        List<String> live = new ArrayList<>();
        if (Files.notExists(meta, LinkOption.NOFOLLOW_LINKS)) return live;
        List<Path> entries;
        try {
            entries = list(meta);
        } catch (IOException e) {
            // 목록을 못 읽으면(권한·디렉터리 아님 등) 살아 있다고 본다
            live.add(meta + " (목록 읽기 실패)");
            return live;
        }
        for (Path wt : entries) {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(wt, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                continue;      // 그사이 prune됨
            } catch (IOException e) {
                live.add(wt + " (메타 읽기 실패)");
                continue;
            }
            if (attrs.isRegularFile()) continue;   // worktree 메타 디렉터리가 아님
            if (!attrs.isDirectory()) {            // 링크 등 — 판단하지 않는다
                live.add(wt + " (디렉터리 아님)");
                continue;
            }
            if (!Files.notExists(wt.resolve("locked"), LinkOption.NOFOLLOW_LINKS)) {
                live.add(wt + " (git worktree lock)");   // 의도적 보존
                continue;
            }
            String gitdir;
            try {
                gitdir = Files.readString(wt.resolve("gitdir")).trim();
            } catch (IOException e) {
                live.add(wt + " (gitdir 읽기 실패)");
                continue;
            }
            if (gitdir.isEmpty()) {
                live.add(wt + " (gitdir 비어 있음)");
                continue;
            }
            try {
                Path target = Paths.get(gitdir);
                if (!target.isAbsolute()) target = wt.resolve(target).normalize();
                Path parent = target.getParent();
                if (!Files.notExists(target)) {
                    // gitdir은 worktree의 .git 파일 — 운영자에겐 worktree 디렉터리를 보여 준다
                    boolean dotGit = parent != null && ".git".equals(String.valueOf(target.getFileName()));
                    live.add((dotGit ? parent : target).toString());
                } else if (parent != null && !Files.notExists(parent)) {
                    live.add(parent.toString());
                } else {
                    String why = unconfirmedAbsence(target, worktreeRoot);
                    if (why != null) live.add((parent != null ? parent : target) + " (" + why + ")");
                }
            } catch (InvalidPathException e) {
                live.add(wt + " (gitdir 경로 해석 실패)");
            }
        }
        return live;
    }

    /**
     * worktree 경로의 부재(NoSuchFile)를 stale로 믿어도 되면 null, 아니면 사유. worktree는 전부 worktree-root
     * 아래에 만든다(WorktreeService) — 가장 가까운 존재 조상이 worktree-root(실경로) 하위가 아니면 외장·네트워크
     * 볼륨 분리나 끊긴 링크로 위치 자체가 안 보이는 것일 수 있다. 그때 캐시를 지우면 볼륨이 돌아와도 그 worktree의
     * git(브랜치·push 안 된 커밋)이 끊기므로 '모름'으로 보존한다. WORKTREE_ROOT를 옮긴 뒤 옛 위치의 stale 메타도
     * 같은 이유로 보존된다(회차 로그에 사유가 남는다).
     */
    static String unconfirmedAbsence(Path missing, Path worktreeRoot) {
        if (worktreeRoot == null) return "worktree-root 접근 불가";
        Path p = missing.toAbsolutePath().normalize();
        while (p != null && Files.notExists(p)) p = p.getParent();
        if (p == null) return "존재하는 상위 경로 없음";
        if (!Files.exists(p)) return "상위 경로 존재 확인 불가: " + p;
        try {
            if (!p.toRealPath().startsWith(worktreeRoot)) return "worktree-root 밖 — 볼륨·링크 끊김?: " + p;
        } catch (IOException e) {
            return "상위 경로 해석 실패: " + p;
        }
        return null;
    }

    /** 설정 경로의 실제 디렉터리 경로. 비었거나 지금 접근할 수 없으면(볼륨 분리·끊긴 링크) null. */
    private static Path realDirOrNull(String dir) {
        if (dir == null || dir.isBlank()) return null;
        try {
            Path real = Paths.get(dir).toAbsolutePath().normalize().toRealPath();
            return Files.isDirectory(real) ? real : null;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    /** 로그용 — 앞 5개만 보이고 나머지는 개수로. */
    static String abbreviate(List<String> items) {
        int shown = Math.min(items.size(), 5);
        String head = String.join(", ", items.subList(0, shown));
        return items.size() > shown ? head + " 외 " + (items.size() - shown) + "개" : head;
    }

    /** 이동 직전 방어: base 바로 아래 2단계의 실제 디렉터리(링크 아님)여야 한다. */
    private static void requireCacheUnder(Path base, Path cacheDir) throws IOException {
        Path n = cacheDir.toAbsolutePath().normalize();
        if (!n.startsWith(base) || n.getNameCount() != base.getNameCount() + 2
                || !Files.isDirectory(n, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(n.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !n.toRealPath().equals(n)) {
            throw new IOException("repos-dir 하위 캐시 경로가 아님: " + cacheDir);
        }
    }

    /** trash 바로 아래 항목만 지운다. 그 밖을 가리키면 손대지 않는다. */
    private static DeleteResult deleteContained(Path base, Path trash, Path target) {
        Path n = target.toAbsolutePath().normalize();
        if (!n.startsWith(base) || !trash.equals(n.getParent())) {
            log.warn("repos-dir/.trash 밖 경로 삭제 거부: {}", target);
            return new DeleteResult(0, 1);
        }
        DeleteResult d = deleteTree(n);
        if (d.failures() > 0) {
            log.warn("레포 캐시 휴지통 삭제 미완료 {} — {}개 항목 실패, 다음 회차 재시도", n.getFileName(), d.failures());
        }
        return d;
    }

    record DeleteResult(long freedBytes, int failures) {}

    /**
     * root 이하를 지운다. 링크를 따라가지 않는다 — FOLLOW_LINKS 없이 걸으면 심볼릭 링크는 visitFile로 와서
     * 링크 자체만 지워지고, Windows 정션(리파스 포인트)은 isOther라 안으로 들어가지 않고 링크만 지운다.
     * 실패 항목이 있어도 나머지는 계속 지워 최대한 비운다.
     */
    static DeleteResult deleteTree(Path root) {
        long[] freed = {0};
        int[] failures = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // root 자신이 정션이어도 마찬가지 — 대상 쪽으로 내려가지 않는다
                    if (attrs.isOther()) {
                        if (!deleteEntry(dir)) failures[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (deleteEntry(file)) {
                        if (attrs.isRegularFile()) freed[0] += attrs.size();
                    } else {
                        failures[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (!(exc instanceof NoSuchFileException)) {
                        log.debug("삭제 대상 읽기 실패: {} ({})", file, exc.toString());
                        failures[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!deleteEntry(dir)) failures[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failures[0]++;
        }
        return new DeleteResult(freed[0], failures[0]);
    }

    /** 항목 1개 삭제. 이미 없으면 성공으로 본다. Windows: git pack/idx는 읽기 전용 속성이라 FileDeletion이 풀고 지운다. */
    private static boolean deleteEntry(Path p) {
        try {
            FileDeletion.deleteClearingReadOnly(p);
            return true;
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException e) {
            log.debug("삭제 실패: {} ({})", p, e.toString());
            return false;
        }
    }

    private static List<Path> list(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) out.add(p);
        }
        return out;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i]);
    }
}
