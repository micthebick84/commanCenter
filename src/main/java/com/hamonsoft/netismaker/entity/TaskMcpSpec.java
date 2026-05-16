package com.hamonsoft.netismaker.entity;

/**
 * 작업별 MCP 스펙 (snapshot). task.mcps_extra (jsonb) 배열의 한 요소.
 *
 * 카탈로그에서 선택된 시점의 name/url/transport를 박제 — 카탈로그가 나중에
 * 변경/삭제돼도 분석 시 사용된 정확한 설정이 audit/재현 가능.
 */
public record TaskMcpSpec(
        String name,
        String url,
        String transport
) {}
