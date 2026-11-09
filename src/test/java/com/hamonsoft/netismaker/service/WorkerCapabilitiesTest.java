package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 워커 능력 — dbWrite는 큐를 거르지 않는다(스펙 2026-11-03 §6.1 조건 6은 grant 판정이다). */
class WorkerCapabilitiesTest {

    @Test
    void dbWrite는_큐를_거르지_않는다() {
        assertThat(new WorkerCapabilities(true, true, false).needsFilteredQueue()).isFalse();
        assertThat(new WorkerCapabilities(true, true, true).needsFilteredQueue()).isFalse();
        assertThat(new WorkerCapabilities(false, true, true).needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, false, true).needsFilteredQueue()).isTrue();
    }

    @Test
    void 인자_둘은_dbWrite_false_ALL은_셋_다_true() {
        assertThat(new WorkerCapabilities(true, true)).isEqualTo(new WorkerCapabilities(true, true, false));
        assertThat(WorkerCapabilities.ALL).isEqualTo(new WorkerCapabilities(true, true, true));
    }
}
