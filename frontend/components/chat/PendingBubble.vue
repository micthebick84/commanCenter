<script setup lang="ts">
// 진행 중(확정 응답 전) 어시스턴트 버블: 최신 도구 활동 + thinking(기본 접힘) + 내레이션 미리보기.
// 순수 프레젠테이션 — 내부 상태는 thinking 펼침 토글뿐. 확정 question이 도착하면 부모가 제거한다.
// 고빈도 델타 갱신이라 aria-live는 off — 확정 턴은 transcript의 aria-live="polite"가 알린다.
import type { PendingActivity } from '~/composables/useInterviewStream'

const props = defineProps<{ pending: PendingActivity }>()

const showThinking = ref(false)

const latest = computed(() =>
  props.pending.activities.length > 0
    ? props.pending.activities[props.pending.activities.length - 1]
    : null,
)
// 최신 라인 직전 최대 3건 (오래된 → 최신)
const recent = computed(() => props.pending.activities.slice(-4, -1))
</script>

<template>
  <div class="pending-row" aria-live="off">
    <div class="avatar ai">AI</div>
    <div class="pending-bubble">
      <div class="activity-head">
        <span class="typing-dots" aria-hidden="true">
          <span class="dot" /><span class="dot" /><span class="dot" />
        </span>
        <span v-if="latest" class="activity-line" data-test="activity-latest">
          {{ latest.label }}<template v-if="latest.detail"> · {{ latest.detail }}</template>
        </span>
      </div>
      <div v-if="recent.length" class="activity-recent">
        <div v-for="(a, i) in recent" :key="i" class="recent-line">
          {{ a.label }}<template v-if="a.detail"> · {{ a.detail }}</template>
        </div>
      </div>
      <div v-if="pending.thinking" class="thinking-box">
        <button
          type="button"
          class="thinking-toggle"
          data-test="thinking-toggle"
          @click="showThinking = !showThinking"
        >
          {{ showThinking ? '▾' : '▸' }} 추론 중…
        </button>
        <pre v-if="showThinking" class="thinking-text" data-test="thinking-text">{{
          pending.thinking
        }}</pre>
      </div>
      <div v-if="pending.narration" class="narration" data-test="narration">{{ pending.narration }}</div>
    </div>
  </div>
</template>

<style scoped>
.pending-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: 0 0 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
}
.avatar.ai {
  background: #e3f2fd;
  color: #1565c0;
}
.pending-bubble {
  max-width: 80%;
  background: #eef2f8;
  border-radius: 14px;
  border-bottom-left-radius: 4px;
  padding: 9px 12px;
}
.activity-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 18px;
}
.typing-dots {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7f8ea3;
  animation: typing-bounce 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.18s;
}
.dot:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
    opacity: 0.6;
  }
}
.activity-line {
  font-size: 11.5px;
  font-weight: 600;
  color: #3d4c60;
  word-break: break-all;
}
.activity-recent {
  margin-top: 4px;
}
.recent-line {
  font-size: 10.5px;
  color: #9aa7b6;
  word-break: break-all;
}
.thinking-box {
  margin-top: 6px;
}
.thinking-toggle {
  border: none;
  background: none;
  padding: 0;
  font-size: 11px;
  color: #8a97a8;
  cursor: pointer;
}
.thinking-text {
  margin: 4px 0 0;
  max-height: 180px;
  overflow-y: auto;
  font-size: 11px;
  line-height: 1.45;
  color: #8a97a8;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Pretendard', sans-serif;
}
.narration {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.46;
  color: #25303f;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
