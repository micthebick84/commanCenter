package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 작업별 MCP 스펙 (snapshot). task.mcps_extra (jsonb) 배열의 한 요소.
 *
 * 카탈로그에서 선택된 시점의 name/url/transport를 박제 — 카탈로그가 나중에
 * 변경/삭제돼도 분석 시 사용된 정확한 설정이 audit/재현 가능.
 * headers: 워커가 실행 직전에만 만드는 http 서버용(DB MCP 작업 토큰, 스펙 2026-10-14 §6.4) — DB에는 저장하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskMcpSpec(
        String name,
        String url,
        String transport,
        Map<String, String> headers
) {
    public TaskMcpSpec(String name, String url, String transport) {
        this(name, url, transport, null);
    }

    /** ⚠️ headers에 토큰 — 가린다. */
    @Override
    public String toString() {
        return "TaskMcpSpec[name=" + name + ", url=" + url + ", transport=" + transport
                + (headers == null ? "" : ", headers=****") + "]";
    }
}
