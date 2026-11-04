package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusFixReviewTest {

    @Test
    void 수정승인대기_DB값_왕복() {
        assertThat(TaskStatus.FIX_REVIEW.dbValue()).isEqualTo("수정승인대기");
        assertThat(TaskStatus.fromDb("수정승인대기")).isEqualTo(TaskStatus.FIX_REVIEW);
        // DB 컬럼은 VARCHAR(30)이고 dbValue는 서로 겹치면 안 된다
        assertThat(TaskStatus.FIX_REVIEW.dbValue().length()).isLessThanOrEqualTo(30);
        long distinct = Arrays.stream(TaskStatus.values()).map(TaskStatus::dbValue).distinct().count();
        assertThat(distinct).isEqualTo(TaskStatus.values().length);
    }

    @Test
    void 수정_요청은_승인대기로_만들어지고_첫_시도_번호를_가진다() {
        TaskFixRequest r = TaskFixRequest.pending(7L, "로그인 버튼이 안 눌려요", TaskStatus.DEPLOYED, "user1");

        assertThat(r.getTaskId()).isEqualTo(7L);
        assertThat(r.getBody()).isEqualTo("로그인 버튼이 안 눌려요");
        assertThat(r.getFromStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(r.getRequestedBy()).isEqualTo("user1");
        assertThat(r.getStatus()).isEqualTo(FixRequestStatus.PENDING);
        assertThat(r.getRequestedAt()).isNotNull();
        assertThat(r.getAttempt()).isEqualTo(1);
        // 회차·구현 방식·결정·결과는 승인·종료 때 채운다
        assertThat(r.getRound()).isNull();
        assertThat(r.getImplementationMode()).isNull();
        assertThat(r.getDecidedBy()).isNull();
        assertThat(r.getDecidedAt()).isNull();
        assertThat(r.getDecisionReason()).isNull();
        assertThat(r.getOutcome()).isNull();
        assertThat(r.getClosedAt()).isNull();
    }

    @Test
    void 작업은_진행_중_수정_요청_id를_들고_다닌다() {
        Task t = Task.create("owner/repo", "main", "제목", "설명", "user1", 3, java.util.List.of(), null, null);
        assertThat(t.getActiveFixRequestId()).isNull();

        t.setActiveFixRequestId(5L);
        assertThat(t.getActiveFixRequestId()).isEqualTo(5L);

        t.setActiveFixRequestId(null);
        assertThat(t.getActiveFixRequestId()).isNull();
    }

    @Test
    void 수정_요청_상태와_결과_이름() {
        assertThat(FixRequestStatus.values()).containsExactly(
                FixRequestStatus.PENDING, FixRequestStatus.APPROVED,
                FixRequestStatus.REJECTED, FixRequestStatus.WITHDRAWN);
        assertThat(FixOutcome.values()).containsExactly(
                FixOutcome.DEPLOYED, FixOutcome.DEPLOY_FAILED, FixOutcome.ABORTED);
    }
}
