package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelEffortPolicyTest {

    @Test
    void resolve_blank_returns_defaults() {
        assertThat(ModelEffortPolicy.resolveModel(null)).isEqualTo("claude-opus-4-8");
        assertThat(ModelEffortPolicy.resolveModel("  ")).isEqualTo("claude-opus-4-8");
        assertThat(ModelEffortPolicy.resolveEffort(null)).isEqualTo("high");
        assertThat(ModelEffortPolicy.resolveEffort("")).isEqualTo("high");
    }

    @Test
    void resolve_keeps_given_values() {
        assertThat(ModelEffortPolicy.resolveModel("claude-haiku-4-5")).isEqualTo("claude-haiku-4-5");
        assertThat(ModelEffortPolicy.resolveEffort("low")).isEqualTo("low");
    }

    @Test
    void validate_accepts_opus_and_sonnet_with_max() {
        ModelEffortPolicy.validate("claude-opus-4-8", "max");
        ModelEffortPolicy.validate("claude-opus-4-7", "xhigh");
        ModelEffortPolicy.validate("claude-sonnet-4-6", "max");
    }

    @Test
    void validate_accepts_haiku_with_high() {
        ModelEffortPolicy.validate("claude-haiku-4-5", "high");
        ModelEffortPolicy.validate("claude-haiku-4-5", "low");
    }

    @Test
    void validate_rejects_unknown_model() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("gpt-5", "high"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("모델");
    }

    @Test
    void validate_rejects_haiku_with_max_or_xhigh() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-haiku-4-5", "max"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-haiku-4-5", "xhigh"))
                .isInstanceOf(TaskException.class);
    }
}
