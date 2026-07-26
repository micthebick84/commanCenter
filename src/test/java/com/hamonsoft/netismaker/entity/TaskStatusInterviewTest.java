package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusInterviewTest {

    @Test
    void interview_statuses_have_korean_db_values() {
        assertThat(TaskStatus.AWAITING_APPROVAL.dbValue()).isEqualTo("승인대기");
        assertThat(TaskStatus.INTERVIEWING.dbValue()).isEqualTo("인터뷰중");
        assertThat(TaskStatus.INTERVIEW_INPUT.dbValue()).isEqualTo("입력대기");
        assertThat(TaskStatus.INTERVIEW_REVIEW.dbValue()).isEqualTo("플랜승인대기");
    }

    @Test
    void interview_statuses_round_trip_from_db() {
        assertThat(TaskStatus.fromDb("승인대기")).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(TaskStatus.fromDb("인터뷰중")).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(TaskStatus.fromDb("입력대기")).isEqualTo(TaskStatus.INTERVIEW_INPUT);
        assertThat(TaskStatus.fromDb("플랜승인대기")).isEqualTo(TaskStatus.INTERVIEW_REVIEW);
    }

    @Test
    void db_values_stay_unique_and_fit_varchar30() {
        long distinct = Arrays.stream(TaskStatus.values()).map(TaskStatus::dbValue).distinct().count();
        assertThat(distinct).isEqualTo(TaskStatus.values().length);
        assertThat(Arrays.stream(TaskStatus.values()).allMatch(s -> s.dbValue().length() <= 30)).isTrue();
    }
}
