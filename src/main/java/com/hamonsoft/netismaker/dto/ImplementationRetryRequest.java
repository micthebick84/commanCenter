package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 구현 재시도 바디 (스펙 2026-10-15 §5.5). implementationMode가 null·공백이면 지금 방식 유지.
 * removeRefCatalogIds: 이번 재시도에서 뺄 참조 repo의 카탈로그 id (스펙 2026-10-24 §4.10) — null·빈 목록이면 지금 참조 그대로.
 */
public record ImplementationRetryRequest(String implementationMode, List<Long> removeRefCatalogIds) {

    /** 참조 빼기 도입 전 1개 인자 생성자 — 기존 호출부·테스트 호환. */
    public ImplementationRetryRequest(String implementationMode) {
        this(implementationMode, null);
    }
}
