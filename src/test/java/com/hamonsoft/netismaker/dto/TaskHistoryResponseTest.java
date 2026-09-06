package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskHistoryResponseTest {

    @Test
    void of_maps_db_labels_to_enum_names_and_keeps_labels() {
        TaskStatusHistory h = TaskStatusHistory.log(18L, TaskStatus.AWAITING_APPROVAL, TaskStatus.INTERVIEWING,
                "user", "admin", "관리자 승인 → 인터뷰 시작");
        TaskHistoryResponse r = TaskHistoryResponse.of(h);
        assertThat(r.fromStatus()).isEqualTo("AWAITING_APPROVAL");
        assertThat(r.toStatus()).isEqualTo("INTERVIEWING");
        assertThat(r.fromLabel()).isEqualTo("승인대기");
        assertThat(r.toLabel()).isEqualTo("인터뷰중");
        assertThat(r.actorType()).isEqualTo("user");
        assertThat(r.actorId()).isEqualTo("admin");
        assertThat(r.reason()).isEqualTo("관리자 승인 → 인터뷰 시작");
        assertThat(r.at()).isNotNull();
    }

    @Test
    void of_first_row_has_null_from() {
        TaskStatusHistory h = TaskStatusHistory.log(18L, null, TaskStatus.AWAITING_APPROVAL, "user", "u1", "작업 등록");
        TaskHistoryResponse r = TaskHistoryResponse.of(h);
        assertThat(r.fromStatus()).isNull();
        assertThat(r.fromLabel()).isNull();
        assertThat(r.toStatus()).isEqualTo("AWAITING_APPROVAL");
    }
}
