package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 목록 행·세션 상세 응답의 superpowersEnabled (설계 2026-11-03-interview-superpowers-toggle §4) —
 * 승인 다이얼로그 프리필(`/api/tasks/{id}/interviews`)과 인터뷰 패널(세션 응답)이 읽는다.
 */
class InterviewSummaryResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static InterviewSession session(boolean superpowersEnabled) {
        InterviewSession s = InterviewSession.create(
                "owner/repo", "main", "제목", "설명", "u1", List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(s, "id", 7L);
        s.setStatus(InterviewStatus.PLAN_READY);
        s.setSuperpowersEnabled(superpowersEnabled);
        return s;
    }

    @Test
    void 요약과_세션응답은_세션값() {
        InterviewSession off = session(false);
        InterviewSession on = session(true);

        assertThat(InterviewSummaryResponse.of(off).superpowersEnabled()).isFalse();
        assertThat(InterviewSummaryResponse.of(on).superpowersEnabled()).isTrue();

        InterviewResponse offResponse = InterviewResponse.of(off, List.of(), null);
        assertThat(offResponse.superpowersEnabled()).isFalse();
        assertThat(InterviewResponse.of(off, List.of(), null, List.of(), java.util.Map.of()).superpowersEnabled()).isFalse();
        // 질문 세션 응답을 채우는 사본(withDbConnections)도 값을 그대로 싣는다
        assertThat(offResponse.withDbConnections(List.of()).superpowersEnabled()).isFalse();
        assertThat(InterviewResponse.of(on, List.of(), null).withDbConnections(List.of()).superpowersEnabled()).isTrue();
    }

    @Test
    void 요약_JSON에_superpowersEnabled_칸이_나간다() {
        JsonNode off = MAPPER.valueToTree(InterviewSummaryResponse.of(session(false)));
        JsonNode on = MAPPER.valueToTree(InterviewSummaryResponse.of(session(true)));

        assertThat(off.get("superpowersEnabled").isBoolean()).isTrue();
        assertThat(off.get("superpowersEnabled").asBoolean()).isFalse();
        assertThat(on.get("superpowersEnabled").asBoolean()).isTrue();
        // 기존 칸은 그대로
        assertThat(off.get("statusName").asText()).isEqualTo("PLAN_READY");
        assertThat(off.get("dbConnectionIds").isArray()).isTrue();
    }

    @Test
    void 세션응답_JSON에도_superpowersEnabled_칸이_나간다() {
        JsonNode off = MAPPER.valueToTree(InterviewResponse.of(session(false), List.of(), null));
        JsonNode on = MAPPER.valueToTree(InterviewResponse.of(session(true), List.of(), null));

        assertThat(off.get("superpowersEnabled").isBoolean()).isTrue();
        assertThat(off.get("superpowersEnabled").asBoolean()).isFalse();
        assertThat(on.get("superpowersEnabled").asBoolean()).isTrue();
    }
}
