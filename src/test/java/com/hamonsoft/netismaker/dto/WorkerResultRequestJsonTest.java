package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 보고 JSON — 레코드에 생성자가 둘(정규 23개 + 기존 22개)이어도 Jackson은 정규 생성자로 읽는다.
 * 워커 전송·API 수신·dead-letter 재전송이 모두 이 경로다 (스펙 2026-10-07 §5, §9).
 */
class WorkerResultRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void env_template_survives_a_json_round_trip() throws Exception {
        var req = new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/7", 7, "netismaker/task-1", "abc123", "log",
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(new EnvTemplateItem("JWT_SECRET", "토큰 서명 키", true, true)));

        WorkerResultRequest back = json.readValue(json.writeValueAsString(req), WorkerResultRequest.class);

        assertThat(back).isEqualTo(req);
    }

    @Test
    void a_report_without_env_template_reads_as_null() throws Exception {
        // 구버전 워커 보고·dead-letter에 남은 옛 보고
        WorkerResultRequest back = json.readValue(
                "{\"workerId\":\"w1\",\"status\":\"PR_CREATED\",\"prUrl\":\"u\",\"headBranch\":\"b\"}",
                WorkerResultRequest.class);

        assertThat(back.envTemplate()).isNull();
        assertThat(back.prUrl()).isEqualTo("u");
    }

    @Test
    void close_reports_survive_a_json_round_trip() throws Exception {
        var usage = new WorkerResultRequest.UsageReport(new java.math.BigDecimal("0.12"), 100L, 50L, 0L, 0L);
        // UsageReport.isEmpty()가 "empty" 속성으로 직렬화된다 — 스프링 부트 매퍼처럼 모르는 속성은 무시
        var lenient = json.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        for (WorkerResultRequest req : List.of(
                WorkerResultRequest.closed("w1", usage),
                WorkerResultRequest.closeFailed("w1", "CI 대기 중: checks pending", null))) {
            WorkerResultRequest back = lenient.readValue(json.writeValueAsString(req), WorkerResultRequest.class);
            assertThat(back).isEqualTo(req);
        }
    }

    @Test
    void close_factories_set_status_and_reason() {
        assertThat(WorkerResultRequest.closed("w1", null).status()).isEqualTo(TaskStatus.CLOSED);
        WorkerResultRequest failed = WorkerResultRequest.closeFailed("w1", "충돌", null);
        assertThat(failed.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(failed.failureReason()).isEqualTo("충돌");
        // 마스킹 경로(reportTerminal)를 지나도 사유가 유지된다
        assertThat(failed.masked().failureReason()).isEqualTo("충돌");
    }
}
