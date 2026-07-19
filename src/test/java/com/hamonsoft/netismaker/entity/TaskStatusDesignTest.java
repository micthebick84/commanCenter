package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusDesignTest {

    @Test
    void 디자인_상태_4개가_한글_dbValue와_왕복된다() {
        assertThat(TaskStatus.DESIGN_PENDING.dbValue()).isEqualTo("디자인대기");
        assertThat(TaskStatus.DESIGNING.dbValue()).isEqualTo("디자인중");
        assertThat(TaskStatus.DESIGN_REVIEW.dbValue()).isEqualTo("디자인승인대기");
        assertThat(TaskStatus.DESIGN_FAILED.dbValue()).isEqualTo("디자인실패");
        assertThat(TaskStatus.fromDb("디자인승인대기")).isEqualTo(TaskStatus.DESIGN_REVIEW);
    }
}
