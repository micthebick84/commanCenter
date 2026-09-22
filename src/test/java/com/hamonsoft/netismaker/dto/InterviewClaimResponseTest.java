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

    // ── 질문 첨부·attachmentRoot (스펙 2026-09-13 §5.1) ────────────────────────

    @Test
    void of_five_arg_carries_attachment_root_and_per_turn_attachments_non_null() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        ReflectionTestUtils.setField(q, "id", 1L);
        var t0 = com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "답", null);
        var t1 = com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0);
        var ref = new InterviewClaimResponse.AttachmentRef(12L, "로그.txt", "/abs/question-1/1/1-로그.txt",
                "text/plain", 5L, null);

        InterviewClaimResponse r = InterviewClaimResponse.of(q, List.of(t0, t1), List.of(),
                java.util.Map.of(1, List.of(ref)), "/abs/question-1");

        assertThat(r.attachmentRoot()).isEqualTo("/abs/question-1");
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(1).attachments()).containsExactly(ref);
    }

    @Test
    void three_arg_of_keeps_legacy_shape_null_root_and_empty_turn_attachments() {
        var t0 = com.hamonsoft.netismaker.entity.InterviewTurn.of(7L, 0, "assistant", "question", "q", null);
        InterviewClaimResponse r = InterviewClaimResponse.of(session(), List.of(t0), List.of());
        assertThat(r.attachmentRoot()).isNull();
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
    }

    @Test
    void turn_normalizes_null_attachments_to_empty_and_attachment_ref_defaults_extracted_path_to_null() {
        var turn = new InterviewClaimResponse.Turn(1, "user", "answer", "x", 0, null);
        assertThat(turn.attachments()).isNotNull().isEmpty();
        var legacy = new InterviewClaimResponse.AttachmentRef(3L, "a.pdf", "/abs/a.pdf", "application/pdf", 1L);
        assertThat(legacy.extractedTextPath()).isNull();
        var office = new InterviewClaimResponse.AttachmentRef(4L, "a.docx", "/abs/a.docx", null, 1L, "/abs/a.docx.txt");
        assertThat(office.extractedTextPath()).isEqualTo("/abs/a.docx.txt");
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

    @Test
    void of_carries_git_url_and_host() {
        InterviewSession s = InterviewSession.createQuestion("g/sub/p", "main", "t", "q", "admin",
                List.of(), "claude-opus-5", "high");
        s.setGitUrl("https://gitlab.hamon.vip/g/sub/p.git");
        ReflectionTestUtils.setField(s, "id", 5L);
        InterviewClaimResponse r = InterviewClaimResponse.of(s, List.of(), List.of());
        assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/g/sub/p.git");
        assertThat(r.repoHost()).isEqualTo("gitlab");
        assertThat(r.repoRef().path()).isEqualTo("g/sub/p");
    }
}
