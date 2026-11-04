package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskDbMcpTokenRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 디자인·구현 단계 DB MCP 작업 토큰 (스펙 2026-10-14 §6.2).
 * 유효 = 그 작업의 가장 최근 토큰 + 작업이 발급 당시 상태 그대로 + 만료 전 + 삭제되지 않은 작업.
 * 구현 단계 쓰기(스펙 2026-11-03 §6.1)를 위해 발급 때의 쓰기 허가(write_granted)를 함께 저장하고 인증 때 돌려준다.
 */
@Service
@Profile("api")
public class DbMcpTokenService {

    private static final Logger log = LoggerFactory.getLogger(DbMcpTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 구현 단계 토큰 수명 = 구현 상한 + 이 여유(기본 TTL보다 길 때만). 이 값은 구현 계획(2026-11-03)이 정했다. */
    public static final Duration IMPLEMENTATION_TTL_MARGIN = Duration.ofMinutes(30);

    private final TaskDbMcpTokenRepository repo;
    private final TaskRepository taskRepo;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public DbMcpTokenService(TaskDbMcpTokenRepository repo, TaskRepository taskRepo,
                             @Value("${netismaker.db-mcp.token-ttl:2h}") Duration ttl) {
        this(repo, taskRepo, ttl, Clock.systemDefaultZone());
    }

    DbMcpTokenService(TaskDbMcpTokenRepository repo, TaskRepository taskRepo, Duration ttl, Clock clock) {
        this.repo = repo;
        this.taskRepo = taskRepo;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 쓰기 허가 없는 토큰 — {@link #issue(Task, boolean)}의 false. */
    @Transactional
    public String issue(Task t) {
        return issue(t, false);
    }

    /**
     * 새 토큰 발급 — 평문은 반환값으로만 나간다(저장·로그 금지). 같은 작업의 이전 토큰은 이 시점에 무효.
     * writeGranted: 발급 때 쓰기가 허가됐는지(스펙 2026-11-03 §6.1 조건 6 — 쓰기를 아는 워커가 받은 구현 claim에서 쓰기 연결이 하나라도
     * 있을 때). 실행 시점에 조건을 다시 보므로 이 값은 상한일 뿐이다. 만료는 {@link #lifetime(Task)}.
     */
    @Transactional
    public String issue(Task t, boolean writeGranted) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        OffsetDateTime now = OffsetDateTime.now(clock);
        TaskDbMcpToken row = TaskDbMcpToken.issue(t.getId(), sha256Hex(token), t.getStatus().name(), now,
                now.plus(lifetime(t)));
        row.setWriteGranted(writeGranted);
        repo.save(row);
        return token;
    }

    /**
     * 토큰 수명 (스펙 2026-11-03 §6.4). 구현 단계이고 claim이 이번 시도의 구현 상한을 기록했으면 max(기본 TTL, 상한 + 여유) —
     * 기본 2h는 SDD 상한(240분)보다 짧아 세션 중간에 401이 난다. 그 밖(디자인·상한 없음)은 기본 TTL.
     */
    private Duration lifetime(Task t) {
        Integer timeout = t.getImplementationTimeoutMinutes();
        if (t.getStatus() != TaskStatus.IMPLEMENTING || timeout == null) return ttl;
        Duration implementation = Duration.ofMinutes(timeout).plus(IMPLEMENTATION_TTL_MARGIN);
        return implementation.compareTo(ttl) > 0 ? implementation : ttl;
    }

    /** 작업의 토큰 전부 무효화 — 새 토큰 없이 claim될 때(쓸 수 있는 연결 0개) 이전 토큰이 다시 유효해지지 않게. */
    @Transactional
    public void revokeAll(Long taskId) {
        repo.deleteByTaskId(taskId);
    }

    /** 인증된 작업과 그 토큰의 쓰기 허가(발급 때 값). */
    public record TokenAuth(Task task, boolean writeGranted) {}

    @Transactional(readOnly = true)
    public Optional<Task> authenticate(String token) {
        return authenticateWithGrant(token).map(TokenAuth::task);
    }

    @Transactional(readOnly = true)
    public Optional<TokenAuth> authenticateWithGrant(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<TaskDbMcpToken> row = repo.findByTokenHash(sha256Hex(token));
        if (row.isEmpty()) return Optional.empty();
        TaskDbMcpToken r = row.get();
        if (!r.getExpiresAt().isAfter(OffsetDateTime.now(clock))) return Optional.empty();
        Optional<TaskDbMcpToken> latest = repo.findFirstByTaskIdOrderByIdDesc(r.getTaskId());
        if (latest.isEmpty() || !latest.get().getId().equals(r.getId())) return Optional.empty();
        return taskRepo.findById(r.getTaskId())
                .filter(t -> t.getDeletedAt() == null && t.getStatus().name().equals(r.getStage()))
                .map(t -> new TokenAuth(t, r.isWriteGranted()));
    }

    @Scheduled(cron = "${netismaker.db-mcp.token-cleanup-cron:0 40 3 * * *}")
    @Transactional
    public void cleanup() {
        int n = repo.deleteExpiredOrSuperseded(OffsetDateTime.now(clock));
        if (n > 0) log.info("DB MCP 작업 토큰 정리 {}건", n);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
