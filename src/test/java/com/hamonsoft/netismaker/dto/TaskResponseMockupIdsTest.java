package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** implementationMockupIds는 build가 채우고 모든 사본이 그대로 실어 나른다 (스펙 2026-11-03 §7). */
class TaskResponseMockupIdsTest {

    private static Task task() {
        return Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-5-5", "high");
    }

    @Test
    void 기본은_빈_목록() {
        assertThat(TaskResponse.of(task(), null).implementationMockupIds()).isEmpty();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void JSONB가_Integer를_담아_와도_Long으로_바꾼다() {
        Task t = task();
        List raw = new ArrayList();
        raw.add(7);          // Hibernate JSON이 작은 수를 Integer로 읽는 경우
        raw.add(8L);
        t.setImplementationMockupIds(raw);

        List<Long> ids = TaskResponse.of(t, null).implementationMockupIds();

        assertThat(ids).containsExactly(7L, 8L);
        assertThat(ids.get(0)).isInstanceOf(Long.class);
    }

    @Test
    void 사본이_implementationMockupIds_유지() {
        Task t = task();
        t.setImplementationMockupIds(new ArrayList<>(List.of(10L, 12L)));
        TaskResponse r = TaskResponse.of(t, null);

        assertThat(TaskResponse.withTotalCost(r, BigDecimal.ONE).implementationMockupIds()).containsExactly(10L, 12L);
        assertThat(r.withRefRepos(List.of()).implementationMockupIds()).containsExactly(10L, 12L);
        assertThat(r.withFix(null).implementationMockupIds()).containsExactly(10L, 12L);
        assertThat(r.redactSecretValues().implementationMockupIds()).containsExactly(10L, 12L);
        assertThat(r.withInterviewSuperpowersEnabled(true).implementationMockupIds()).containsExactly(10L, 12L);
    }
}
