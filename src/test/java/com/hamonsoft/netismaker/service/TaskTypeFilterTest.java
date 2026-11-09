package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 작업 목록 type 필터 파라미터 해석 (스펙 2026-11-03 §4.3 — "타입 없음"의 와이어 값은 NONE). */
class TaskTypeFilterTest {

    @Test
    void 필터_파싱() {
        assertThat(TaskTypeFilter.parse("NONE")).isEqualTo(new TaskTypeFilter(false, null));
        assertThat(TaskTypeFilter.parse(null)).isEqualTo(TaskTypeFilter.ANY);
        assertThatThrownBy(() -> TaskTypeFilter.parse("bug")).hasMessageContaining("알 수 없는 작업 타입 필터: bug");
    }

    @Test
    void 파라미터가_없거나_공백이면_전체() {
        assertThat(TaskTypeFilter.ANY).isEqualTo(new TaskTypeFilter(true, null));
        assertThat(TaskTypeFilter.parse("")).isEqualTo(TaskTypeFilter.ANY);
        assertThat(TaskTypeFilter.parse("   ")).isEqualTo(TaskTypeFilter.ANY);
    }

    @Test
    void enum_이름은_그_타입만_고른다() {
        for (TaskType type : TaskType.values()) {
            assertThat(TaskTypeFilter.parse(type.name())).isEqualTo(new TaskTypeFilter(false, type));
        }
    }

    @Test
    void 앞뒤_공백은_자르고_대소문자는_구분한다() {
        assertThat(TaskTypeFilter.parse(" BUG_FIX ")).isEqualTo(new TaskTypeFilter(false, TaskType.BUG_FIX));
        assertThat(TaskTypeFilter.parse(" NONE ")).isEqualTo(new TaskTypeFilter(false, null));

        assertThatThrownBy(() -> TaskTypeFilter.parse("bug_fix"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("알 수 없는 작업 타입 필터: bug_fix");
                });
        assertThatThrownBy(() -> TaskTypeFilter.parse("none"))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
