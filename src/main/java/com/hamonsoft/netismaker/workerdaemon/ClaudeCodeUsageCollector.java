package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.Kind;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.ParsedLine;
import com.hamonsoft.netismaker.workerdaemon.ClaudeCodeUsageLineParser.UsageLine;
import com.hamonsoft.netismaker.workerdaemon.UsageCollectorState.FileMark;
import com.hamonsoft.netismaker.workerdaemon.UsagePathClassifier.Category;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude Code 사용량 수집 (스펙 2026-11-03 §4, worker 프로필). usage-collect.interval-seconds(기본 5분)마다
 * {CLAUDE_CONFIG_DIR 또는 ~/.claude}/projects 아래 jsonl(subagents·workflows 포함)을 바이트 위치로 이어 읽어
 * 응답 키마다 토큰 칸별 최댓값을 POST /worker/usage/claude-code-events로 1000건씩 보낸다.
 *
 *  - 같은 PC의 워커 여러 개 중 하나만 돈다: ~/netis-maker/claude-usage-collector.lock 비차단 FileLock(RepoCacheCleanupJob과 같은 방식).
 *  - 이어 읽기 표시는 ~/netis-maker/claude-usage-collector.state.json(UsageCollectorState). 모든 묶음이 성공해야 저장한다
 *    (설계와 다름 ① — 회차 단위; 다시 보낸 것은 API upsert가 흡수하고 회차 양은 상한으로 묶인다).
 *  - 회차당 읽기 상한 max-mb-per-run과 첫 회차 60초 지연(설계와 다름 ② — 첫 회차에 GB 단위를 읽지 않게).
 *  - 내용·도구 입력·cwd·경로는 보내지 않는다(ClaudeCodeUsageEvent에 그런 칸이 없다). cwd는 SYMPHONY/DIRECT 구분에만 쓴다.
 *  - 걷는 사이 지워진 파일(Claude Code의 30일 정리)은 빼고 표시도 지운다. 그 밖의 읽기 오류는 그 파일만 건너뛰고 표시를 둔다.
 *  - RestClient에 타임아웃을 따로 두지 않는다(WorkerHttpClient 공유) — API가 멈춰도 묶이는 것은 수집 스레드 하나다.
 */
@Component
@Profile("worker")
@Slf4j
public class ClaudeCodeUsageCollector implements SchedulingConfigurer {

    static final String LOCK_FILE = "claude-usage-collector.lock";
    static final String STATE_FILE = "claude-usage-collector.state.json";
    static final int BATCH_SIZE = 1000;
    static final Duration INITIAL_DELAY = Duration.ofSeconds(60);
    /** API의 machine 칸 상한(ClaudeCodeUsageBatchRequest @Size). */
    static final int MACHINE_MAX = 100;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 한 회차의 결과. 줄 수는 이번 회차에 새로 읽은 완성 줄 기준이고, symphony·direct는 보낼 묶음에 들어간 줄,
     * directDropped는 include-direct=false라 버린 DIRECT 줄이다. inserted·updated·rejected는 API 응답 합계(rejected = skipped).
     *
     * @param files 걸어서 찾은 jsonl 중 회차 끝까지 있던 파일 수
     */
    record Result(boolean skipped, int files, long newLines, int symphony, int direct, int directDropped,
                  int beforeCountFrom, int unreadable, boolean budgetHit, boolean sent,
                  int inserted, int updated, int rejected) {
        static final Result SKIPPED = new Result(true, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0);
    }

    private final UsageCollectProperties cfg;
    private final WorkerHttpClient http;
    private final Path home;
    private final Map<String, String> env;
    private final List<String> symphonyRoots;

    @Autowired
    public ClaudeCodeUsageCollector(WorkerProperties props, UsageCollectProperties cfg, WorkerHttpClient http) {
        this(props, cfg, http, Path.of(System.getProperty("user.home")), System.getenv());
    }

    /** 시험용 — 가짜 홈과 env를 주입해 실제 ~/.claude를 읽지 않는다. */
    ClaudeCodeUsageCollector(WorkerProperties props, UsageCollectProperties cfg, WorkerHttpClient http,
                             Path home, Map<String, String> env) {
        this.cfg = cfg;
        this.http = http;
        this.home = home;
        this.env = env;
        UsagePathClassifier.Roots roots = UsagePathClassifier.roots(
                props.worktreeRoot(), props.reposDir(), home.toString(), cfg.symphonyRoots());
        roots.ignored().forEach(v -> log.warn("Symphony 폴더 설정 무시(홈·파일시스템 루트): {}", v));
        this.symphonyRoots = roots.accepted();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!Boolean.TRUE.equals(cfg.enabled())) return;
        registrar.addFixedDelayTask(
                new FixedDelayTask(this::collect, Duration.ofSeconds(cfg.intervalSeconds()), INITIAL_DELAY));
    }

    /** 스케줄 진입점 — 예외는 모두 잡아 경고 한 줄로 끝낸다(다음 회차 재시도). */
    public void collect() {
        if (!Boolean.TRUE.equals(cfg.enabled())) return;
        try {
            Result r = runOnce();
            if (r.skipped()) {
                log.debug("Claude 사용량 수집 건너뜀 — 같은 PC의 다른 워커가 수집 중");
            } else if (r.sent()) {
                log.info("{}", summary(r, cfg.maxMbPerRun()));
            }
        } catch (Exception e) {
            log.warn("Claude 사용량 수집 실패 (다음 회차 재시도): {}", e.toString());
        }
    }

    static String summary(Result r, int maxMbPerRun) {
        String line = "Claude 사용량 수집: 파일 " + r.files() + " · 새 줄 " + r.newLines()
                + " · Symphony " + r.symphony() + " · 직접 " + r.direct() + " · 직접 제외 " + r.directDropped()
                + " · 시작일 이전 " + r.beforeCountFrom() + " · 못 읽은 줄 " + r.unreadable()
                + " · 새로 " + r.inserted() + " · 갱신 " + r.updated() + " · 거절 " + r.rejected();
        return r.budgetHit() ? line + " · 읽기 상한 " + maxMbPerRun + "MB 도달 — 다음 회차에 이어 읽음" : line;
    }

    /** 보내기 실패 사유 — 응답 본문은 싣지 않는다. */
    static String failureReason(Exception e) {
        if (e instanceof HttpClientErrorException.NotFound) return "404(구버전 API)";
        if (e instanceof RestClientResponseException r) return r.getStatusCode().value() + " " + r.getStatusText();
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    /** 1회 실행. 락 안에서 읽기 → 보내기 → (모두 성공하면) 상태 저장. */
    Result runOnce() throws IOException {
        Path base = Files.createDirectories(home.resolve("netis-maker"));
        try (FileChannel ch = FileChannel.open(base.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = tryLockOrNull(ch);
            if (lock == null) return Result.SKIPPED;
            try (lock) {
                return collectUnderLock(base.resolve(STATE_FILE));
            }
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

    private Result collectUnderLock(Path stateFile) throws IOException {
        UsageCollectorState state = UsageCollectorState.load(stateFile);
        Path projects = ClaudeAuthProbe.claudeConfigDir(env, home).resolve("projects");
        List<Path> files = Files.isDirectory(projects) ? listJsonl(projects) : List.of();

        Round round = new Round(cfg.countFromDate().atStartOfDay(SEOUL).toInstant());
        Map<String, FileMark> marks = new LinkedHashMap<>();
        Set<String> present = new HashSet<>();
        long remaining = cfg.maxBytesPerRun();
        boolean budgetHit = false;
        for (Path file : files) {
            String key = file.toString();
            present.add(key);
            if (budgetHit) continue;   // 상한이 찼다 — 남은 파일은 이번 회차에 손대지 않는다
            long size;
            long mtime;
            JsonlTail.Chunk chunk;
            long start;
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                size = attrs.size();
                mtime = attrs.lastModifiedTime().toMillis();
                FileMark prev = state.mark(key);
                if (UsageCollectorState.unchanged(prev, size, mtime)) continue;
                start = UsageCollectorState.startOffset(prev, size, mtime);
                chunk = JsonlTail.read(file, start, remaining);
            } catch (NoSuchFileException e) {
                present.remove(key);   // 걷는 사이 지워졌다(30일 정리) — 이전 표시까지 지운다
                continue;
            } catch (IOException e) {
                warnUnreadable(file, e);   // 이번 회차만 건너뛰고 이전 표시는 둔다
                continue;
            }
            marks.put(key, new FileMark(chunk.nextOffset(), size, mtime));
            chunk.lines().forEach(round::accept);   // 곧바로 해석·병합하고 바이트는 버린다
            remaining -= chunk.nextOffset() - start;
            if (remaining <= 0) budgetHit = true;
        }

        List<ClaudeCodeUsageEvent> events = new ArrayList<>(round.byKey.size());
        round.byKey.values().forEach(p -> events.add(p.line().toEvent(p.category())));
        String machine = machineName();
        int inserted = 0;
        int updated = 0;
        int rejected = 0;
        boolean sent = true;
        try {
            int from = 0;
            do {   // 0건이어도 빈 묶음을 한 번 보낸다 — "수집이 멈춤"과 "쓴 것이 없음"을 가르려고
                int to = Math.min(from + BATCH_SIZE, events.size());
                ClaudeCodeUsageBatchResponse resp = http.postClaudeCodeUsage(new ClaudeCodeUsageBatchRequest(
                        machine, round.unreadable, List.copyOf(events.subList(from, to))));
                inserted += resp.inserted();
                updated += resp.updated();
                rejected += resp.skipped();
                from = to;
            } while (from < events.size());
        } catch (RuntimeException e) {
            log.warn("Claude 사용량 보내기 실패 — 위치를 그대로 두고 다음 회차에 다시 보냄: {}", failureReason(e));
            sent = false;
        }
        if (sent) {
            marks.forEach(state::put);
            state.retainOnly(present);
            state.saveAtomically(stateFile);
        }
        return new Result(false, present.size(), round.newLines, round.symphony, round.direct, round.directDropped,
                round.beforeCountFrom, round.unreadable, budgetHit, sent, inserted, updated, rejected);
    }

    /** projects 아래 jsonl 전부(경로 순). 못 여는 폴더·걷는 사이 지워진 항목은 건너뛰고 계속한다. 시험 seam. */
    List<Path> listJsonl(Path projectsDir) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(projectsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".jsonl")) found.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                if (!(e instanceof NoSuchFileException)) warnUnreadable(file, e);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null && !(e instanceof NoSuchFileException)) warnUnreadable(dir, e);
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(Comparator.naturalOrder());
        return found;
    }

    private static void warnUnreadable(Path p, IOException e) {
        log.warn("Claude 사용량 기록을 읽지 못해 이번 회차에서 건너뜀: {} ({})", p.getFileName(), e.getClass().getSimpleName());
    }

    /** 설정값, 없으면 호스트 이름, 그것도 못 얻으면 "unknown". 100자로 자른다. */
    String machineName() {
        String name = cfg.machine();
        if (name == null) name = localHostName();
        name = name == null || name.isBlank() ? "unknown" : name.trim();
        return name.length() > MACHINE_MAX ? name.substring(0, MACHINE_MAX) : name;
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record Pending(UsageLine line, Category category) {}

    /** 한 회차의 줄 해석 — 남는 것은 키별로 칸별 최댓값을 합친 줄과 세기뿐이다. */
    private final class Round {
        final Map<String, Pending> byKey = new LinkedHashMap<>();
        final Instant countFrom;
        long newLines;
        int symphony;
        int direct;
        int directDropped;
        int beforeCountFrom;
        int unreadable;

        Round(Instant countFrom) {
            this.countFrom = countFrom;
        }

        void accept(byte[] raw) {
            newLines++;
            ParsedLine parsed = ClaudeCodeUsageLineParser.parse(raw);
            if (parsed.kind() == Kind.UNREADABLE) {
                unreadable++;
                return;
            }
            if (parsed.kind() != Kind.EVENT) return;
            UsageLine line = parsed.line();
            if (line.occurredAt().toInstant().isBefore(countFrom)) {
                beforeCountFrom++;
                return;
            }
            Category category = UsagePathClassifier.classify(line.cwd(), symphonyRoots);
            if (category == Category.DIRECT && !Boolean.TRUE.equals(cfg.includeDirect())) {
                directDropped++;
                return;
            }
            if (category == Category.SYMPHONY) symphony++;
            else direct++;
            byKey.merge(line.eventKey(), new Pending(line, category),
                    (a, b) -> new Pending(a.line().maxWith(b.line()), a.category()));
        }
    }
}
