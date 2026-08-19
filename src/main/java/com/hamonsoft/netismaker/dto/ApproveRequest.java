package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 관리자 승인 요청 바디. 승인 = 인터뷰 시작이므로 인터뷰가 쓸 모델/effort/MCP를 여기서 정한다.
 * 전부 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음.
 * 레거시 경로(분석완료 → 구현대기) 승인에서는 무시된다.
 */
public record ApproveRequest(
        List<Long> mcpCatalogIds,
        String model,
        String effort
) {}
