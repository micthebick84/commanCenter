package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionSummaryResponseTest {

    /** 사이드바 행/헤더 칩이 쓰는 컨텍스트 스냅샷이 목록 응답에도 실린다 (스펙 2026-09-05 §5.3). */
    @Test
    void of_carries_context_snapshot() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "인증 흐름", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setContextTokens(76004L);
        q.setContextWindow(200000L);
        QuestionSummaryResponse r = QuestionSummaryResponse.of(q);
        assertThat(r.title()).isEqualTo("인증 흐름");
        assertThat(r.statusName()).isEqualTo("QUEUED");
        assertThat(r.contextTokens()).isEqualTo(76004L);
        assertThat(r.contextWindow()).isEqualTo(200000L);
    }
}
