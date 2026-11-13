package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 워커 능력 — dbWrite는 큐를 거르지 않고(스펙 2026-11-03 §6.1 조건 6은 grant 판정이다), mockups·baseSync는 거른다(§7, 2026-11-13). */
class WorkerCapabilitiesTest {

    @Test
    void dbWrite는_큐를_거르지_않는다() {
        assertThat(new WorkerCapabilities(true, true, false, true, true).needsFilteredQueue()).isFalse();
        assertThat(new WorkerCapabilities(true, true, true, true, true).needsFilteredQueue()).isFalse();
        assertThat(new WorkerCapabilities(false, true, true, true, true).needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, false, true, true, true).needsFilteredQueue()).isTrue();
    }

    @Test
    void mockups를_모르면_큐를_건다() {
        assertThat(new WorkerCapabilities(true, true, true, false, true).needsFilteredQueue()).isTrue();
        // 3인자·2인자 호환 생성자는 mockups=false라 거르는 큐다
        assertThat(new WorkerCapabilities(true, true, true).needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, true, false).needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, true).needsFilteredQueue()).isTrue();
    }

    @Test
    void baseSync를_모르면_큐를_건다() {
        // 4인자 호환 생성자는 baseSync=false — 나머지를 다 알아도 거르는 큐다
        WorkerCapabilities fourArgs = new WorkerCapabilities(true, true, true, true);
        assertThat(fourArgs.baseSync()).isFalse();
        assertThat(fourArgs.needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, true, true, true, false).needsFilteredQueue()).isTrue();
        assertThat(new WorkerCapabilities(true, true, true, true, true).needsFilteredQueue()).isFalse();
    }

    @Test
    void 호환_생성자는_뒤_칸을_false로_ALL은_다섯_다_true() {
        assertThat(new WorkerCapabilities(true, true)).isEqualTo(new WorkerCapabilities(true, true, false, false, false));
        assertThat(new WorkerCapabilities(true, true, true)).isEqualTo(new WorkerCapabilities(true, true, true, false, false));
        assertThat(new WorkerCapabilities(true, true, true, true)).isEqualTo(new WorkerCapabilities(true, true, true, true, false));
        assertThat(WorkerCapabilities.ALL).isEqualTo(new WorkerCapabilities(true, true, true, true, true));
        assertThat(WorkerCapabilities.ALL.needsFilteredQueue()).isFalse();
    }
}
