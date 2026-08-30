package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewClaimResponseTest {

    private InterviewSession session() {
        InterviewSession s = InterviewSession.create(
                "owner/repo", "main", "제목", "설명", "u1", List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", 7L);
        return s;
    }

    @Test
    void of_carries_accumulated_session_totalCostUsd_so_the_worker_guard_is_cumulative() {
        InterviewSession s = session();
        ReflectionTestUtils.setField(s, "totalCostUsd", new BigDecimal("4.95"));

        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(4.95);
    }

    @Test
    void of_defaults_totalCostUsd_to_zero_for_a_fresh_session() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of());

        assertThat(r.totalCostUsd()).isEqualTo(0.0);
    }

    @Test
    void attachments가_null이어도_빈_리스트로_정규화된다_계약은_non_null() {
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), null);
        assertThat(r.attachments()).isNotNull().isEmpty();
    }

    @Test
    void attachments는_id_파일명_절대경로_타입_크기를_나른다() {
        var ref = new InterviewClaimResponse.AttachmentRef(
                3L, "요구사항.pdf", "/abs/task-7/1-요구사항.pdf", "application/pdf", 1234L);
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(), List.of(ref));
        assertThat(r.attachments()).containsExactly(ref);
        assertThat(r.attachments().get(0).absolutePath()).startsWith("/abs/");
    }

    @Test
    void of_carries_session_kind_as_enum_name() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(q, "id", 1L); // of()가 sessionId(long)로 unbox — 미영속 세션은 id set 필요
        InterviewClaimResponse r = InterviewClaimResponse.of(q, List.of(), List.of());
        assertThat(r.kind()).isEqualTo("QUESTION");

        InterviewSession i = InterviewSession.create("o/r", "main", "t", "d", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(i, "id", 2L);
        assertThat(InterviewClaimResponse.of(i, List.of(), List.of()).kind()).isEqualTo("INTERVIEW");
    }
}
