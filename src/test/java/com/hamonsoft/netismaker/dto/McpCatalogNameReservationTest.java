package com.hamonsoft.netismaker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP 카탈로그 이름의 'db-' 접두사는 DB 연결 서버용 예약 (스펙 2026-10-02 §5.4). */
class McpCatalogNameReservationTest {

    private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    private static McpCatalogDto.UpsertRequest req(String name) {
        return new McpCatalogDto.UpsertRequest(name, "표시", "https://mcp.example/sse", "sse", null, true);
    }

    @Test
    void db_prefix_is_rejected_but_similar_names_pass() {
        assertThat(v.validate(req("db-1"))).isNotEmpty();
        assertThat(v.validate(req("db-tools"))).isNotEmpty();
        assertThat(v.validate(req("mydb-1"))).isEmpty();
        assertThat(v.validate(req("dbtools"))).isEmpty();
    }
}
