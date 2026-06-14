<script setup lang="ts">
// AI "응답 대기 중" 표시: assistant 말풍선 안의 점 3개 통통 애니메이션.
// prefers-reduced-motion이면 애니메이션을 끄고 정적 점으로.
defineProps<{ label?: string }>()
</script>

<template>
  <div class="typing-row" role="status" :aria-label="label ?? 'AI가 응답을 준비 중'">
    <div class="avatar ai">AI</div>
    <div class="typing-bubble">
      <span class="typing-dots" aria-hidden="true">
        <span class="dot" /><span class="dot" /><span class="dot" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.typing-row {
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
.typing-bubble {
  background: #eef2f8;
  border-radius: 14px;
  border-bottom-left-radius: 4px;
  padding: 11px 14px;
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
</style>
