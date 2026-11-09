package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인 바디 — 레코드에 생성자가 여럿(정규 6개 + 호환 5·4·3개)이어도 Jackson은 정규 생성자로 읽는다.
 * taskType은 문자열 그대로 받고(해석·400은 서비스), 키가 없으면 null = 등록 값 유지(스펙 2026-11-03 §4.2).
 */
class ApproveRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void 승인_바디의_taskType_바인딩() throws Exception {
        ApproveRequest req = json.readValue("""
                {"mcpCatalogIds":[1],"model":"claude-opus-5-5","effort":"high","dbConnectionIds":[3],
                 "refRepos":[{"repoCatalogId":11,"branch":"main"}],"taskType":"DB_WORK"}
                """, ApproveRequest.class);

        assertThat(req).isEqualTo(new ApproveRequest(List.of(1L), "claude-opus-5-5", "high", List.of(3L),
                List.of(new RefRepoInput(11L, "main")), "DB_WORK"));
        assertThat(req.taskType()).isEqualTo("DB_WORK");
    }

    @Test
    void 키를_생략한_바디는_타입_유지() throws Exception {
        ApproveRequest omitted = json.readValue("{\"model\":\"claude-opus-5-5\"}", ApproveRequest.class);
        assertThat(omitted.taskType()).isNull();
        assertThat(json.readValue("{\"taskType\":null}", ApproveRequest.class).taskType()).isNull();
        // 바디가 아예 비어도 읽힌다
        assertThat(json.readValue("{}", ApproveRequest.class))
                .isEqualTo(new ApproveRequest(null, null, null, null, null, null));
    }

    @Test
    void 호환_생성자는_타입_유지() {
        // 5·4·3인자 — 기존 호출부·시험은 그대로 타입을 건드리지 않는다
        assertThat(new ApproveRequest(null, null, null, null, List.of(new RefRepoInput(11L, null))).taskType()).isNull();
        assertThat(new ApproveRequest(null, null, null, null).taskType()).isNull();
        assertThat(new ApproveRequest(null, null, null).taskType()).isNull();
        assertThat(new ApproveRequest(List.of(1L), "m", "e", List.of(2L), List.of(new RefRepoInput(3L, "b"))))
                .isEqualTo(new ApproveRequest(List.of(1L), "m", "e", List.of(2L),
                        List.of(new RefRepoInput(3L, "b")), null));
    }
}
