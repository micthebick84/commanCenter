package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelEffortPolicyTest {

    @Test
    void resolve_blank_returns_defaults() {
        assertThat(ModelEffortPolicy.resolveModel(null)).isEqualTo("claude-opus-5");
        assertThat(ModelEffortPolicy.resolveModel("  ")).isEqualTo("claude-opus-5");
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
        ModelEffortPolicy.validate("claude-opus-5", "max");
        ModelEffortPolicy.validate("claude-opus-5", "xhigh");
        ModelEffortPolicy.validate("claude-sonnet-5", "max");
    }

    /** Fable은 2026-09-05 선택 목록에서 뺐다(스펙 2026-09-05 §5.4). 과거 세션의 박제 값은 검증을 타지 않는다. */
    @Test
    void validate_rejects_fable_removed_from_picker() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-fable-5", "high"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("모델");
    }

    /** 구세대 모델은 선택 목록에서 빠졌다 — 새로 고를 수 없다(과거 작업의 박제 값은 검증을 타지 않음). */
    @Test
    void validate_rejects_retired_picker_models() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-opus-4-8", "high"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("모델");
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-sonnet-4-6", "high"))
                .isInstanceOf(TaskException.class);
    }

    /**
     * ultracode는 CLI가 인식하는 --effort 별칭이지만 헤드리스에서 xhigh와 무차이라 목록에서 뺐다.
     * 실측 근거는 ModelEffortPolicy 주석 참고 — 누락으로 오해해 다시 넣지 말 것.
     */
    @Test
    void validate_rejects_ultracode_deliberately_excluded() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-opus-5", "ultracode"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");
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
