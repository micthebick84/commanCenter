package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseCloseTest {

    private Task task(TaskStatus s) {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(s);
        return t;
    }

    @Test
    void 종료와_무관한_작업은_close가_null() {
        assertThat(TaskResponse.of(task(TaskStatus.DEPLOYED), null).close()).isNull();
    }

    @Test
    void 종료_단계면_진행을_enum_순서로_보여준다() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseProgress("UNDEPLOYED,MERGED");
        t.setMergeCommitSha("deadbeef");
        TaskResponse.CloseView v = TaskResponse.of(t, null).close();
        assertThat(v.progress()).containsExactly("MERGED", "UNDEPLOYED");
        assertThat(v.mergeCommitSha()).isEqualTo("deadbeef");
    }

    @Test
    void 종료승인대기는_진행이_없어도_빈_close를_준다() {
        TaskResponse.CloseView v = TaskResponse.of(task(TaskStatus.CLOSE_REVIEW), null).close();
        assertThat(v).isNotNull();
        assertThat(v.progress()).isEmpty();
    }

    @Test
    void 비밀_마스킹과_총비용_재조립이_close를_보존한다() {
        Task t = task(TaskStatus.CLOSED);
        t.setCloseNotePath("HamonSoft/netisMaker/T-작업1-2026-10-13.md");
        TaskResponse r = TaskResponse.of(t, null);
        assertThat(r.redactSecretValues().close()).isEqualTo(r.close());
        assertThat(TaskResponse.withTotalCost(r, java.math.BigDecimal.ONE).close()).isEqualTo(r.close());
    }
}
