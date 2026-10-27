package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 보고 JSON — 레코드에 생성자가 넷(정규 25개 + 호환 24개·23개·22개)이어도 Jackson은 정규 생성자로 읽는다.
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
        // 사유에 인증 URL이 섞여 있으면 마스킹 경로에서 토큰이 사라진다
        assertThat(WorkerResultRequest.closeFailed("w", "사유 https://oauth2:tok@github.com/x", null)
                .masked().failureReason()).doesNotContain("tok");
    }

    @Test
    void the_implementation_attempt_survives_json_and_masking_and_old_reports_read_null() throws Exception {
        var lenient = json.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        WorkerResultRequest req = WorkerResultRequest.closed("w1", null).withImplementationAttempt(3);

        WorkerResultRequest back = lenient.readValue(json.writeValueAsString(req), WorkerResultRequest.class);

        assertThat(back.implementationAttempt()).isEqualTo(3);
        assertThat(req.masked().implementationAttempt()).isEqualTo(3);
        assertThat(json.readValue("{\"workerId\":\"w1\",\"status\":\"PR_CREATED\"}", WorkerResultRequest.class)
                .implementationAttempt()).isNull();
    }

    /** 참조 repo 변경 경고 (스펙 2026-10-24 §4.8) — 시도 번호 사본·JSON·가림을 지나도 남고, 옛 보고는 null이다. */
    @Test
    void ref_warnings_survive_json_masking_and_the_attempt_copy() throws Exception {
        var lenient = json.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        WorkerResultRequest req = new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/7", 7, "netismaker/task-1", "abc123", "log",
                null, null, null, null, null,
                null, null, null, null, null)
                .withRefWarnings(List.of("옛앱: 파일 3개", "공통 UI: https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/ui.git"))
                .withImplementationAttempt(2);

        assertThat(req.refWarnings()).hasSize(2);
        assertThat(req.implementationAttempt()).isEqualTo(2);
        WorkerResultRequest back = lenient.readValue(json.writeValueAsString(req), WorkerResultRequest.class);
        assertThat(back).isEqualTo(req);
        assertThat(req.masked().refWarnings())
                .containsExactly("옛앱: 파일 3개", "공통 UI: https://***@gitlab.hamon.vip/g/ui.git");
        assertThat(req.masked().implementationAttempt()).isEqualTo(2);
        // 경고를 붙여도 다른 필드는 그대로
        assertThat(req.withRefWarnings(null).refWarnings()).isNull();
        assertThat(req.withRefWarnings(null).prUrl()).isEqualTo("https://github.com/acme/widgets/pull/7");

        WorkerResultRequest old = json.readValue("{\"workerId\":\"w1\",\"status\":\"PR_CREATED\"}", WorkerResultRequest.class);
        assertThat(old.refWarnings()).isNull();
        assertThat(old.masked().refWarnings()).isNull();
    }

    @Test
    void masked_cuts_each_warning_to_500_after_masking() {
        String longWarning = "옛앱: " + "x".repeat(495) + "😀" + "y".repeat(100);   // 600자 남짓, 500번째에 서로게이트 앞 절반
        // 원문에서는 토큰 끝의 '@'가 500자 밖이라 먼저 자르면 mask가 알아보지 못해 토큰이 남는다
        String tokenAtTheEdge = "x".repeat(480) + " https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git" + "z".repeat(60);
        WorkerResultRequest req = WorkerResultRequest.closed("w1", null)
                .withRefWarnings(List.of(longWarning, tokenAtTheEdge, "짧은 경고"));

        List<String> masked = req.masked().refWarnings();

        assertThat(masked.get(0)).isEqualTo("옛앱: " + "x".repeat(495)).hasSize(499);
        assertThat(masked.get(1)).isEqualTo("x".repeat(480) + " https://***@gitlab.").hasSize(500);
        assertThat(masked.get(2)).isEqualTo("짧은 경고");
    }
}
