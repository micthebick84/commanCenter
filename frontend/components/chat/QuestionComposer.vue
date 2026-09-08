<script setup lang="ts">
// 채팅 입력창 (스펙 2026-09-05 §3·§6): textarea + 툴바(모델/effort 픽커 · #tools 슬롯 · 마이크 · 전송).
// 새 질문(#top 슬롯에 레포/브랜치)과 대화 페이지의 추가 질문 공용 — 픽커는 양쪽 모두 활성이며(§2 개정: 대화 중
// 변경은 다음 질문부터 적용) 입력이 막힌 동안(disabled/sending)만 함께 잠긴다.
// Enter 전송 · Shift+Enter 줄바꿈 · 한글 IME 조합 중 Enter는 무시(keydown.isComposing).
// 음성 입력(2026-09-08): Web Speech API 받아쓰기만 — 듣는 동안 입력창은 읽기 전용, 전송은 잠긴다. 멈춘 뒤 사용자가
// 확인·수정하고 직접 전송한다(자동 전송 없음). 미지원 브라우저는 버튼을 그리지 않는다(지원 판정은 마운트 후).
import { useQuasar } from 'quasar'
import ModelEffortPicker from '~/components/chat/ModelEffortPicker.vue'
import { useSpeechInput, type SpeechTranscript } from '~/composables/useSpeechInput'

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

const $q = useQuasar()
const inputLocked = computed(() => props.disabled || props.sending)

// ── 음성 입력 ──
let dictationBase = '' // 듣기 시작 시점의 입력값 — 인식 결과는 이 뒤에 이어 붙는다
function joinText(a: string, b: string): string {
  if (!b) return a
  if (!a) return b
  return /\s$/.test(a) ? a + b : a + ' ' + b
}
const { supported: micSupported, listening, start, stop, abort } = useSpeechInput({
  onTranscript: ({ final, interim }: SpeechTranscript) => {
    text.value = joinText(dictationBase, joinText(final, interim))
  },
  onError: (code) => {
    if (code === 'no-speech' || code === 'aborted') return // 아무 말 없음 · 우리가 중단 — 조용히
    if (code === 'not-allowed' || code === 'service-not-allowed') {
      $q.notify({ type: 'warning', message: '마이크 권한이 필요합니다' })
      return
    }
    $q.notify({ type: 'negative', message: `음성 인식 오류: ${code}` })
  },
})
function toggleMic() {
  if (listening.value) {
    stop()
    return
  }
  dictationBase = text.value ?? ''
  start()
}
// 입력이 막히면(세션 상태 변화·전송 중) 듣기도 즉시 중단
watch(inputLocked, (locked) => {
  if (locked) abort()
})

function onEnter(ev: KeyboardEvent) {
  // Safari는 IME 조합 완료 Enter에서 isComposing=false를 주면서 keyCode=229를 남기는 경우가 있다
  if (ev.isComposing || ev.keyCode === 229) return
  ev.preventDefault()
  if (props.canSend && !inputLocked.value && !listening.value) emit('send')
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
      :disable="inputLocked"
      :readonly="listening"
      :placeholder="listening ? '듣는 중…' : props.placeholder"
      class="composer-input"
      data-test="composer-input"
      @keydown.enter.exact="onEnter"
    >
      <!-- 마이크는 툴바가 아니라 입력창 오른쪽(append) — 390px에서 툴바(픽커+MCP+전송)가 이미 꽉 차 있어 버튼을 더 넣으면
           전송 버튼이 밀려 나간다(2026-09-08 프리뷰 빌드 실측). 모델 라벨 폭에 따라 달라지는 툴바와 분리해 모든 폭에서 안전. -->
      <template v-if="micSupported" #append>
        <q-btn
          round
          dense
          flat
          :icon="listening ? 'mic' : 'mic_none'"
          :color="listening ? 'negative' : 'grey-8'"
          :class="{ 'mic-listening': listening }"
          :aria-label="listening ? '음성 입력 중지' : '음성 입력'"
          :aria-pressed="listening ? 'true' : 'false'"
          :disable="inputLocked"
          data-test="composer-mic"
          @click="toggleMic"
        >
          <q-tooltip>{{ listening ? '탭하면 받아쓰기를 멈춥니다' : '음성으로 입력 — 멈춘 뒤 확인·수정하고 전송' }}</q-tooltip>
        </q-btn>
      </template>
    </q-input>
    <div class="row items-center no-wrap composer-bar">
      <ModelEffortPicker v-model:model="model" v-model:effort="effort" :disabled="inputLocked" />
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
        :disable="!props.canSend || props.disabled || listening"
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
/* 듣는 중: negative(#C10015) 링이 퍼지는 펄스 */
.mic-listening {
  animation: mic-pulse 1.2s ease-in-out infinite;
}
@keyframes mic-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(193, 0, 21, 0.35);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(193, 0, 21, 0);
  }
}
@media (prefers-reduced-motion: reduce) {
  .mic-listening {
    animation: none;
  }
}
</style>
