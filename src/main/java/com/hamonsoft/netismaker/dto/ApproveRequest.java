package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 관리자 승인 요청 바디. 승인 = 인터뷰 시작이므로 인터뷰가 쓸 모델/effort/MCP/DB 연결을 여기서 정한다.
 * 전부 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음 + DB 없음.
 * 레거시 경로(분석완료 → 구현대기) 승인에서는 무시된다.
 * dbConnectionIds: 스펙 2026-10-14 §4 — 검증 기준은 작업 등록자(최대 3개, 같은 레포, 활성, 공용이거나 등록자 본인 것).
 */
public record ApproveRequest(
        List<Long> mcpCatalogIds,
        String model,
        String effort,
        List<Long> dbConnectionIds
) {
    /** DB 선택 도입 전 3인자 — 기존 호출부·테스트 호환. */
    public ApproveRequest(List<Long> mcpCatalogIds, String model, String effort) {
        this(mcpCatalogIds, model, effort, null);
    }
}
