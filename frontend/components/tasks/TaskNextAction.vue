<script setup lang="ts">
// "다음 할 일" (스펙 2026-09-06 §4.2). banner = 데스크톱 상세 상단 우측 패널, bar = 모바일 하단 고정 액션 바.
// 실제 동작(승인/배포/재시도…)은 부모가 act(kind)로 받아 기존 핸들러에 연결한다.
import type { NextAction, NextActionKind } from '~/composables/taskStages'

defineProps<{ action: NextAction | null; variant: 'banner' | 'bar' }>()
const emit = defineEmits<{ (e: 'act', kind: NextActionKind): void }>()
</script>

<template>
  <div
    v-if="action"
    class="next-action"
    :class="`next-action--${variant}`"
    data-test="next-action"
  >
    <template v-if="variant === 'banner'">
      <div
        class="row items-center text-primary text-caption text-weight-bold"
        style="gap: 6px; letter-spacing: 0.04em"
      >
        <q-icon name="play_arrow" size="16px" />다음 할 일
      </div>
      <div class="text-body1" data-test="next-action-text">
        {{ action.text }}
      </div>
      <!-- primary가 없어도 extra(예: PR 열기 버튼) 슬롯이 채워졌으면 행은 그대로 그린다 -->
      <div v-if="action.primary || $slots.extra" class="row" style="gap: 8px">
        <q-btn
          v-if="action.primary"
          unelevated
          color="primary"
          :icon="action.primary.icon"
          :label="action.primary.label"
          data-test="next-action-primary"
          @click="emit('act', action.primary.kind)"
        />
        <slot name="extra" />
      </div>
    </template>
    <template v-else>
      <q-btn
        v-if="action.primary"
        unelevated
        color="primary"
        :icon="action.primary.icon"
        :label="action.primary.label"
        class="col bar-primary"
        data-test="next-action-primary"
        @click="emit('act', action.primary.kind)"
      />
      <div
        v-else
        class="col text-body2 text-grey-8 bar-text"
        data-test="next-action-text"
      >
        {{ action.text }}
      </div>
      <slot name="extra" />
    </template>
  </div>
</template>

<style scoped>
.next-action--banner {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  background: #f5f9fd;
  height: 100%;
}
.next-action--bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: var(--bottom-nav-height, 0px);
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #fff;
  border-top: 1px solid rgba(0, 0, 0, 0.12);
}
.bar-primary {
  min-height: 44px;
}
.bar-text {
  min-height: 44px;
  display: flex;
  align-items: center;
  padding: 0 4px;
}
</style>
