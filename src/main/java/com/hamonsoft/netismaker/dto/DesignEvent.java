package com.hamonsoft.netismaker.dto;

/**
 * SSE event:design 페이로드 — {key, title, body, approved} JSON 객체.
 * 프론트가 key로 설계 섹션을 upsert한다. 현재 백엔드는 설계를 단일 마크다운 덩어리로 보관하므로
 * 턴 seq 기반 key("design-{seq}")로 1섹션을 구성한다(섹션 분할/승인 모델은 후속).
 */
public record DesignEvent(String key, String title, String body, boolean approved) {}
