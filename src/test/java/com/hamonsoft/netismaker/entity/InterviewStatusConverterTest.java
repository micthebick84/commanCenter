package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewStatusConverterTest {

    private final InterviewStatusConverter converter = new InterviewStatusConverter();

    @Test
    void every_status_round_trips_through_korean_db_value() {
        for (InterviewStatus s : InterviewStatus.values()) {
            String db = converter.convertToDatabaseColumn(s);
            assertThat(db).isEqualTo(s.dbValue());
            assertThat(converter.convertToEntityAttribute(db)).isEqualTo(s);
        }
    }

    @Test
    void db_values_are_the_expected_korean_labels() {
        assertThat(InterviewStatus.QUEUED.dbValue()).isEqualTo("인터뷰대기");
        assertThat(InterviewStatus.RUNNING.dbValue()).isEqualTo("인터뷰중");
        assertThat(InterviewStatus.AWAITING_INPUT.dbValue()).isEqualTo("입력대기");
        assertThat(InterviewStatus.PLAN_READY.dbValue()).isEqualTo("플랜완료");
        assertThat(InterviewStatus.REGISTERED.dbValue()).isEqualTo("등록됨");
        assertThat(InterviewStatus.CANCELLED.dbValue()).isEqualTo("취소됨");
        assertThat(InterviewStatus.EXPIRED.dbValue()).isEqualTo("만료됨");
        assertThat(InterviewStatus.FAILED.dbValue()).isEqualTo("인터뷰실패");
    }

    @Test
    void null_passes_through_both_directions() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void unknown_db_value_throws() {
        assertThatThrownBy(() -> InterviewStatus.fromDb("없는상태"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown interview status");
    }
}
