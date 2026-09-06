<script setup lang="ts">
// 채팅 입력창 (스펙 2026-09-05 §3·§6): textarea + 툴바(모델/effort 픽커 · #tools 슬롯 · 전송).
// 새 질문(#top 슬롯에 레포/브랜치)과 대화 페이지의 추가 질문 공용 — 픽커는 양쪽 모두 활성이며(§2 개정: 대화 중
// 변경은 다음 질문부터 적용) 입력이 막힌 동안(disabled/sending)만 함께 잠긴다.
// Enter 전송 · Shift+Enter 줄바꿈 · 한글 IME 조합 중 Enter는 무시(keydown.isComposing).
import ModelEffortPicker from '~/components/chat/ModelEffortPicker.vue'

const props = withDefaults(
  defineProps<{
    placeholder?: string
    /** 전송 가능 여부 — 부모가 판정(텍스트/상태/레포 선택 등) */
    canSend: boolean
    sending?: boolean
    /** 대화 페이지: 세션이 입력 대기가 아니면 입력·픽커를 함께 막는다 */
    disabled?: boolean
    /** 하단 안내 문구(모바일은 부모가 끈다) */
    hint?: boolean
  }>(),
  { placeholder: '추가 질문을 입력하세요…', sending: false, disabled: false, hint: true },
)
const text = defineModel<string>({ default: '' })
const model = defineModel<string>('model', { required: true })
const effort = defineModel<string>('effort', { required: true })
const emit = defineEmits<{ (e: 'send'): void }>()

function onEnter(ev: KeyboardEvent) {
  // Safari는 IME 조합 완료 Enter에서 isComposing=false를 주면서 keyCode=229를 남기는 경우가 있다
  if (ev.isComposing || ev.keyCode === 229) return
  ev.preventDefault()
  if (props.canSend && !props.sending && !props.disabled) emit('send')
}
</script>

<template>
  <div class="composer" :class="{ 'composer--disabled': props.disabled }">
    <slot name="top" />
    <q-input
      v-model="text"
      type="textarea"
      autogrow
      borderless
      dense
      :disable="props.disabled || props.sending"
      :placeholder="props.placeholder"
      class="composer-input"
      data-test="composer-input"
      @keydown.enter.exact="onEnter"
    />
    <div class="row items-center no-wrap composer-bar">
      <ModelEffortPicker v-model:model="model" v-model:effort="effort" :disabled="props.disabled || props.sending" />
      <slot name="tools" />
      <q-space />
      <q-btn
        round
        dense
        unelevated
        color="primary"
        icon="send"
        aria-label="전송"
        :loading="props.sending"
        :disable="!props.canSend || props.disabled"
        data-test="composer-send"
        @click="emit('send')"
      />
    </div>
  </div>
  <div v-if="props.hint" class="text-caption text-grey-7 composer-hint">Enter 전송 · Shift+Enter 줄바꿈</div>
</template>

<style scoped>
.composer {
  border: 1px solid rgba(0, 0, 0, 0.24);
  border-radius: 12px;
  background: #fff;
  padding: 4px 8px 8px 10px;
}
.composer--disabled {
  opacity: 0.7;
}
.composer-input :deep(textarea) {
  font-size: 14px;
  line-height: 18px;
}
.composer-bar {
  gap: 4px;
  padding-top: 4px;
}
.composer-hint {
  padding: 4px 12px 0;
}
</style>
