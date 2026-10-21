package com.hamonsoft.netismaker.entity;

/**
 * 구현 방식 (스펙 2026-10-15 §1). DB·API·워커가 enum 이름을 그대로 주고받는다.
 *  DIRECT — 한 세션이 계획대로 직접 구현(기존 동작)
 *  REVIEW — 직접 구현 + 마지막에 리뷰 서브에이전트 1회, 계획 범위 안 중대 문제만 수정
 *  SDD    — superpowers subagent-driven-development로 계획 작업마다 서브에이전트가 구현·리뷰
 */
public enum ImplementationMode {
    DIRECT("직접"),
    REVIEW("리뷰"),
    SDD("SDD");

    private final String label;

    ImplementationMode(String label) {
        this.label = label;
    }

    /** 화면·이력·PR 본문에 쓰는 이름. */
    public String label() {
        return label;
    }

    /** 워커가 claim 응답 값을 읽을 때 — null·공백·모르는 값(다른 버전 API)은 지금 동작인 DIRECT로 본다. */
    public static ImplementationMode fromWireOrDirect(String raw) {
        if (raw == null || raw.isBlank()) return DIRECT;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}
