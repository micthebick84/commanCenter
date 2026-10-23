package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest.RefState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질문 답변 보고 JSON (스펙 2026-10-22 §4.5) — 레코드에 생성자가 둘(정규 11개 + 구버전 10개)이어도 Jackson은 정규
 * 생성자로 읽는다. 새 러너는 refStates를 싣고, 구버전 러너는 보내지 않는다(→ null, API는 아무것도 바꾸지 않는다).
 */
class WorkerQuestionRequestJsonTest {

    private static final String SHA = "a".repeat(40);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ref_states_bind_from_the_runner_report() throws Exception {
        WorkerQuestionRequest req = json.readValue("""
                {"content":"답변","claudeSessionId":"s1","kind":"question","costUsd":0.01,
                 "refStates":[
                   {"catalogId":11,"branch":"release/6.5","sha":"%s","error":null},
                   {"catalogId":12,"branch":"main","sha":null,"error":"브랜치를 찾을 수 없음"}]}
                """.formatted(SHA), WorkerQuestionRequest.class);

        assertThat(req.content()).isEqualTo("답변");
        assertThat(req.refStates()).containsExactly(
                new RefState(11L, "release/6.5", SHA, null),
                new RefState(12L, "main", null, "브랜치를 찾을 수 없음"));
    }

    @Test
    void a_report_without_ref_states_reads_as_null() throws Exception {
        // 구버전 러너 — refStates 필드를 아예 보내지 않는다
        WorkerQuestionRequest req = json.readValue(
                "{\"content\":\"답변\",\"claudeSessionId\":\"s1\",\"kind\":\"question\",\"contextWindow\":200000}",
                WorkerQuestionRequest.class);

        assertThat(req.refStates()).isNull();
        assertThat(req.content()).isEqualTo("답변");
        assertThat(req.contextWindow()).isEqualTo(200000L);
    }

    @Test
    void the_old_ten_argument_constructor_leaves_ref_states_null() {
        WorkerQuestionRequest req = new WorkerQuestionRequest("q", "s", "question", BigDecimal.ONE,
                1L, 2L, 3L, 4L, 5L, 6L);

        assertThat(req.refStates()).isNull();
        assertThat(req.contextTokens()).isEqualTo(5L);
        assertThat(req.contextWindow()).isEqualTo(6L);
    }

    @Test
    void a_report_with_ref_states_survives_a_json_round_trip() throws Exception {
        var req = new WorkerQuestionRequest("답변", "s1", "question", new BigDecimal("0.01"),
                1L, 2L, 3L, 4L, 5L, 6L, List.of(new RefState(11L, "main", SHA, null)));

        WorkerQuestionRequest back = json.readValue(json.writeValueAsString(req), WorkerQuestionRequest.class);

        assertThat(back).isEqualTo(req);
    }
}
