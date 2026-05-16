package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *  워커 헬스 (ADMIN만). DESIGN §12.
 *    GET /api/workers/health
 *
 *  alive = last_seen_at > (now - 60s)
 */
@RestController
@RequestMapping("/api/workers")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Profile("api")
public class WorkerHealthController {

    private static final long ALIVE_THRESHOLD_SECONDS = 60;

    private final WorkerHeartbeatRepository repo;

    public WorkerHealthController(WorkerHeartbeatRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/health")
    public List<Map<String, Object>> health() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(ALIVE_THRESHOLD_SECONDS);
        return repo.findAll().stream()
                .map(h -> toView(h, threshold))
                .toList();
    }

    /**
     * 작업 등록 다이얼로그용 — 살아있는 워커들의 MCP 합집합.
     * ADMIN 권한 강제하지 않음 (일반 사용자가 작업 작성 시 사용 가능한 도구를 미리 보기 위함).
     * 반환: { mcps: ["local-db", "obsidian-vault", ...], aliveWorkerCount: 2 }
     */
    @GetMapping("/mcps/available")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> availableMcps() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(ALIVE_THRESHOLD_SECONDS);
        Set<String> union = new LinkedHashSet<>();
        int alive = 0;
        for (WorkerHeartbeat h : repo.findAll()) {
            if (h.getLastSeenAt() == null || !h.getLastSeenAt().isAfter(threshold)) continue;
            alive++;
            if (h.getMcps() != null) union.addAll(h.getMcps());
        }
        return Map.of("mcps", List.copyOf(union), "aliveWorkerCount", alive);
    }

    private Map<String, Object> toView(WorkerHeartbeat h, OffsetDateTime threshold) {
        Map<String, Object> view = new java.util.HashMap<>();
        view.put("workerId", h.getWorkerId());
        view.put("hostname", h.getHostname());
        view.put("version", h.getVersion());
        view.put("lastSeenAt", h.getLastSeenAt());
        view.put("alive", h.getLastSeenAt() != null && h.getLastSeenAt().isAfter(threshold));
        view.put("claudeSessionOk", h.getClaudeSessionOk());
        view.put("vpnStatus", h.getVpnStatus());
        view.put("mcps", h.getMcps() == null ? java.util.List.of() : h.getMcps());
        return view;
    }
}
