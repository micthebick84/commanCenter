<script setup lang="ts">
// 대화 턴 1개를 말풍선으로. 순수 프레젠테이션, 내부 상태 없음.
// assistant → 좌측(회색 말풍선 + AI 아바타, 마크다운 렌더), user → 우측(파란 말풍선 + 나 아바타, 평문),
// system → 가운데 노트 칩.
import { renderMarkdown } from '~/composables/useMarkdown'

const props = defineProps<{
  role: 'assistant' | 'user' | 'system'
  content: string
}>()

const avatarLabel = computed(() =>
  props.role === 'assistant' ? 'AI' : props.role === 'user' ? '나' : '',
)
// assistant만 마크다운 — 사용자 입력은 평문 pre-wrap 유지 (스펙 2026-09-05 §2).
const html = computed(() =>
  props.role === 'assistant' ? renderMarkdown(props.content) : '',
)
</script>

<template>
  <div v-if="role === 'system'" class="bubble-row system">
    <span class="system-note">{{ content }}</span>
  </div>
  <div v-else class="bubble-row" :class="role">
    <div class="avatar" :class="role">{{ avatarLabel }}</div>
    <!-- renderMarkdown은 html:false라 원시 HTML을 이스케이프한다 — v-html 안전 -->
    <div
      v-if="role === 'assistant'"
      class="bubble assistant markdown"
      v-html="html"
    />
    <div v-else class="bubble" :class="role">{{ content }}</div>
  </div>
</template>

<style scoped>
.bubble-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.bubble-row.assistant {
  flex-direction: row;
}
.bubble-row.user {
  flex-direction: row-reverse;
}
.bubble-row.system {
  justify-content: center;
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
.avatar.assistant {
  background: #e3f2fd;
  color: #1565c0;
}
.avatar.user {
  background: #eceff3;
  color: #5b6b7d;
}
.bubble {
  max-width: 80%;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.46;
  border-radius: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.assistant {
  background: #eef2f8;
  color: #25303f;
  border-bottom-left-radius: 4px;
}
.bubble.user {
  background: #1976d2;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.system-note {
  font-size: 11px;
  color: #8a97a8;
  background: #f0f2f5;
  padding: 3px 11px;
  border-radius: 11px;
}
/* 마크다운 본문 — 렌더된 블록은 pre-wrap을 끄고 여백을 말풍선에 맞춘다.
   클래스명은 Quasar 반응형 표시 헬퍼(xs/sm/md/lg/xl)와 겹치지 않게 유지할 것 — 겹치면 해당 폭 밖에서 display:none. */
.bubble.markdown {
  white-space: normal;
}
.bubble.markdown :deep(p) {
  margin: 0 0 6px;
}
.bubble.markdown :deep(p:last-child) {
  margin-bottom: 0;
}
.bubble.markdown :deep(ul),
.bubble.markdown :deep(ol) {
  margin: 4px 0;
  padding-left: 18px;
}
.bubble.markdown :deep(li) {
  margin: 2px 0;
}
.bubble.markdown :deep(h1),
.bubble.markdown :deep(h2),
.bubble.markdown :deep(h3),
.bubble.markdown :deep(h4) {
  font-size: 13.5px;
  font-weight: 700;
  margin: 8px 0 4px;
}
.bubble.markdown :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  background: rgba(37, 48, 63, 0.07);
  padding: 1px 4px;
  border-radius: 3px;
}
.bubble.markdown :deep(pre) {
  background: rgba(37, 48, 63, 0.07);
  padding: 8px 10px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 6px 0;
}
.bubble.markdown :deep(pre code) {
  background: none;
  padding: 0;
}
.bubble.markdown :deep(a) {
  color: #1565c0;
}
.bubble.markdown :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
}
.bubble.markdown :deep(th),
.bubble.markdown :deep(td) {
  border: 1px solid #d5dbe5;
  padding: 3px 6px;
}
.bubble.markdown :deep(blockquote) {
  margin: 6px 0;
  padding-left: 10px;
  border-left: 3px solid #c9d2df;
  color: #4b5866;
}
</style>
