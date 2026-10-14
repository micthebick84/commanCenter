package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
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
 */
@Service
@Profile("api")
public class DbMcpTokenService {

    private static final Logger log = LoggerFactory.getLogger(DbMcpTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

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

    /** 새 토큰 발급 — 평문은 반환값으로만 나간다(저장·로그 금지). 같은 작업의 이전 토큰은 이 시점에 무효. */
    @Transactional
    public String issue(Task t) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        OffsetDateTime now = OffsetDateTime.now(clock);
        repo.save(TaskDbMcpToken.issue(t.getId(), sha256Hex(token), t.getStatus().name(), now, now.plus(ttl)));
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<Task> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<TaskDbMcpToken> row = repo.findByTokenHash(sha256Hex(token));
        if (row.isEmpty()) return Optional.empty();
        TaskDbMcpToken r = row.get();
        if (!r.getExpiresAt().isAfter(OffsetDateTime.now(clock))) return Optional.empty();
        Optional<TaskDbMcpToken> latest = repo.findFirstByTaskIdOrderByIdDesc(r.getTaskId());
        if (latest.isEmpty() || !latest.get().getId().equals(r.getId())) return Optional.empty();
        return taskRepo.findById(r.getTaskId())
                .filter(t -> t.getDeletedAt() == null && t.getStatus().name().equals(r.getStage()));
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
