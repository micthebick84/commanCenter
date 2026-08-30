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
}
