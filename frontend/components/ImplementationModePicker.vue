<script setup lang="ts">
// 구현 방식 고르기 (스펙 2026-10-15 §7) — 인터뷰 패널 "구현 진행"과 구현 재시도 다이얼로그가 같이 쓴다.
// modes: 고를 수 있는 방식과 그 순서(없으면 직접·리뷰·SDD 전부). 수정 회차(스펙 2026-11-03 §6.2)는 SDD를 뺀 리뷰·직접만 준다.
import { IMPLEMENTATION_MODES, implementationModeDescription } from '~/composables/implementationMode'

// modes 타입은 `~/`에서 가져오지 않고 인라인 — .nuxt 없는 vitest에서 SFC 매크로가 못 푼다(CLAUDE.md)
const props = defineProps<{ modelValue: 'DIRECT' | 'REVIEW' | 'SDD'; modes?: Array<'DIRECT' | 'REVIEW' | 'SDD'> }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: 'DIRECT' | 'REVIEW' | 'SDD'): void }>()

const options = computed(() =>
  (props.modes
    ? props.modes.flatMap((v) => IMPLEMENTATION_MODES.filter((m) => m.value === v))
    : IMPLEMENTATION_MODES
  ).map((m) => ({
    label: m.label,
    value: m.value,
    attrs: { 'data-test': `impl-mode-${m.value}` },
  })),
)
</script>

<template>
  <div class="impl-mode-picker" data-test="impl-mode-picker">
    <div class="row items-center no-wrap" style="gap: 8px">
      <span class="text-caption text-grey-8">구현 방식</span>
      <q-btn-toggle
        :model-value="props.modelValue"
        :options="options"
        dense
        no-caps
        unelevated
        toggle-color="primary"
        color="grey-3"
        text-color="grey-9"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </div>
    <div class="text-caption text-grey-7 q-mt-xs" data-test="impl-mode-description">
      {{ implementationModeDescription(props.modelValue) }}
    </div>
  </div>
</template>
