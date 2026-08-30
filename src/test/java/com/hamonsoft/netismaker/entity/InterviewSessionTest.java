package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionTest {

    @Test
    void create_initializes_queued_with_defaults() {
        InterviewSession s = InterviewSession.create(
                "owner/repo", "  ", "제목", "기능 요구", "user1",
                List.of(new TaskMcpSpec("ctx7", "https://x", "http")), "claude-opus-4-8", "high");
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
        InterviewSession s = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of(), "claude-opus-4-8", "high");
        assertThat(s.isOwnedBy("u1")).isTrue();
        assertThat(s.isOwnedBy("u2")).isFalse();
    }

    @Test
    void create_defaults_kind_to_interview() {
        InterviewSession s = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of(), "claude-opus-4-8", "high");
        assertThat(s.getKind()).isEqualTo(InterviewKind.INTERVIEW);
        assertThat(s.isQuestion()).isFalse();
    }

    @Test
    void createQuestion_sets_kind_question_keeps_other_columns_and_no_task() {
        InterviewSession s = InterviewSession.createQuestion(
                "owner/repo", "", "인증 흐름", "로그인은 어디서 처리되나요?", "user1",
                List.of(new TaskMcpSpec("ctx7", "https://x", "http")), "claude-sonnet-5", "medium");
        assertThat(s.getKind()).isEqualTo(InterviewKind.QUESTION);
        assertThat(s.isQuestion()).isTrue();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getGithubBranch()).isEqualTo("main");        // blank → main (create와 동일)
        assertThat(s.getDescription()).isEqualTo("로그인은 어디서 처리되나요?"); // description = 질문 본문
        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
        assertThat(s.getMcpsExtra()).hasSize(1);
        assertThat(s.getTaskId()).isNull();
        assertThat(s.getCurrentPhase()).isNull();
    }
}
