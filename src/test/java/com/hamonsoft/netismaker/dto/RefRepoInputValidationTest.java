package com.hamonsoft.netismaker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 참조 repo 입력의 빈 검증 (스펙 2026-10-22 §4.1, 보충 Ruling 4) — 브랜치 상한은 대상 브랜치(githubBranch)와 같은 255자다.
 * 등록·대화 중 변경 요청 모두 refRepos 목록에 @Valid가 걸려 있어야 원소의 @Size가 실제로 적용된다.
 */
class RefRepoInputValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() { factory = Validation.buildDefaultValidatorFactory(); validator = factory.getValidator(); }
    @AfterAll static void close() { factory.close(); }

    private static QuestionCreateRequest create(String branch) {
        return new QuestionCreateRequest(1L, "main", "t", "q", null, null, null, null,
                List.of(new RefRepoInput(11L, branch)));
    }

    private static QuestionAskRequest ask(String branch) {
        return new QuestionAskRequest("추가", 3, null, null, null, null, List.of(new RefRepoInput(11L, branch)));
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void a_256_char_branch_is_rejected_in_both_the_create_and_the_ask_request() {
        String tooLong = "b".repeat(256);

        assertThat(paths(validator.validate(create(tooLong)))).containsExactly("refRepos[0].branch");
        assertThat(paths(validator.validate(ask(tooLong)))).containsExactly("refRepos[0].branch");
        assertThat(validator.validate(new RefRepoInput(11L, tooLong))).isNotEmpty();
    }

    // ── 작업 등록·승인 (스펙 2026-10-24 §4.1·§4.2) — 같은 255자 상한, 목록에 @Valid ──

    private static TaskCreateRequest taskCreate(String branch) {
        return new TaskCreateRequest(1L, "main", "t", "d", List.of(new RefRepoInput(11L, branch)), null);
    }

    private static ApproveRequest approve(String branch) {
        return new ApproveRequest(null, null, null, null, List.of(new RefRepoInput(11L, branch)));
    }

    @Test
    void a_256_char_branch_is_rejected_in_the_task_create_and_the_approve_request() {
        String tooLong = "b".repeat(256);

        assertThat(paths(validator.validate(taskCreate(tooLong)))).containsExactly("refRepos[0].branch");
        assertThat(paths(validator.validate(approve(tooLong)))).containsExactly("refRepos[0].branch");
    }

    @Test
    void task_requests_with_a_255_char_branch_or_without_references_stay_valid() {
        assertThat(validator.validate(taskCreate("b".repeat(255)))).isEmpty();
        assertThat(validator.validate(approve("b".repeat(255)))).isEmpty();
        assertThat(validator.validate(new TaskCreateRequest(1L, "main", "t", "d"))).isEmpty();
        assertThat(validator.validate(new ApproveRequest(null, null, null, null))).isEmpty();
        assertThat(validator.validate(new ApproveRequest(null, null, null))).isEmpty();
    }

    // ── 작업 인터뷰 답변 (스펙 2026-10-24 §4.3) — 같은 255자 상한, 목록에 @Valid ──

    private static AnswerRequest answer(String branch) {
        return new AnswerRequest("답", 3, List.of(new RefRepoInput(11L, branch)));
    }

    @Test
    void a_256_char_branch_is_rejected_in_the_interview_answer() {
        assertThat(paths(validator.validate(answer("b".repeat(256))))).containsExactly("refRepos[0].branch");
    }

    @Test
    void interview_answers_with_a_255_char_branch_or_without_references_stay_valid() {
        assertThat(validator.validate(answer("b".repeat(255)))).isEmpty();
        assertThat(validator.validate(new AnswerRequest("답", 3))).isEmpty();
        assertThat(validator.validate(new AnswerRequest("답", 3, List.of()))).isEmpty();
        assertThat(paths(validator.validate(new AnswerRequest(" ", 3)))).containsExactly("answer");
    }

    @Test
    void a_255_char_blank_or_missing_branch_passes_bean_validation() {
        // 빈 브랜치는 서비스가 카탈로그 기본 브랜치로 채우거나 400("참조 브랜치를 지정하세요")으로 거절한다 — 빈 검증의 일이 아니다
        for (String branch : new String[] {"b".repeat(255), "", "  ", null, "release/6.5"}) {
            assertThat(validator.validate(create(branch))).as("create %s", branch).isEmpty();
            assertThat(validator.validate(ask(branch))).as("ask %s", branch).isEmpty();
        }
    }

    @Test
    void requests_without_a_reference_list_stay_valid() {
        assertThat(validator.validate(new QuestionCreateRequest(1L, "main", "t", "q", null, null, null))).isEmpty();
        assertThat(validator.validate(new QuestionAskRequest("추가", 3, null, null))).isEmpty();
        assertThat(validator.validate(new QuestionCreateRequest(1L, "main", "t", "q", null, null, null, null, List.of()))).isEmpty();
    }
}
