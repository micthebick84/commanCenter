package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 방식별 구현 상한·멈춤 판정 설정 (스펙 2026-10-15 §5.4). */
class ImplementationModePolicyTest {

    @Test
    void defaults_match_the_spec_table() {
        ImplementationModePolicy p = ImplementationModePolicy.defaults();
        assertThat(p.timeoutMinutes(ImplementationMode.DIRECT)).isEqualTo(45);
        assertThat(p.timeoutMinutes(ImplementationMode.REVIEW)).isEqualTo(80);
        assertThat(p.timeoutMinutes(ImplementationMode.SDD)).isEqualTo(240);
        assertThat(p.staleThresholdMinutes(ImplementationMode.DIRECT)).isEqualTo(60);
        assertThat(p.staleThresholdMinutes(ImplementationMode.REVIEW)).isEqualTo(95);
        assertThat(p.staleThresholdMinutes(ImplementationMode.SDD)).isEqualTo(260);
    }

    @Test
    void a_null_mode_is_treated_as_direct() {
        ImplementationModePolicy p = ImplementationModePolicy.defaults();
        assertThat(p.timeoutMinutes(null)).isEqualTo(45);
        assertThat(p.staleThresholdMinutes(null)).isEqualTo(60);
    }

    @Test
    void a_timeout_that_is_not_below_its_stale_threshold_refuses_to_boot() {
        assertThatThrownBy(() -> new ImplementationModePolicy(45, 95, 240, 60, 95, 260))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVIEW");
        assertThatThrownBy(() -> new ImplementationModePolicy(0, 80, 240, 60, 95, 260))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIRECT");
    }

    @Test
    void parse_uses_the_fallback_for_blank_and_rejects_unknown_values_with_400() {
        assertThat(ImplementationModePolicy.parse(null, ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationModePolicy.parse(" ", null)).isNull();
        assertThat(ImplementationModePolicy.parse("SDD", ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.SDD);
        assertThat(ImplementationModePolicy.parse(" REVIEW ", ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.REVIEW);
        for (String bad : new String[]{"sdd", "FAST"}) {
            assertThatThrownBy(() -> ImplementationModePolicy.parse(bad, ImplementationMode.DIRECT))
                    .isInstanceOf(TaskException.class)
                    .hasMessageContaining(bad)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
}
