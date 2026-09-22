package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewResponseTest {

    @Test
    void plan_ready_event_carries_all_three_fields() {
        var ev = new PlanReadyEvent("# 설계", "# 플랜", "[{\"title\":\"A\"}]");
        assertThat(ev.designMarkdown()).isEqualTo("# 설계");
        assertThat(ev.planMarkdown()).isEqualTo("# 플랜");
        assertThat(ev.planJson()).isEqualTo("[{\"title\":\"A\"}]");
    }

    @Test
    void turn_view_maps_seq_role_kind_content() {
        // Phase 1 팩토리 시그니처: InterviewTurn.of(sessionId, seq, role, kind, content, replyToSeq)
        InterviewTurn t = InterviewTurn.of(1L, 2, "assistant", "question", "어떤 인증?", null);
        var tv = new InterviewResponse.TurnView(
                t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq(), t.getCreatedAt());
        assertThat(tv.seq()).isEqualTo(2);
        assertThat(tv.role()).isEqualTo("assistant");
        assertThat(tv.kind()).isEqualTo("question");
    }

    @Test
    void of_exposes_both_korean_status_and_english_statusName() {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "제목", "설명", "u1", List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", 7L);
        s.setStatus(InterviewStatus.AWAITING_INPUT);

        InterviewResponse r = InterviewResponse.of(s, List.of(), null);

        assertThat(r.status()).isEqualTo("입력대기");          // 한글 dbValue (표시용) — 유지
        assertThat(r.statusName()).isEqualTo("AWAITING_INPUT"); // 영문 enum name (로직용) — 신규
    }

    @Test
    void of_carries_kind_and_total_cost() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setTotalCostUsd(new java.math.BigDecimal("1.25"));
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.kind()).isEqualTo("QUESTION");
        assertThat(r.totalCostUsd()).isEqualByComparingTo("1.25");
    }

    @Test
    void of_carries_context_snapshot() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setContextTokens(76004L);
        q.setContextWindow(200000L);
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isEqualTo(76004L);
        assertThat(r.contextWindow()).isEqualTo(200000L);
    }

    // ── 질문 첨부·MCP id (스펙 2026-09-13 §5.1) ────────────────────────────────

    @Test
    void of_five_arg_carries_mcp_ids_kickoff_attachments_and_per_turn_attachments() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setMcpCatalogIds(new java.util.ArrayList<>(List.of(3L, 4L)));
        InterviewTurn t0 = InterviewTurn.of(1L, 0, "assistant", "question", "답", null);
        InterviewTurn t1 = InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0);
        var kick = new InterviewResponse.AttachmentView(11L, "설계.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100L);
        var turnAtt = new InterviewResponse.AttachmentView(12L, "로그.txt", null, 5L);

        InterviewResponse r = InterviewResponse.of(q, List.of(t0, t1), null,
                List.of(kick), java.util.Map.of(1, List.of(turnAtt)));

        assertThat(r.mcpCatalogIds()).containsExactly(3L, 4L);
        assertThat(r.attachments()).containsExactly(kick);
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(1).attachments()).containsExactly(turnAtt);
    }

    @Test
    void three_arg_of_and_six_arg_turn_view_keep_legacy_shape_with_empty_non_null_lists() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewTurn t = InterviewTurn.of(1L, 2, "assistant", "question", "어떤 인증?", null);
        InterviewResponse r = InterviewResponse.of(q, List.of(t), null);
        assertThat(r.mcpCatalogIds()).isNotNull().isEmpty();
        assertThat(r.attachments()).isNotNull().isEmpty();
        assertThat(r.turns().get(0).attachments()).isNotNull().isEmpty();
        var tv = new InterviewResponse.TurnView(1, "user", "answer", "x", 0, t.getCreatedAt(), null);
        assertThat(tv.attachments()).isNotNull().isEmpty();
    }

    @Test
    void of_leaves_context_null_when_never_reported() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isNull();
        assertThat(r.contextWindow()).isNull();
    }
}
