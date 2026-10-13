package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusCloseTest {

    @Test
    void 종료_상태_5개가_한글_dbValue와_왕복된다() {
        assertThat(TaskStatus.CLOSE_REVIEW.dbValue()).isEqualTo("종료승인대기");
        assertThat(TaskStatus.CLOSE_PENDING.dbValue()).isEqualTo("종료대기");
        assertThat(TaskStatus.CLOSING.dbValue()).isEqualTo("종료중");
        assertThat(TaskStatus.CLOSED.dbValue()).isEqualTo("작업종료");
        assertThat(TaskStatus.CLOSE_FAILED.dbValue()).isEqualTo("종료실패");
        assertThat(TaskStatus.fromDb("종료승인대기")).isEqualTo(TaskStatus.CLOSE_REVIEW);
        // DB 컬럼은 VARCHAR(30)
        for (TaskStatus s : TaskStatus.values()) assertThat(s.dbValue().length()).isLessThanOrEqualTo(30);
    }
}
