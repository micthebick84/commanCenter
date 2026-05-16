package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.repository.McpCatalogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 5분마다 enabled=true 카탈로그 항목 자동 헬스 체크.
 *
 * startup 후 30초 대기 후 첫 실행 (앱 부트 안정화 후).
 * 카탈로그 사이즈 작을 거란 가정 — 직렬 probe (각 5s timeout이라 N건이면 최대 5N초).
 * 100건 넘어가면 병렬화 필요.
 */
@Component
@Profile("api")
@Slf4j
public class McpHealthScheduler {

    private final McpCatalogRepository repo;
    private final McpHealthService healthService;

    public McpHealthScheduler(McpCatalogRepository repo, McpHealthService healthService) {
        this.repo = repo;
        this.healthService = healthService;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)  // 30s 후 첫 실행, 이후 5분 주기
    public void probeAllEnabled() {
        List<McpCatalogEntry> entries = repo.findByEnabledTrueOrderByDisplayName();
        if (entries.isEmpty()) return;
        log.info("MCP 헬스 자동 체크 시작: {}개", entries.size());
        int healthy = 0, degraded = 0, down = 0;
        for (McpCatalogEntry e : entries) {
            try {
                McpHealthService.ProbeResult r = probeOne(e.getId());
                switch (r.status()) {
                    case McpHealthService.STATUS_HEALTHY -> healthy++;
                    case McpHealthService.STATUS_DEGRADED -> degraded++;
                    case McpHealthService.STATUS_DOWN -> down++;
                }
            } catch (Exception ex) {
                log.warn("자동 체크 실패 id={} ({})", e.getId(), ex.getMessage());
                down++;
            }
        }
        log.info("MCP 헬스 자동 체크 완료: HEALTHY={}, DEGRADED={}, DOWN={}", healthy, degraded, down);
    }

    /** 각 entry는 독립 트랜잭션 — 하나 실패해도 나머지 계속. */
    @Transactional
    public McpHealthService.ProbeResult probeOne(Long entryId) {
        return healthService.checkAndPersist(entryId);
    }
}
