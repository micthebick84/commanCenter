package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구독 사용량 조회 — 인증 사용자 전원 (스펙 2026-09-05 §2 "조회 권한": 구독 계정 1개를 전 사용자가 공유).
 *   GET /api/usage/claude → {limits:[…]}
 */
@RestController
@RequestMapping("/api/usage")
@Profile("api")
public class UsageController {

    private final ClaudeUsageService usage;

    public UsageController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/claude")
    public ClaudeUsageResponse claude() {
        return usage.snapshot();
    }
}
