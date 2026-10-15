package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 방식 enum (스펙 2026-10-15 §4). */
class ImplementationModeTest {

    @Test
    void labels_are_the_screen_names() {
        assertThat(ImplementationMode.DIRECT.label()).isEqualTo("직접");
        assertThat(ImplementationMode.REVIEW.label()).isEqualTo("리뷰");
        assertThat(ImplementationMode.SDD.label()).isEqualTo("SDD");
    }

    @Test
    void wire_values_from_an_older_or_newer_api_fall_back_to_direct() {
        assertThat(ImplementationMode.fromWireOrDirect(null)).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("  ")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("TURBO")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("review")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("SDD")).isEqualTo(ImplementationMode.SDD);
        assertThat(ImplementationMode.fromWireOrDirect(" REVIEW ")).isEqualTo(ImplementationMode.REVIEW);
    }

    @Test
    void a_new_task_starts_direct_first_attempt_without_a_recorded_timeout() {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), null, null);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
    }
}
