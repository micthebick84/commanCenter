package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.junit.jupiter.api.Test;

import static com.hamonsoft.netismaker.entity.ImplementationMode.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 준비가 안 되면 한 단계 낮은 방식으로 (스펙 2026-10-15 §6.5). */
class ImplementationModeResolverTest {

    private static final ImplementationModeResolver.Inputs READY =
            new ImplementationModeResolver.Inputs(true, true, true, true, 3);

    private static ImplementationModeResolver.Resolution resolve(ImplementationMode m, boolean plugin,
                                                                 boolean exclude, boolean plan,
                                                                 boolean written, int headings) {
        return ImplementationModeResolver.resolve(m,
                new ImplementationModeResolver.Inputs(plugin, exclude, plan, written, headings));
    }

    @Test
    void ready_modes_run_as_requested() {
        assertThat(ImplementationModeResolver.resolve(DIRECT, READY)).isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, null));
        assertThat(ImplementationModeResolver.resolve(REVIEW, READY)).isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, null));
        assertThat(ImplementationModeResolver.resolve(SDD, READY)).isEqualTo(new ImplementationModeResolver.Resolution(SDD, null));
        assertThat(ImplementationModeResolver.resolve(null, READY).effective()).isEqualTo(DIRECT);
        assertThat(ImplementationModeResolver.resolve(SDD, READY).fellBack()).isFalse();
    }

    @Test
    void sdd_falls_back_to_review_with_the_first_missing_piece() {
        assertThat(resolve(SDD, false, true, true, true, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_PLUGIN));
        assertThat(resolve(SDD, true, false, true, true, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_EXCLUDE));
        assertThat(resolve(SDD, true, true, false, false, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_PLAN));
        assertThat(resolve(SDD, true, true, true, true, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_TASK_HEADINGS));
    }

    @Test
    void an_unwritable_plan_file_drops_review_and_sdd_to_direct() {
        assertThat(resolve(SDD, true, true, true, false, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, ImplementationModeResolver.PLAN_WRITE_FAILED));
        assertThat(resolve(REVIEW, true, true, true, false, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, ImplementationModeResolver.PLAN_WRITE_FAILED));
    }

    @Test
    void review_without_a_plan_still_reviews_against_the_requirements() {
        assertThat(resolve(REVIEW, false, false, false, false, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, null));
    }
}
