package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbMcpWorkerSupportTest {

    private final DbMcpGrant grant = new DbMcpGrant("tok",
            List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영 (MySQL)", "MYSQL")),
            List.of("DB 연결 #13를 사용할 수 없습니다"));

    @Test
    void appends_http_servers_with_bearer_header() {
        List<TaskMcpSpec> out = DbMcpWorkerSupport.withDbServers(
                List.of(new TaskMcpSpec("ctx7", "https://c", "http")), grant, "http://localhost:8090/");
        assertThat(out).hasSize(2);
        assertThat(out.get(1)).isEqualTo(new TaskMcpSpec("db-12", "http://localhost:8090/mcp/db/12", "http",
                Map.of("Authorization", "Bearer tok")));
    }

    @Test
    void no_grant_or_no_token_keeps_extras() {
        List<TaskMcpSpec> extras = List.of(new TaskMcpSpec("ctx7", "https://c", "http"));
        assertThat(DbMcpWorkerSupport.withDbServers(extras, null, "http://x")).isEqualTo(extras);
        assertThat(DbMcpWorkerSupport.withDbServers(null, new DbMcpGrant(null, List.of(), List.of("꺼짐")), "http://x")).isEmpty();
    }

    @Test
    void prompt_section_lists_tools_and_notices_without_token() {
        String s = DbMcpWorkerSupport.promptSection(grant);
        assertThat(s).contains("## 데이터베이스 조회 (읽기 전용)")
                .contains("- mcp__db-12__query / list_tables / describe_table : 운영 (MySQL)")
                .contains("참고: DB 연결 #13를 사용할 수 없습니다")
                .doesNotContain("tok");
        assertThat(DbMcpWorkerSupport.promptSection(null)).isEmpty();
    }
}
