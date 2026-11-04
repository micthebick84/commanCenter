package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest.RefState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 보고 JSON (스펙 2026-10-24 §4.5) — 질문 보고처럼 refStates를 싣는다. 레코드에 생성자가 둘(정규 10개 + 구버전 9개)이어도
 * Jackson은 정규 생성자로 읽는다. 참조 없는 인터뷰·구버전 러너는 키를 보내지 않는다(→ null, API는 참조를 바꾸지 않는다).
 */
class WorkerPlanRequestJsonTest {

    private static final String SHA = "a".repeat(40);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ref_states_bind_from_the_runner_plan_report() throws Exception {
        WorkerPlanRequest req = json.readValue("""
                {"designMarkdown":"# 설계","planMarkdown":"# 플랜","planJson":"[]","costUsd":0.1,"durationMs":1000,
                 "refStates":[
                   {"catalogId":11,"branch":"release/6.5","sha":"%s","error":null},
                   {"catalogId":12,"branch":"main","sha":null,"error":"브랜치를 찾을 수 없음"}]}
                """.formatted(SHA), WorkerPlanRequest.class);

        assertThat(req.designMarkdown()).isEqualTo("# 설계");
        assertThat(req.durationMs()).isEqualTo(1000L);
        assertThat(req.refStates()).containsExactly(
                new RefState(11L, "release/6.5", SHA, null),
                new RefState(12L, "main", null, "브랜치를 찾을 수 없음"));
    }

    @Test
    void a_plan_report_without_ref_states_reads_as_null() throws Exception {
        WorkerPlanRequest req = json.readValue(
                "{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1000}",
                WorkerPlanRequest.class);

        assertThat(req.refStates()).isNull();
        assertThat(req.planMarkdown()).isEqualTo("# 플랜");
        assertThat(req.costUsd()).isEqualByComparingTo("0.1");
    }

    @Test
    void the_old_nine_argument_constructor_leaves_ref_states_null() {
        WorkerPlanRequest req = new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE,
                1L, 2L, 3L, 4L, 5L);

        assertThat(req.refStates()).isNull();
        assertThat(req.cacheReadTokens()).isEqualTo(4L);
        assertThat(req.durationMs()).isEqualTo(5L);
    }

    /**
     * 간단 인터뷰(superpowers 끔) 러너는 계획 보고에 claudeSessionId를 싣는다 (설계 2026-11-03-interview-superpowers-toggle §4.1, RF1) —
     * 첫 턴에 바로 계획이 나온 세션의 "계획 고쳐 달라기"가 같은 대화를 이어 가게. 켬·구버전 러너는 키가 없다(→ null, 세션 값 유지).
     */
    @Test
    void 계획_JSON_claudeSessionId_바인딩() throws Exception {
        WorkerPlanRequest with = json.readValue(
                "{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 계획 v1\",\"planJson\":\"[]\",\"costUsd\":0.1,"
                        + "\"durationMs\":1000,\"claudeSessionId\":\"sess-1\"}",
                WorkerPlanRequest.class);
        WorkerPlanRequest without = json.readValue(
                "{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 계획 v1\",\"planJson\":\"[]\",\"costUsd\":0.1,"
                        + "\"durationMs\":1000}",
                WorkerPlanRequest.class);

        assertThat(with.claudeSessionId()).isEqualTo("sess-1");
        assertThat(with.planMarkdown()).isEqualTo("# 계획 v1");
        assertThat(with.refStates()).isNull();
        assertThat(without.claudeSessionId()).isNull();
        assertThat(without.planMarkdown()).isEqualTo("# 계획 v1");
    }

    @Test
    void 이전_생성자_10인자와_9인자는_claudeSessionId가_null() {
        var ten = new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE,
                1L, 2L, 3L, 4L, 5L, List.of(new RefState(11L, "main", SHA, null)));
        var nine = new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE,
                1L, 2L, 3L, 4L, 5L);

        assertThat(ten.claudeSessionId()).isNull();
        assertThat(ten.refStates()).containsExactly(new RefState(11L, "main", SHA, null));
        assertThat(nine.claudeSessionId()).isNull();
        assertThat(nine.refStates()).isNull();
    }

    @Test
    void claudeSessionId를_실은_계획_보고도_JSON_왕복이_같다() throws Exception {
        var req = new WorkerPlanRequest("# 설계", "# 플랜", "[]", new BigDecimal("0.01"),
                1L, 2L, 3L, 4L, 5L, null, "sess-1");

        String body = json.writeValueAsString(req);
        WorkerPlanRequest back = json.readValue(body, WorkerPlanRequest.class);

        assertThat(body).contains("\"claudeSessionId\":\"sess-1\"");
        assertThat(back).isEqualTo(req);
    }

    @Test
    void a_plan_report_with_ref_states_survives_a_json_round_trip() throws Exception {
        var req = new WorkerPlanRequest("# 설계", "# 플랜", "[]", new BigDecimal("0.01"),
                1L, 2L, 3L, 4L, 5L, List.of(new RefState(11L, "main", SHA, null)));

        WorkerPlanRequest back = json.readValue(json.writeValueAsString(req), WorkerPlanRequest.class);

        assertThat(back).isEqualTo(req);
    }
}
