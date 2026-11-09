package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import com.hamonsoft.netismaker.usage.ClaudeCodeUsageIngestService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 구독 사용량 워커 API (X-Worker-API-Key, InterviewWorkerController 미러).
 *   POST /worker/usage/rate-limits?workerId=… ─► SDK rate_limit_info 스냅샷 upsert (스펙 2026-09-05 §5.2)
 *   POST /worker/usage/claude-code-events ─► Claude Code 사용량 이벤트 묶음 upsert (스펙 2026-11-03 §4)
 * /worker/** 는 SecurityConfig.workerFilterChain이 감싼다 — 별도 등록 불필요.
 */
@RestController
@RequestMapping("/worker/usage")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class UsageWorkerController {

    private final ClaudeUsageService usage;
    private final ClaudeCodeUsageIngestService claudeCodeIngest;

    public UsageWorkerController(ClaudeUsageService usage, ClaudeCodeUsageIngestService claudeCodeIngest) {
        this.usage = usage;
        this.claudeCodeIngest = claudeCodeIngest;
    }

    @PostMapping("/rate-limits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rateLimit(@RequestParam String workerId, @RequestBody @Valid WorkerRateLimitRequest req) {
        usage.record(req, workerId);
    }

    @PostMapping("/claude-code-events")
    public ClaudeCodeUsageBatchResponse claudeCodeEvents(@RequestBody @Valid ClaudeCodeUsageBatchRequest req) {
        return claudeCodeIngest.ingest(req);
    }
}
