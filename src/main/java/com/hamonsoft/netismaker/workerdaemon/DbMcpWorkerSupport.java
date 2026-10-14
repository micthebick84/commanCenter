package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구현·디자인 단계 DB MCP (스펙 2026-10-14 §6.4). grant를 http MCP 스펙(토큰 헤더)으로 바꾸고 프롬프트 안내를 만든다.
 * 토큰은 임시 mcp-config 파일에만 실리고 ClaudeExecAdapter가 exec 뒤 지운다. 프롬프트에는 토큰을 넣지 않는다.
 */
final class DbMcpWorkerSupport {

    private DbMcpWorkerSupport() {}

    static List<TaskMcpSpec> withDbServers(List<TaskMcpSpec> extras, DbMcpGrant grant, String apiBaseUrl) {
        List<TaskMcpSpec> out = new ArrayList<>(extras == null ? List.of() : extras);
        if (grant == null || grant.token() == null || grant.servers().isEmpty()) return out;
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        for (DbMcpGrant.Server s : grant.servers()) {
            out.add(new TaskMcpSpec(s.name(), base + s.path(), "http",
                    Map.of("Authorization", "Bearer " + grant.token())));
        }
        return out;
    }

    static String promptSection(DbMcpGrant grant) {
        if (grant == null || (grant.servers().isEmpty() && grant.notices().isEmpty())) return "";
        StringBuilder sb = new StringBuilder("\n\n## 데이터베이스 조회 (읽기 전용)\n");
        if (!grant.servers().isEmpty() && grant.token() != null) {
            sb.append("이 작업에는 아래 DB 조회 도구가 붙어 있습니다. 스키마·데이터를 확인할 때 쓰세요. ")
              .append("SELECT 계열 단일 문장만 실행되고 최대 200행·30초 제한입니다. ")
              .append("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다.\n");
            for (DbMcpGrant.Server s : grant.servers()) {
                sb.append("- mcp__").append(s.name()).append("__query / list_tables / describe_table : ")
                  .append(s.label()).append('\n');
            }
        }
        for (String n : grant.notices()) sb.append("참고: ").append(n).append('\n');
        return sb.toString();
    }
}
