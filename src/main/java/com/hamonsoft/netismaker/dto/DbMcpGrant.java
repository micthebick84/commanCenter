package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 구현·디자인 claim의 DB MCP 지시 (스펙 2026-10-14 §6.4). 비밀번호 없음 — 워커는 path·token으로 API의 /mcp/db/{id}에 붙는다.
 * token이 null이면 쓸 수 있는 연결이 없다(notices만 프롬프트에). ⚠️ token은 로그 금지 — toString이 가린다.
 */
public record DbMcpGrant(String token, List<Server> servers, List<String> notices) {

    public record Server(String name, String path, String label, String dbType) {}

    public DbMcpGrant {
        servers = servers == null ? List.of() : List.copyOf(servers);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    @Override
    public String toString() {
        return "DbMcpGrant[token=" + (token == null ? "null" : "****") + ", servers=" + servers + ", notices=" + notices + "]";
    }
}
