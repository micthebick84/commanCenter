<script setup lang="ts">
// 5단계(분석·디자인·구현·배포·종료) 진행 스테퍼 (스펙 2026-09-06 §4.2, 캔버스 Detail/DesktopDetail). 상태 계산은 taskStages.stageSteps — 여기선 표시만.
import type { StageStep } from '~/composables/taskStages'

const props = withDefaults(
  defineProps<{ steps: StageStep[]; caption?: string; compact?: boolean }>(),
  { caption: '', compact: false },
)
const current = computed(() =>
  props.steps.find((s) => s.state === 'current' || s.state === 'failed'),
)

function circleStyle(s: StageStep) {
  if (s.state === 'done')
    return { background: '#2e7d32', borderColor: '#2e7d32' }
  if (s.state === 'current')
    return {
      background: s.color,
      borderColor: s.color,
      boxShadow: `0 0 0 4px ${s.color}2e`,
    }
  if (s.state === 'failed')
    return { background: '#c62828', borderColor: '#c62828' }
  if (s.state === 'skipped')
    return { background: '#fff', borderColor: '#e0e0e0', borderStyle: 'dashed' }
  return { background: '#fff', borderColor: '#bdbdbd' }
}
function segStyle(s: StageStep) {
  if (s.state === 'done') return { background: '#a5d6a7' }
  if (s.state === 'current') return { background: s.color }
  if (s.state === 'failed') return { background: '#c62828' }
  if (s.state === 'skipped')
    return {
      background:
        'repeating-linear-gradient(90deg, #e0e0e0 0 4px, transparent 4px 8px)',
    }
  return { background: '#eeeeee' }
}
function labelColor(s: StageStep) {
  if (s.state === 'current') return s.color
  if (s.state === 'failed') return '#c62828'
  if (s.state === 'done') return '#2e7d32'
  return '#9e9e9e'
}
</script>

<template>
  <div
    class="stepper"
    :class="{ 'stepper--compact': compact }"
    data-test="stepper"
  >
    <template v-if="compact">
      <div class="row items-center no-wrap" style="gap: 10px">
        <div class="row no-wrap col" style="gap: 4px">
          <span
            v-for="s in steps"
            :key="s.key"
            class="seg"
            :data-test="`step-${s.key}`"
            :data-state="s.state"
            :style="segStyle(s)"
          />
        </div>
        <span
          v-if="current"
          class="text-caption text-weight-bold"
          :style="{ color: labelColor(current) }"
          >{{ current.label }}</span
        >
      </div>
    </template>
    <template v-else>
      <!-- 단계마다 원과 라벨을 한 칸에 세로로 묶어 라벨이 항상 제 원 바로 밑에 온다.
           연결선은 이 칸의 원 중심에서 다음 칸의 원 중심까지(left 50%, width 100%). -->
      <div class="track">
        <div
          v-for="(s, i) in steps"
          :key="s.key"
          class="step"
          :class="`step--${s.state}`"
          :data-test="`step-${s.key}`"
          :data-state="s.state"
        >
          <div
            v-if="i < steps.length - 1"
            class="line"
            :style="{ background: s.state === 'done' ? '#a5d6a7' : '#e0e0e0' }"
          />
          <div class="circle" :style="circleStyle(s)">
            <q-icon
              v-if="s.state === 'done'"
              name="check"
              color="white"
              size="16px"
            />
            <q-icon
              v-else-if="s.state === 'failed'"
              name="priority_high"
              color="white"
              size="16px"
            />
            <span v-else-if="s.state === 'current'" class="core" />
          </div>
          <span
            class="label"
            data-test="step-label"
            :style="{
              color: labelColor(s),
              fontWeight: s === current ? 700 : 400,
            }"
            >{{ s.label }}</span
          >
        </div>
      </div>
      <div v-if="caption" class="caption" data-test="stepper-caption">
        {{ caption }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.stepper {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.track {
  display: flex;
}
.step {
  position: relative;
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.circle {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2px solid;
  display: flex;
  align-items: center;
  justify-content: center;
}
.core {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
}
.line {
  position: absolute;
  top: 11.5px;
  left: 50%;
  width: 100%;
  height: 3px;
}
.label {
  max-width: 100%;
  text-align: center;
  font-size: 11.5px;
  line-height: 1.2;
  word-break: keep-all;
}
.caption {
  font-size: 12.5px;
  color: #616161;
  text-align: center;
}
.seg {
  flex: 1 1 0;
  height: 5px;
  border-radius: 3px;
}
</style>
