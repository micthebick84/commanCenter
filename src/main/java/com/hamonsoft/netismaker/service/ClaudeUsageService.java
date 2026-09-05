package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Claude Code 구독 사용량 — 스펙 2026-09-05 §4.1. limit_type당 1행 upsert.
 * 보고 빈도가 낮고(턴당 수 건) 보고자가 인터뷰 서비스 1개라 네이티브 ON CONFLICT 없이 findById→save로 충분.
 */
@Service
@Profile("api")
public class ClaudeUsageService {

    private final ClaudeRateLimitRepository repo;

    public ClaudeUsageService(ClaudeRateLimitRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ClaudeRateLimit record(WorkerRateLimitRequest req, String workerId) {
        ClaudeRateLimit row = repo.findById(req.limitType()).orElseGet(() -> {
            ClaudeRateLimit r = new ClaudeRateLimit();
            r.setLimitType(req.limitType());
            return r;
        });
        row.setStatus(req.status());
        row.setUtilization(req.utilization());
        row.setResetsAt(req.resetsAt());
        row.setUsingOverage(Boolean.TRUE.equals(req.isUsingOverage()));
        row.setReportedBy(workerId);
        row.setUpdatedAt(OffsetDateTime.now());
        return repo.save(row);
    }

    @Transactional(readOnly = true)
    public ClaudeUsageResponse snapshot() {
        return new ClaudeUsageResponse(
                repo.findAllByOrderByLimitTypeAsc().stream().map(ClaudeUsageResponse.LimitView::of).toList());
    }
}
