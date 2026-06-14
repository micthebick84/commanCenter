package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewTurn;
import org.junit.jupiter.api.Test;

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
}
