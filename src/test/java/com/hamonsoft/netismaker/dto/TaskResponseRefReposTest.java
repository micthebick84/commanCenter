package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 작업 응답의 참조 repo·출처 질문 (스펙 2026-10-24 §4.9). */
class TaskResponseRefReposTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final RefRepo REF = new RefRepo(11L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
            SHA, "2026-10-24T10:00+09:00", null);

    private Task t;

    @BeforeEach
    void setUp() {
        t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
    }

    @Test
    void task_refs_and_source_question_are_mapped_and_absent_values_are_empty_and_null() {
        assertThat(TaskResponse.of(t, null).refRepos()).isNotNull().isEmpty();
        assertThat(TaskResponse.of(t, null).sourceQuestionId()).isNull();
        t.setRefRepos(null);
        assertThat(TaskResponse.of(t, null).refRepos()).isNotNull().isEmpty();

        t.setRefRepos(new ArrayList<>(List.of(REF)));
        t.setSourceQuestionId(9L);
        TaskResponse r = TaskResponse.of(t, null);

        assertThat(r.refRepos()).containsExactly(
                new InterviewResponse.RefRepoView(11L, "옛앱", "release/6.5", SHA, "2026-10-24T10:00+09:00", null));
        assertThat(r.sourceQuestionId()).isEqualTo(9L);
    }

    @Test
    void withTotalCost_redact_keep_refs_and_withRefRepos_replaces_only_refs() {
        t.setRefRepos(new ArrayList<>(List.of(REF)));
        t.setSourceQuestionId(9L);
        TaskResponse r = TaskResponse.of(t, null);

        TaskResponse costed = TaskResponse.withTotalCost(r, BigDecimal.ONE);
        assertThat(costed.refRepos()).isEqualTo(r.refRepos());
        assertThat(costed.sourceQuestionId()).isEqualTo(9L);
        TaskResponse redacted = r.redactSecretValues();
        assertThat(redacted.refRepos()).isEqualTo(r.refRepos());
        assertThat(redacted.sourceQuestionId()).isEqualTo(9L);

        TaskResponse swapped = r.withRefRepos(List.of(REF.withError("권한 없음")));
        assertThat(swapped.refRepos()).hasSize(1);
        assertThat(swapped.refRepos().get(0).error()).isEqualTo("권한 없음");
        assertThat(swapped).usingRecursiveComparison().ignoringFields("refRepos").isEqualTo(r);
        assertThat(r.refRepos().get(0).error()).isNull();   // 원본 불변

        assertThat(r.withRefRepos(null).refRepos()).isNotNull().isEmpty();
    }
}
