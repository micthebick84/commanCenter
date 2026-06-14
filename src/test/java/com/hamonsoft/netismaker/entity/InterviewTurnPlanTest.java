package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewTurnPlanTest {

    @Test
    void turn_of_sets_all_fields() {
        InterviewTurn t = InterviewTurn.of(7L, 3, "assistant", "question", "어떤 화면에 추가하나요?", null);
        assertThat(t.getSessionId()).isEqualTo(7L);
        assertThat(t.getSeq()).isEqualTo(3);
        assertThat(t.getRole()).isEqualTo("assistant");
        assertThat(t.getKind()).isEqualTo("question");
        assertThat(t.getContent()).isEqualTo("어떤 화면에 추가하나요?");
        assertThat(t.getReplyToSeq()).isNull();   // question 턴은 reply_to_seq 없음
        assertThat(t.getCreatedAt()).isNotNull();
    }

    @Test
    void turn_of_answer_records_reply_to_seq() {
        InterviewTurn t = InterviewTurn.of(7L, 4, "user", "answer", "좌측 패널에 추가", 3);
        assertThat(t.getKind()).isEqualTo("answer");
        assertThat(t.getReplyToSeq()).isEqualTo(3);   // seq 3 question에 대한 답변
    }

    @Test
    void plan_create_sets_all_fields_and_defaults_json() {
        InterviewPlan p = InterviewPlan.create(7L, "# 설계", "# 플랜", null,
                1234L, new BigDecimal("0.42"));
        assertThat(p.getSessionId()).isEqualTo(7L);
        assertThat(p.getDesignMarkdown()).isEqualTo("# 설계");
        assertThat(p.getPlanMarkdown()).isEqualTo("# 플랜");
        assertThat(p.getPlanJson()).isEqualTo("[]");   // null → "[]"
        assertThat(p.getDurationMs()).isEqualTo(1234L);
        assertThat(p.getTotalCostUsd()).isEqualByComparingTo("0.42");
        assertThat(p.getCompletedAt()).isNotNull();
    }
}
