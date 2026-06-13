package com.hamonsoft.netismaker.dto;

/**
 * SSE event:plan_ready 페이로드 객체 (LOCKED CONTRACT v2).
 * bare string이 아니라 {designMarkdown, planMarkdown, planJson} JSON 객체로 전달.
 * 프론트가 우측 패널(설계 + 플랜 + 잘게 쪼갠 태스크)을 한 번에 채운다.
 */
public record PlanReadyEvent(
        String designMarkdown,
        String planMarkdown,
        String planJson
) {}
