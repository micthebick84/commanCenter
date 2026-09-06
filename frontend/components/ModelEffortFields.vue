<script setup lang="ts">
// 승인 다이얼로그(2026-09-06 UI/UX 개선)의 인터뷰 설정 필드: 모델은 이름 + 한 줄 설명이 붙은 라디오 행,
// 추론 단계(effort)는 질문 탭 ModelEffortPicker와 같은 세그먼트 토글 + 선택값 설명 한 줄.
// 모델 변경 시 coerceEffort로 강등 — 기존 등록/질문 다이얼로그의 watch(draft.model) 규칙과 동일.
import {
  MODEL_OPTIONS,
  effortsForModel,
  coerceEffort,
  describeModel,
  describeEffort,
} from '~/composables/modelEffort'

const props = withDefaults(
  defineProps<{
    /** 승인 요청 전송 중 등 부모가 함께 잠근다. */
    disabled?: boolean
  }>(),
  { disabled: false },
)
const model = defineModel<string>('model', { required: true })
const effort = defineModel<string>('effort', { required: true })

const efforts = computed(() => effortsForModel(model.value))
const effortOptions = computed(() =>
  efforts.value.map((e) => ({ label: e, value: e })),
)
const effortDesc = computed(() => describeEffort(effort.value))
const haikuHint = computed(() =>
  model.value === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : '',
)

// "Opus 5 (기본)" → "Opus 5": 기본값 표기는 설명 줄이 맡는다
function shortLabel(label: string): string {
  return label.replace(/\s*\(.*\)$/, '')
}

function pickModel(value: string) {
  if (props.disabled) return
  model.value = value
  effort.value = coerceEffort(value, effort.value)
}
// 테스트에서 행 클릭 대신 직접 호출할 수 있게 (ModelEffortPicker.pickModel 선례)
defineExpose({ pickModel })
</script>

<template>
  <div class="model-effort-fields">
    <div class="field-label">모델</div>
    <div role="radiogroup" aria-label="Claude 모델" class="model-rows">
      <div
        v-for="m in MODEL_OPTIONS"
        :key="m.value"
        role="radio"
        :aria-checked="m.value === model ? 'true' : 'false'"
        :aria-disabled="props.disabled ? 'true' : undefined"
        :tabindex="props.disabled ? -1 : 0"
        class="model-row"
        :class="{
          'model-row--active': m.value === model,
          'model-row--disabled': props.disabled,
        }"
        :data-test="`model-row-${m.value}`"
        @click="pickModel(m.value)"
        @keydown.enter.prevent="pickModel(m.value)"
        @keydown.space.prevent="pickModel(m.value)"
      >
        <q-icon
          :name="
            m.value === model
              ? 'radio_button_checked'
              : 'radio_button_unchecked'
          "
          :color="m.value === model ? 'primary' : 'grey-6'"
          size="20px"
        />
        <div class="model-text">
          <div class="model-name">{{ shortLabel(m.label) }}</div>
          <div class="model-desc">{{ describeModel(m.value) }}</div>
        </div>
      </div>
    </div>

    <div class="field-label q-mt-md">추론 단계</div>
    <q-btn-toggle
      v-model="effort"
      :options="effortOptions"
      outline
      dense
      no-caps
      spread
      toggle-color="primary"
      :disable="props.disabled"
      data-test="effort-toggle"
      class="effort-toggle"
    />
    <div class="text-caption text-grey-7 q-mt-xs" data-test="effort-desc">
      {{ effortDesc }}<span v-if="haikuHint"> · {{ haikuHint }}</span>
    </div>
  </div>
</template>

<style scoped>
.field-label {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #616161;
  margin-bottom: 6px;
}
.model-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.model-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 6px;
  cursor: pointer;
  min-height: 44px;
  outline: none;
}
.model-row:focus-visible {
  box-shadow: 0 0 0 2px rgba(25, 118, 210, 0.35);
}
.model-row--active {
  border-color: #1976d2;
  background: #f5f9fd;
}
.model-row--disabled {
  cursor: default;
  opacity: 0.6;
}
.model-text {
  min-width: 0;
}
.model-name {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.3;
  color: #212121;
}
.model-desc {
  font-size: 12px;
  line-height: 1.35;
  color: #757575;
}
.effort-toggle {
  width: 100%;
}
</style>
