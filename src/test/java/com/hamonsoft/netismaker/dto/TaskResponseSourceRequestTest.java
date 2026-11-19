package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답의 sourceRequestId 칸 — 만드는 곳과 사본 다섯 곳이 모두 유지하고, null이어도 키가 나간다 (스펙 2026-11-19 §5.4). */
class TaskResponseSourceRequestTest {

    private Task task(Long sourceRequestId) {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(TaskStatus.AWAITING_APPROVAL);
        t.setSourceQuestionId(8L);
        t.setSourceRequestId(sourceRequestId);
        return t;
    }

    @Test
    void 응답과_사본이_sourceRequestId_유지() {
        TaskResponse r = TaskResponse.of(task(30L), null);
        assertThat(r.sourceRequestId()).isEqualTo(30L);
        assertThat(r.sourceQuestionId()).isEqualTo(8L);   // 옆 칸과 뒤바뀌지 않았다
        assertThat(TaskResponse.withTotalCost(r, BigDecimal.ONE).sourceRequestId()).isEqualTo(30L);
        assertThat(r.withRefRepos(List.of()).sourceRequestId()).isEqualTo(30L);
        assertThat(r.withFix(new TaskResponse.FixView(1, "DIRECT", 1, 3, "FIX")).sourceRequestId()).isEqualTo(30L);
        assertThat(r.withInterviewSuperpowersEnabled(false).sourceRequestId()).isEqualTo(30L);
        assertThat(r.redactSecretValues().sourceRequestId()).isEqualTo(30L);
        assertThat(r.redactSecretValues().sourceQuestionId()).isEqualTo(8L);
    }

    @Test
    void null이어도_키가_나간다() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = om.writeValueAsString(TaskResponse.of(task(null), null));
        assertThat(json).contains("\"sourceRequestId\":null");
    }
}
