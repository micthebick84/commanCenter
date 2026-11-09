package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /worker/usage/claude-code-events 본문. 빈 묶음도 받는다(수집기 상태만 갱신).
 * events 안의 이상한 이벤트 하나가 묶음 전체를 400으로 만들지 않도록 요소 검증(@Valid)은 달지 않는다 — 서버가 건너뛰고 skipped로 센다.
 */
public record ClaudeCodeUsageBatchRequest(
        @NotBlank @Size(max = 100) String machine,
        @PositiveOrZero Integer unreadableLines,
        @Size(max = 1000) List<ClaudeCodeUsageEvent> events
) {}
