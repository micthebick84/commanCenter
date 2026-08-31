package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseUsageTest {

    private Task task() {
        return Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                List.of(), null, null);
    }

    @Test
    void 기본_팩토리는_빈_stageUsage와_null_총합을_반환한다() {
        TaskResponse r = TaskResponse.of(task(), null);
        assertThat(r.stageUsage()).isEmpty();
        assertThat(r.totalCostUsd()).isNull();
        assertThat(r.totalTokens()).isNull();
    }

    @Test
    void withTotalCost는_목록_총합만_채운다() {
        TaskResponse r = TaskResponse.withTotalCost(
                TaskResponse.of(task(), null), new BigDecimal("1.23"));
        assertThat(r.totalCostUsd()).isEqualByComparingTo("1.23");
        assertThat(r.stageUsage()).isEmpty();
    }
}
