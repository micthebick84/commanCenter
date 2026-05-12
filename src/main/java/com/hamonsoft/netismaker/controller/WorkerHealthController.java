package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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

    private Map<String, Object> toView(WorkerHeartbeat h, OffsetDateTime threshold) {
        Map<String, Object> view = new java.util.HashMap<>();
        view.put("workerId", h.getWorkerId());
        view.put("hostname", h.getHostname());
        view.put("version", h.getVersion());
        view.put("lastSeenAt", h.getLastSeenAt());
        view.put("alive", h.getLastSeenAt() != null && h.getLastSeenAt().isAfter(threshold));
        view.put("claudeSessionOk", h.getClaudeSessionOk());
        view.put("vpnStatus", h.getVpnStatus());
        return view;
    }
}
