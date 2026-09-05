<script setup lang="ts">
// 모델 드롭다운 + 추론 단계(effort) 세그먼트 (스펙 2026-09-05 §3). 모델 변경 시 coerceEffort로 강등 —
// 기존 등록/질문 다이얼로그의 watch(draft.model) 규칙과 동일. 좁은 화면(lt.md)은 effort도 드롭다운.
import { useQuasar } from 'quasar'
import { MODEL_OPTIONS, effortsForModel, coerceEffort } from '~/composables/modelEffort'

const props = withDefaults(
  defineProps<{
    /** 세션 생성 후에는 고정 — 읽기 전용 표시 (스펙 §2 "모델/effort 변경 시점"). */
    disabled?: boolean
  }>(),
  { disabled: false },
)
const model = defineModel<string>('model', { required: true })
const effort = defineModel<string>('effort', { required: true })

const $q = useQuasar()
// "Opus 5 (기본)" → "Opus 5": 툴바 칩에는 짧은 이름만
const modelLabel = computed(() =>
  (MODEL_OPTIONS.find((m) => m.value === model.value)?.label ?? model.value).replace(/\s*\(.*\)$/, ''),
)
const efforts = computed(() => effortsForModel(model.value))
const effortOptions = computed(() => efforts.value.map((e) => ({ label: e, value: e })))
const compact = computed(() => !!$q.screen?.lt?.md)
const haikuHint = computed(() =>
  model.value === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : '',
)

function pickModel(value: string) {
  if (props.disabled) return
  model.value = value
  effort.value = coerceEffort(value, effort.value)
}
// 테스트에서 q-menu(포털) 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ pickModel })
</script>

<template>
  <div class="row items-center no-wrap model-effort-picker">
    <q-btn-dropdown
      flat
      dense
      no-caps
      icon="smart_toy"
      :label="modelLabel"
      :disable="props.disabled"
      data-test="model-picker"
    >
      <q-list>
        <q-item
          v-for="m in MODEL_OPTIONS"
          :key="m.value"
          v-close-popup
          clickable
          :active="m.value === model"
          active-class="text-primary"
          :data-test="`model-option-${m.value}`"
          @click="pickModel(m.value)"
        >
          <q-item-section>
            <q-item-label>{{ m.label }}</q-item-label>
            <q-item-label v-if="m.value === 'claude-haiku-4-5'" caption>low/medium/high만 지원</q-item-label>
          </q-item-section>
          <q-item-section v-if="m.value === model" side>
            <q-icon name="check" color="primary" />
          </q-item-section>
        </q-item>
      </q-list>
      <q-tooltip v-if="props.disabled">세션 생성 시 고정 — 바꾸려면 새 질문</q-tooltip>
    </q-btn-dropdown>

    <div class="row items-center no-wrap q-ml-sm effort-group">
      <q-icon name="tune" size="18px" color="grey-7" />
      <span class="text-caption text-grey-7 q-mx-xs">추론</span>
      <q-btn-dropdown
        v-if="compact"
        flat
        dense
        no-caps
        :label="effort"
        :disable="props.disabled"
        data-test="effort-dropdown"
      >
        <q-list dense>
          <q-item
            v-for="e in efforts"
            :key="e"
            v-close-popup
            clickable
            :data-test="`effort-option-${e}`"
            @click="effort = e"
          >
            <q-item-section>{{ e }}</q-item-section>
          </q-item>
        </q-list>
      </q-btn-dropdown>
      <q-btn-toggle
        v-else
        v-model="effort"
        :options="effortOptions"
        outline
        dense
        no-caps
        toggle-color="primary"
        :disable="props.disabled"
        data-test="effort-toggle"
      />
      <span v-if="haikuHint" class="text-caption text-grey-7 q-ml-sm">{{ haikuHint }}</span>
    </div>
  </div>
</template>

<style scoped>
.model-effort-picker {
  gap: 4px;
}
.effort-group {
  white-space: nowrap;
}
</style>
