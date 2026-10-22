package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.CheckoutDir;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.Policy;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.SessionRow;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupPlanner.Verdict;
import com.hamonsoft.netismaker.util.FileTrees;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 인터뷰·질문 체크아웃 폴더 정리 (api 프로파일, 스펙 2026-10-22 §6). 매일 cron(기본 03:50)에 체크아웃 루트
 * (InterviewWorkPaths.root())를 스캔 → 세션 상태를 DB에서 일괄 조회 → InterviewCheckoutCleanupPlanner로 판정 → 삭제.
 * 끝난 세션(마지막 활동 후 retention-days 경과)의 대상 폴더 {루트}/{a}/{b}/session-{id}와 참조 폴더 {루트}/_refs/{id}를 지운다.
 * DB 행·첨부·플랜은 그대로 둔다 — 체크아웃은 다시 받을 수 있는 사본이다.
 *
 * 지우는 잡이므로 안전이 먼저다.
 *  - 판정을 전부 끝낸 뒤에 지운다: DB 조회가 실패하면 아무것도 지우지 않고 이번 회차를 건너뛴다(fail-closed).
 *  - 루트가 절대 경로가 아니거나 없는 디렉터리(·파일시스템 루트·홈)면 아무것도 하지 않는다.
 *  - 링크·정션을 따라가지 않는다. 스캔은 NOFOLLOW_LINKS 속성으로 일반 디렉터리만 내려가고(링크·정션·파일은 건너뜀),
 *    지우기 전에 그 폴더의 실제 경로가 루트의 실제 경로 안인지 다시 확인하며, 트리 안의 링크는 FileTrees가 링크만 지운다.
 *  - 이름이 정규형(session-{id}, _refs/{id})이고 자리(루트 아래 두 단계, 참조는 _refs 바로 아래)가 맞는 폴더만 후보다.
 *  - 폴더 하나가 실패해도 로그만 남기고 다음으로 넘어간다 — 남은 것은 다음 회차에 지운다.
 *
 * 전제: API와 인터뷰 서비스가 같은 PC, 같은 사용자로 돈다(work_dir·첨부 절대경로가 이미 이 전제를 쓴다).
 */
@Component
@Profile("api")
@Slf4j
public class InterviewCheckoutCleanupJob {

    /** IN 절 1회당 id 수 상한 — 폴더가 수천 개여도 바인드 파라미터 한도·쿼리 크기를 넘지 않게. */
    static final int LOOKUP_CHUNK = 500;

    private final InterviewWorkPaths paths;
    private final InterviewSessionRepository sessionRepo;
    private final boolean enabled;
    private final Policy policy;

    public InterviewCheckoutCleanupJob(InterviewWorkPaths paths,
                                       InterviewSessionRepository sessionRepo,
                                       @Value("${app.interview.checkout-cleanup.enabled:true}") boolean enabled,
                                       @Value("${app.interview.checkout-cleanup.retention-days:7}") int retentionDays) {
        // 0 이하면 방금 끝난 세션의 폴더까지 지운다 — 기동 때 거부한다
        if (retentionDays < 1) {
            throw new IllegalArgumentException("app.interview.checkout-cleanup.retention-days는 1 이상이어야 합니다");
        }
        this.paths = paths;
        this.sessionRepo = sessionRepo;
        this.enabled = enabled;
        this.policy = new Policy(Duration.ofDays(retentionDays));
    }

    /** 한 회차 결과 — 요약 로그와 테스트 검증용. deleted·kept는 폴더 수(대상과 참조를 따로 센다)다. */
    public record Summary(int deleted, long freedBytes, int kept, int failed) {}

    @Scheduled(cron = "${app.interview.checkout-cleanup.cron:0 50 3 * * *}")
    public void cleanup() {
        if (!enabled) return;
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            // 스캔/조회 단계 실패 — 삭제는 판정이 끝난 뒤에만 하므로 이 경로에선 지운 것이 없다.
            log.error("체크아웃 정리 실패 — 이번 회차 건너뜀(다음 회차에 재시도)", e);
        }
    }

    Summary runOnce(Instant now) throws IOException {
        Path configured = paths.root();
        String problem = rootProblem(configured);
        if (problem != null) {
            log.warn("체크아웃 정리: 체크아웃 루트를 쓸 수 없어 이번 회차를 건너뜀 — {}: {}", problem, configured);
            return new Summary(0, 0, 0, 0);
        }
        // 스캔도 삭제 확인도 루트의 실제 경로 기준 — 루트 자체가 링크여도(디스크를 옮긴 경우) 안쪽은 링크 없는 경로로 다룬다
        Path root = configured.toRealPath();

        Scan scan = new Scan();
        scanRoot(root, scan);
        if (scan.dirs.isEmpty()) {
            log.debug("체크아웃 정리: 대상 폴더 없음 (건너뜀 {}개)", scan.skipped);
            return new Summary(0, 0, 0, 0);
        }

        // 판정에 필요한 DB 조회를 전부 끝낸 뒤에 지운다 — 어느 조회든 실패하면 예외가 올라가고 지운 것은 없다.
        Map<Long, SessionRow> rows = lookupRows(scan.dirs);
        Long maxSessionId = sessionRepo.findMaxId();

        int deletedClosed = 0, deletedOrphan = 0, kept = 0, failed = 0;
        long freed = 0;
        // 유지는 폴더별로 찍지 않고 사유별 개수만 요약에 싣는다 — 패키지 로그 레벨이 DEBUG라 세션 수만큼 줄이 쌓인다.
        Map<Verdict, Integer> keptBy = new EnumMap<>(Verdict.class);
        for (CheckoutDir d : scan.dirs) {
            Verdict v = InterviewCheckoutCleanupPlanner.decide(d, rows, maxSessionId, now, policy);
            if (!v.delete()) {
                kept++;
                keptBy.merge(v, 1, Integer::sum);
                continue;
            }
            String shown = root.relativize(d.path()).toString();
            try {
                if (!isInsideRoot(root, d.path())) {
                    failed++;
                    log.warn("체크아웃 정리: 체크아웃 루트 안의 일반 폴더가 아니어서 삭제 거부 — {}", d.path());
                    continue;
                }
                FileTrees.DeleteResult r = FileTrees.deleteTree(d.path());
                freed += r.freedBytes();
                if (r.failures() > 0) {
                    failed++;
                    log.warn("체크아웃 정리: 삭제 미완료 {} — {}개 항목 실패, 다음 회차에 재시도", shown, r.failures());
                    continue;
                }
                if (v == Verdict.DELETE_CLOSED) deletedClosed++;
                else deletedOrphan++;
                log.info("체크아웃 정리: 삭제 {} — {} ({})", shown, v.label(), AttachmentCleanupJob.humanBytes(r.freedBytes()));
            } catch (RuntimeException e) {
                failed++;
                log.warn("체크아웃 정리: 삭제 실패 {} — 다음 회차에 재시도", shown, e);
            }
        }
        Summary s = new Summary(deletedClosed + deletedOrphan, freed, kept, failed);
        log.info("체크아웃 정리 완료: 삭제 {}개(끝난 세션 {}, 고아 {}), 해제 {}, 유지 {}개{}, 건너뜀 {}개, 실패 {}개",
                s.deleted(), deletedClosed, deletedOrphan, AttachmentCleanupJob.humanBytes(freed), kept,
                breakdown(keptBy), scan.skipped, failed);
        return s;
    }

    // ── 루트 방어 ───────────────────────────────────────────

    /**
     * 체크아웃 루트로 쓸 수 없는 이유(쓸 수 있으면 null). 절대 경로이고 실제로 있는 디렉터리여야 한다.
     * 파일시스템 루트·홈 디렉터리는 잘못된 설정이 거의 확실하므로 스캔 범위가 되지 않게 막는다.
     */
    static String rootProblem(Path root) {
        if (root == null || !root.isAbsolute()) return "절대 경로가 아님";
        Path n = root.normalize();
        if (n.getParent() == null) return "파일시스템 루트";
        if (isUserHome(n)) return "사용자 홈 디렉터리";
        if (!Files.isDirectory(n)) return "존재하는 디렉터리가 아님";
        return null;
    }

    private static boolean isUserHome(Path normalizedRoot) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return false;
        try {
            return normalizedRoot.equals(Path.of(home).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * 삭제 직전 방어 — 스캔한 폴더가 아직 루트(실제 경로) 안의 일반 디렉터리이고 경로 어디에도 링크가 끼어 있지 않은지 본다.
     * dir은 realRoot에서 걸어 내려온 경로다. 속성은 NOFOLLOW_LINKS로 읽어 폴더 자체가 링크·정션이면 거부하고,
     * toRealPath() 결과가 realRoot 아래이면서 스캔한 경로와 같아야(= 중간 단계가 링크로 바뀌지 않았어야) 통과한다.
     * 확인할 수 없으면(없어짐·권한 등) 거부한다 — 호출한 쪽은 실패로 세고 다음 회차에 다시 본다.
     */
    static boolean isInsideRoot(Path realRoot, Path dir) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!isPlainDirectory(attrs)) return false;
            Path real = dir.toRealPath();
            return real.startsWith(realRoot) && !real.equals(realRoot) && real.equals(dir);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ── 스캔: {루트}/{a}/{b}/session-* (a가 _refs면 제외) 와 {루트}/_refs/* ──

    /** 스캔 결과 누적 — dirs는 판정 후보, skipped는 이름·자리가 맞지 않거나 일반 디렉터리가 아니어서(링크·정션·파일) 건너뛴 항목 수. */
    private static final class Scan {
        final List<CheckoutDir> dirs = new ArrayList<>();
        int skipped;
    }

    private void scanRoot(Path root, Scan scan) throws IOException {
        for (Path top : children(root)) {   // 루트 자체를 못 읽으면 예외 → 회차 중단(아직 지운 것 없음)
            if (!isPlainDirectoryAt(top, scan)) continue;
            try {
                if (InterviewWorkPaths.REFS_DIR.equals(top.getFileName().toString())) {
                    collect(top, true, scan);
                } else {
                    scanOwner(top, scan);
                }
            } catch (IOException e) {
                skipUnreadable(top, e, scan);
            }
        }
    }

    /** {루트}/{a} 아래의 {b}들. */
    private void scanOwner(Path owner, Scan scan) throws IOException {
        for (Path repo : children(owner)) {
            if (!isPlainDirectoryAt(repo, scan)) continue;
            try {
                collect(repo, false, scan);
            } catch (IOException e) {
                skipUnreadable(repo, e, scan);
            }
        }
    }

    /** parent의 바로 아래에서 후보(대상이면 session-{id}, 참조면 {id})를 모은다. 링크·정션이면 이름이 맞아도 건너뛴다. */
    private void collect(Path parent, boolean refs, Scan scan) throws IOException {
        for (Path child : children(parent)) {
            BasicFileAttributes attrs = attributesOf(child);
            if (attrs == null) {
                scan.skipped++;
                continue;
            }
            Instant mtime = attrs.lastModifiedTime().toInstant();
            Optional<CheckoutDir> d = refs
                    ? InterviewCheckoutCleanupPlanner.parseRefs(child, mtime)
                    : InterviewCheckoutCleanupPlanner.parseTarget(child, mtime);
            if (d.isEmpty()) {
                scan.skipped++;
                log.debug("체크아웃 정리: 대상 패턴 아님 — 건너뜀: {}", child);
            } else if (!isPlainDirectory(attrs)) {
                scan.skipped++;
                log.warn("체크아웃 정리: 디렉터리가 아님(심볼릭 링크·정션 등) — 건너뜀: {}", child);
            } else {
                scan.dirs.add(d.get());
            }
        }
    }

    private static void skipUnreadable(Path dir, Exception e, Scan scan) {
        // 스캔 도중 사라졌거나 권한 문제 — 이 폴더 아래만 건너뛴다(다음 회차에 재판정).
        scan.skipped++;
        log.warn("체크아웃 정리: 폴더를 읽지 못해 건너뜀: {} ({})", dir, e.toString());
    }

    /** 스캔 중간 단계(루트 바로 아래·그 아래)가 일반 디렉터리인지. 아니면(파일·링크·정션) 건너뜀으로 센다. */
    private static boolean isPlainDirectoryAt(Path p, Scan scan) {
        BasicFileAttributes attrs = attributesOf(p);
        if (attrs != null && isPlainDirectory(attrs)) return true;
        scan.skipped++;
        log.debug("체크아웃 정리: 일반 디렉터리가 아님 — 건너뜀: {}", p);
        return false;
    }

    /** 링크를 따라가지 않고 읽는다. 읽지 못하면 null. */
    private static BasicFileAttributes attributesOf(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            log.warn("체크아웃 정리: 속성 조회 실패 — 건너뜀: {} ({})", p, e.toString());
            return null;
        }
    }

    private static boolean isPlainDirectory(BasicFileAttributes attrs) {
        return attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther();
    }

    private static List<Path> children(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) out.add(p);
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return out;
    }

    // ── 조회 ───────────────────────────────────────────────

    /** 스캔된 세션 id만 IN 절로 일괄 조회 (LOOKUP_CHUNK씩). 대상과 참조가 같은 세션이면 한 번만 묻는다. */
    private Map<Long, SessionRow> lookupRows(List<CheckoutDir> dirs) {
        List<Long> ids = dirs.stream().map(CheckoutDir::sessionId).distinct().toList();
        Map<Long, SessionRow> rows = new HashMap<>();
        for (int i = 0; i < ids.size(); i += LOOKUP_CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(i + LOOKUP_CHUNK, ids.size()));
            for (InterviewSessionRepository.AttachmentOwnerState r : sessionRepo.findAttachmentOwnerStates(chunk)) {
                rows.put(r.getId(), new SessionRow(r.getStatus(), r.getLastActivityAt()));
            }
        }
        return rows;
    }

    /** 유지 사유별 개수 — "(진행 중 세션 3, 끝난 세션 — 보존 기간 이내 2)". 없으면 빈 문자열. */
    private static String breakdown(Map<Verdict, Integer> keptBy) {
        if (keptBy.isEmpty()) return "";
        return keptBy.entrySet().stream()
                .map(e -> e.getKey().label() + " " + e.getValue())
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
