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

    /**
     * 실제 claude 스모크에서 SDD 세션이 서브에이전트를 구현 하나만 띄우고 리뷰어는 하나도 띄우지 않았다 — 계획이 작아도
     * 태스크 리뷰와 최종 리뷰는 스킬이 정한 대로 서브에이전트로 돌려야 한다(스펙 §1 "작업마다 서브에이전트가 구현·리뷰").
     */
    @Test
    void sdd_says_the_task_reviews_and_the_final_review_run_as_subagents_even_for_a_small_plan() {
        String s = ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false);

        assertThat(s).contains("계획이 작아도")
                .contains("태스크 리뷰 서브에이전트")
                .contains("최종 리뷰 서브에이전트")
                .contains("스킬이 정한 대로")
                .contains("건너뛰거나 구현·다른 리뷰와 합치지 마세요");
        // 이 지시는 SDD 절에만 — 직접·리뷰 방식의 프롬프트는 그대로다
        assertThat(ImplementationPrompts.planSection(task(PLAN), REVIEW, "abc1234", false))
                .doesNotContain("태스크 리뷰 서브에이전트").doesNotContain("최종 리뷰 서브에이전트");
        assertThat(ImplementationPrompts.planSection(task(PLAN), DIRECT, "abc1234", false))
                .doesNotContain("태스크 리뷰 서브에이전트").doesNotContain("최종 리뷰 서브에이전트");
    }

    /** SDD 지시서는 Task N 사이 줄만 뽑아 계획 파일 머리의 참조 절을 놓친다 — 참조가 있을 때만 넘기라고 한 줄 더한다 (스펙 2026-10-24 §6.5). */
    @Test
    void sdd_section_carries_the_ref_line_only_with_refs() {
        String withRefs = ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false, true);

        assertThat(ImplementationPrompts.SDD_REF_LINE).isEqualTo(
                "- 서브에이전트(구현·리뷰)에게 일을 맡길 때 계획 파일 머리의 \"참조 repo\" 절을 그대로 함께 넘긴다.\n");
        assertThat(withRefs).endsWith(ImplementationPrompts.SDD_REF_LINE)
                .isEqualTo(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false)
                        + ImplementationPrompts.SDD_REF_LINE);
        assertThat(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false))
                .doesNotContain(ImplementationPrompts.SDD_REF_LINE);
        assertThat(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false, false))
                .isEqualTo(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false));
        // 리뷰·직접 방식은 참조가 있어도 이 줄이 없다(참조 절은 프롬프트 끝과 계획 파일 머리에 있다)
        assertThat(ImplementationPrompts.planSection(task(PLAN), REVIEW, "abc1234", false, true))
                .doesNotContain(ImplementationPrompts.SDD_REF_LINE)
                .isEqualTo(ImplementationPrompts.planSection(task(PLAN), REVIEW, "abc1234", false));
        assertThat(ImplementationPrompts.planSection(task(PLAN), DIRECT, "abc1234", false, true))
                .doesNotContain(ImplementationPrompts.SDD_REF_LINE)
                .isEqualTo(ImplementationPrompts.planSection(task(PLAN), DIRECT, "abc1234", false));
    }

    // ---- 인터뷰 목업 (설계 2026-11-03 §7 — SDD는 계획 파일 머리를 넘기라는 줄, 리뷰는 리뷰어 요구 한 줄) ----

    private static final List<WorkerTaskResponse.InterviewMockupFile> MOCKUPS = List.of(
            new WorkerTaskResponse.InterviewMockupFile(3, "list-filter-a", 2, "A안", "<html>a</html>"));

    private static WorkerTaskResponse withDesign(WorkerTaskResponse t) {
        return new WorkerTaskResponse(t.id(), t.githubRepo(), t.githubBranch(), t.title(), t.description(), t.kind(),
                t.mcpsExtra(), t.analysisMarkdown(), t.subtasksJson(), t.headBranch(), t.headSha(), t.envVars(),
                t.model(), t.effort(), "# 승인 디자인", null, null, null, null, null, t.gitUrl(), t.repoHost(),
                t.planMarkdown(), null, null, null, null, null, null, t.implementation(), t.refRepos(), null,
                t.interviewMockups(), t.excludedMockupNumbers());
    }

    @Test
    void sdd_section_carries_the_mockup_line_only_when_the_plan_file_has_mockups() {
        WorkerTaskResponse t = task(PLAN).withInterviewMockups(MOCKUPS, List.of());
        String base = ImplementationPrompts.planSection(t, SDD, "abc1234", false, false);

        assertThat(ImplementationPrompts.planSection(t, SDD, "abc1234", false, false, true))
                .isEqualTo(base + InterviewMockupPrompts.SDD_MOCKUP_LINE);
        // 참조 줄 다음에 목업 줄
        assertThat(ImplementationPrompts.planSection(t, SDD, "abc1234", false, true, true))
                .isEqualTo(base + ImplementationPrompts.SDD_REF_LINE + InterviewMockupPrompts.SDD_MOCKUP_LINE);
        // 5인자판은 목업 줄 없음(false)과 같다
        assertThat(ImplementationPrompts.planSection(t, SDD, "abc1234", false, true))
                .isEqualTo(ImplementationPrompts.planSection(t, SDD, "abc1234", false, true, false))
                .doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE);
        // SDD 리뷰어에는 리뷰어 한 줄을 넣지 않는다 — 계획 파일 머리와 SDD 줄로 전한다(계획 "설계와 다름 ②")
        assertThat(ImplementationPrompts.planSection(t, SDD, "abc1234", false, false, true))
                .doesNotContain("interview mockups");
    }

    @Test
    void review_reviewer_checks_the_visible_result_against_the_mockups() {
        WorkerTaskResponse t = task(PLAN).withInterviewMockups(MOCKUPS, List.of());

        String s = ImplementationPrompts.planSection(t, REVIEW, "abc1234", false, false, true);
        assertThat(s).contains("The approved plan is in `.netis/plan/task-13-plan.md`. Read it in full."
                        + InterviewMockupPrompts.reviewerRequirement(MOCKUPS, false) + "\n")
                .contains("matches the interview mockups listed under \"인터뷰 목업\"")
                .doesNotContain("the design wins")
                .doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE);
        // 플래그와 무관하다 — 리뷰는 claim의 목업으로 정한다
        assertThat(ImplementationPrompts.planSection(t, REVIEW, "abc1234", false, false, false)).isEqualTo(s);

        // 승인된 디자인이 있으면 디자인이 이긴다
        assertThat(ImplementationPrompts.planSection(withDesign(t), REVIEW, "abc1234", false, false, true))
                .contains(InterviewMockupPrompts.reviewerRequirement(MOCKUPS, true))
                .contains("Where they differ from the approved design in `.design/`, the design wins.");
    }

    /** 넘긴 목업이 없으면(제외 번호만 있어도) 리뷰·SDD·직접 절은 지금과 같다. */
    @Test
    void without_mockups_every_plan_section_is_unchanged() {
        WorkerTaskResponse excludedOnly = task(PLAN).withInterviewMockups(List.of(), List.of(2));
        for (var mode : List.of(DIRECT, REVIEW, SDD)) {
            assertThat(ImplementationPrompts.planSection(excludedOnly, mode, "abc1234", true, false, false))
                    .isEqualTo(ImplementationPrompts.planSection(task(PLAN), mode, "abc1234", true))
                    .doesNotContain("interview mockups")
                    .doesNotContain(InterviewMockupPrompts.SDD_MOCKUP_LINE);
        }
        // 계획 파일이 없는 리뷰도 리뷰어 요구가 없다 — 목업 목록이 계획 파일 머리에만 있기 때문
        assertThat(ImplementationPrompts.planSection(task(null).withInterviewMockups(MOCKUPS, List.of()), REVIEW,
                "abc1234", false, false, true))
                .isEqualTo(ImplementationPrompts.planSection(task(null), REVIEW, "abc1234", false));
        // 직접 방식은 목업이 있어도 계획 절이 같다 — 목업 절은 템플릿 밖에 붙는다
        assertThat(ImplementationPrompts.planSection(task(PLAN).withInterviewMockups(MOCKUPS, List.of()), DIRECT,
                "abc1234", false, false, true))
                .isEqualTo(ImplementationPrompts.planSection(task(PLAN), DIRECT, "abc1234", false));
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

    /** 실패 사유는 화면에 평문으로 보인다 — PR 본문 줄과 같은 내용을 마크다운 기호 없이. */
    @Test
    void the_plain_text_mode_note_says_the_same_as_the_mode_line_without_markdown() {
        assertThat(ImplementationPrompts.modeNote(REVIEW, REVIEW, 1, null)).isEqualTo("구현 방식: 리뷰");
        assertThat(ImplementationPrompts.modeNote(SDD, SDD, 2, null)).isEqualTo("구현 방식: SDD (2회째)");
        assertThat(ImplementationPrompts.modeNote(SDD, REVIEW, 1, ImplementationModeResolver.NO_PLUGIN))
                .isEqualTo("구현 방식: 리뷰 (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)");
        assertThat(ImplementationPrompts.modeNote(SDD, DIRECT, 3, ImplementationModeResolver.PLAN_WRITE_FAILED))
                .isEqualTo("구현 방식: 직접 (3회째) (SDD 요청 — 계획 파일 쓰기 실패로 직접으로 진행)");
        // PR 본문의 마크다운 줄은 같은 내용에 목록·굵게 표시만 더한 것
        String note = ImplementationPrompts.modeNote(SDD, REVIEW, 2, ImplementationModeResolver.NO_EXCLUDE);
        assertThat(ImplementationPrompts.modeLine(SDD, REVIEW, 2, ImplementationModeResolver.NO_EXCLUDE))
                .isEqualTo("- **구현 방식**:" + note.substring("구현 방식:".length()) + "\n");
    }

    @Test
    void with_ro_follows_the_final_consonant() {
        assertThat(ImplementationPrompts.withRo("리뷰")).isEqualTo("리뷰로");
        assertThat(ImplementationPrompts.withRo("직접")).isEqualTo("직접으로");
        assertThat(ImplementationPrompts.withRo("서울")).isEqualTo("서울로");
        assertThat(ImplementationPrompts.withRo("SDD")).isEqualTo("SDD로");
    }
}
