package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.hamonsoft.netismaker.entity.ImplementationMode.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 방식별 구현 프롬프트 조각 (스펙 2026-10-15 §6.2~§6.4·§6.7). */
class ImplementationPromptsTest {

    private static final String PLAN = "# 계획\n\n### 작업 1: 섹션\n";

    private static WorkerTaskResponse task(String plan) {
        Task t = Task.create("owner/repo", "main", "제목", "요구사항 본문", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 13L);
        return WorkerTaskResponse.forImplementation(t, null, null, plan);
    }

    @Test
    void direct_is_exactly_the_existing_plan_section_and_commit_rule() {
        assertThat(ImplementationPrompts.planSection(task(PLAN), DIRECT, "base1", false))
                .isEqualTo(WorkerMainLoop.renderPlanSection(task(PLAN)));
        assertThat(ImplementationPrompts.commitRule(DIRECT))
                .isEqualTo("의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)");
        assertThat(ImplementationPrompts.commitRule(REVIEW)).isEqualTo(ImplementationPrompts.DIRECT_COMMIT_RULE);
    }

    @Test
    void review_keeps_direct_implementation_and_adds_one_opus_review_with_the_scope_rule() {
        String s = ImplementationPrompts.planSection(task(PLAN), REVIEW, "abc1234", false);

        assertThat(s).contains("### 작업 1: 섹션")
                .contains("리뷰만 아래 \"마지막 리뷰\"대로 서브에이전트 하나에 맡깁니다")
                .contains("model opus")
                .contains("계획 범위 안의 결함")
                .contains("계획에 없는 동작 규칙 변경")
                .contains(".netis/review-notes.md")
                .contains("The approved plan is in `.netis/plan/task-13-plan.md`. Read it in full.")
                .contains("git diff abc1234")
                .doesNotContain("__REVIEWER__").doesNotContain("__REQUIREMENTS__").doesNotContain("__BASE_SHA__")
                .doesNotContain("MIT License").doesNotContain("<!--");
    }

    @Test
    void review_without_a_plan_reviews_against_the_requirements() {
        String s = ImplementationPrompts.planSection(task(null), REVIEW, "abc1234", false);

        assertThat(s).doesNotContain("확정된 구현 계획")
                .contains("There is no plan file")
                .contains("\"요구사항\" 원문");
    }

    @Test
    void sdd_points_at_the_plan_file_and_forbids_push_finishing_and_questions() {
        String s = ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", true);

        assertThat(s).contains("`.netis/plan/task-13-plan.md`")
                .contains("`.netis/plan/task-13-design.md`")
                .contains("superpowers:subagent-driven-development")
                .contains("새 worktree나 브랜치를 만들지 말고")
                .contains("git push")
                .contains("finishing-a-development-branch")
                .contains("Ruling")
                .contains("계획 범위 안의 결함")
                .contains(".netis/review-notes.md")
                .doesNotContain("### 작업 1: 섹션");   // 본문은 넣지 않고 경로만
        assertThat(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false)).doesNotContain("-design.md");
        assertThat(ImplementationPrompts.commitRule(SDD)).contains("태스크별 커밋").contains("push는 하지 마세요");
    }

    @Test
    void mode_line_names_attempt_and_fallback_with_korean_particles() {
        assertThat(ImplementationPrompts.modeLine(REVIEW, REVIEW, 1, null)).isEqualTo("- **구현 방식**: 리뷰\n");
        assertThat(ImplementationPrompts.modeLine(SDD, SDD, 2, null)).isEqualTo("- **구현 방식**: SDD (2회째)\n");
        assertThat(ImplementationPrompts.modeLine(SDD, REVIEW, 1, ImplementationModeResolver.NO_PLUGIN))
                .isEqualTo("- **구현 방식**: 리뷰 (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)\n");
        assertThat(ImplementationPrompts.modeLine(SDD, DIRECT, 3, ImplementationModeResolver.PLAN_WRITE_FAILED))
                .isEqualTo("- **구현 방식**: 직접 (3회째) (SDD 요청 — 계획 파일 쓰기 실패로 직접으로 진행)\n");
    }

    @Test
    void with_ro_follows_the_final_consonant() {
        assertThat(ImplementationPrompts.withRo("리뷰")).isEqualTo("리뷰로");
        assertThat(ImplementationPrompts.withRo("직접")).isEqualTo("직접으로");
        assertThat(ImplementationPrompts.withRo("서울")).isEqualTo("서울로");
        assertThat(ImplementationPrompts.withRo("SDD")).isEqualTo("SDD로");
    }
}
