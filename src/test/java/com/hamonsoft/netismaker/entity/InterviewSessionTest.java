package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionTest {

    @Test
    void create_initializes_queued_with_defaults() {
        InterviewSession s = InterviewSession.create(
                "owner/repo", "  ", "제목", "기능 요구", "user1",
                List.of(new TaskMcpSpec("ctx7", "https://x", "http")));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getGithubBranch()).isEqualTo("main");            // blank → main
        assertThat(s.getRequesterId()).isEqualTo("user1");
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0");
        assertThat(s.getMcpsExtra()).hasSize(1);
        assertThat(s.getCreatedAt()).isNotNull();
        assertThat(s.getLastActivityAt()).isNotNull();
        assertThat(s.getTaskId()).isNull();
        assertThat(s.getClaudeSessionId()).isNull();
        assertThat(s.getWorkDir()).isNull();   // 첫 claim 전엔 미배정
    }

    @Test
    void is_owned_by_matches_requester() {
        InterviewSession s = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of());
        assertThat(s.isOwnedBy("u1")).isTrue();
        assertThat(s.isOwnedBy("u2")).isFalse();
    }
}
