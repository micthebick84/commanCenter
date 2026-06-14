<script setup lang="ts">
// 대화 턴 1개를 말풍선으로. 순수 프레젠테이션, 내부 상태 없음.
// assistant → 좌측(회색 말풍선 + AI 아바타), user → 우측(파란 말풍선 + 나 아바타),
// system → 가운데 노트 칩.
const props = defineProps<{
  role: 'assistant' | 'user' | 'system'
  content: string
}>()

const avatarLabel = computed(() => (props.role === 'assistant' ? 'AI' : '나'))
</script>

<template>
  <div v-if="role === 'system'" class="bubble-row system">
    <span class="system-note">{{ content }}</span>
  </div>
  <div v-else class="bubble-row" :class="role">
    <div class="avatar" :class="role">{{ avatarLabel }}</div>
    <div class="bubble" :class="role">{{ content }}</div>
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
</style>
