package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 구현·디자인 claim의 DB MCP 지시 (스펙 2026-10-14 §6.4). 비밀번호 없음 — 워커는 path·token으로 API의 /mcp/db/{id}에 붙는다.
 * token이 null이면 쓸 수 있는 연결이 없다(notices만 프롬프트에). ⚠️ token은 로그 금지 — toString이 가린다.
 */
public record DbMcpGrant(String token, List<Server> servers, List<String> notices) {

    /**
     * writable: 이 연결에 구현 단계 쓰기 도구(execute)가 열리는지 (스펙 2026-11-03 §6.1) — 프롬프트 DB 절을 연결별로 나누는 데 쓴다.
     * 옛 API가 보낸 JSON에는 칸이 없어 false로 읽힌다. 실행 때 서버가 조건을 다시 본다.
     */
    public record Server(String name, String path, String label, String dbType, boolean writable) {

        /** 읽기만 되는 연결 — 쓰기 칸이 생기기 전 호출 호환. */
        public Server(String name, String path, String label, String dbType) {
            this(name, path, label, dbType, false);
        }
    }

    public DbMcpGrant {
        servers = servers == null ? List.of() : List.copyOf(servers);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    @Override
    public String toString() {
        return "DbMcpGrant[token=" + (token == null ? "null" : "****") + ", servers=" + servers + ", notices=" + notices + "]";
    }
}
