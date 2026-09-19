<script setup lang="ts">
// 채팅 입력창 (스펙 2026-09-05 §3·§6): textarea + 툴바(모델/effort 픽커 · #tools 슬롯 · 마이크 · 전송).
// 새 질문(#top 슬롯에 레포/브랜치)과 대화 페이지의 추가 질문 공용 — 픽커는 양쪽 모두 활성이며(§2 개정: 대화 중
// 변경은 다음 질문부터 적용) 입력이 막힌 동안(disabled/sending)만 함께 잠긴다.
// Enter 전송 · Shift+Enter 줄바꿈 · 한글 IME 조합 중 Enter는 무시(keydown.isComposing).
// 음성 입력(2026-09-08): Web Speech API 받아쓰기만 — 듣는 동안 입력창은 읽기 전용, 전송은 잠긴다. 멈춘 뒤 사용자가
// 확인·수정하고 직접 전송한다(자동 전송 없음). 미지원 브라우저는 버튼을 그리지 않는다(지원 판정은 마운트 후).
// 첨부(2026-09-13 스펙 §7): 클립 버튼(입력창 prepend) → 숨은 q-file → 대기 파일 칩 줄(#top과 입력창 사이). 파일은
// v-model:files로 부모가 들고 전송 성공 시 비운다. 한도(validateFiles)를 넘는 추가는 통째로 거부하고 경고한다.
import { useQuasar, type QFile } from 'quasar'
import ModelEffortPicker from '~/components/chat/ModelEffortPicker.vue'
import { useSpeechInput, type SpeechTranscript } from '~/composables/useSpeechInput'
import {
  validateFiles,
  formatSize,
  MAX_FILES,
  MAX_FILE_MB,
  MAX_TOTAL_MB,
} from '~/composables/attachmentLimits'

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
const files = defineModel<File[]>('files', { default: () => [] })
const emit = defineEmits<{ (e: 'send'): void }>()

const $q = useQuasar()
const inputLocked = computed(() => props.disabled || props.sending)

// ── 첨부 ──
const attachTip = `파일 첨부 — 최대 ${MAX_FILES}개, 파일당 ${MAX_FILE_MB}MB, 합계 ${MAX_TOTAL_MB}MB`
const fileInput = ref<QFile | null>(null)
function openFilePicker() {
  fileInput.value?.pickFiles()
}
// q-file은 모델을 묶지 않고(:model-value="null") 선택 결과만 받는다 — 같은 파일을 다시 골라도 emit되고,
// 기존 대기 파일에 누적한다. 합친 결과가 한도를 넘으면 이번 선택은 하나도 넣지 않는다(부분 수용 없음).
function onPick(picked: File | FileList | File[] | null) {
  const added =
    picked == null ? [] : Array.isArray(picked) ? picked : picked instanceof File ? [picked] : Array.from(picked)
  if (added.length === 0) return
  const next = [...files.value, ...added]
  const err = validateFiles(next)
  if (err) {
    $q.notify({ type: 'warning', message: err })
    return
  }
  files.value = next
}
function removeFile(index: number) {
  files.value = files.value.filter((_, i) => i !== index)
}

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
    <!-- 대기 파일 칩 줄 — #top(레포/브랜치)과 입력창 사이. 비면 줄 자체를 그리지 않는다. -->
    <div v-if="files.length" class="row items-center composer-files" data-test="composer-files">
      <q-chip
        v-for="(f, i) in files"
        :key="`${f.name}-${f.size}-${i}`"
        dense
        size="sm"
        removable
        icon="attach_file"
        color="blue-grey-1"
        text-color="blue-grey-9"
        :label="`${f.name} (${formatSize(f.size)})`"
        :disable="inputLocked"
        @remove="removeFile(i)"
      />
    </div>
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
      <!-- 클립은 마이크와 같은 이유로 툴바가 아니라 입력창 안(왼쪽 prepend) — 390px에서 툴바(픽커+MCP+전송)는 이미 꽉 차 있어
           버튼을 더 넣으면 전송 버튼이 밀려 나간다(2026-09-08 프리뷰 빌드 실측). 입력창 폭만 줄어들므로 모든 폭에서 안전. -->
      <template #prepend>
        <q-btn
          round
          dense
          flat
          icon="attach_file"
          color="grey-8"
          aria-label="파일 첨부"
          :disable="inputLocked"
          data-test="composer-attach"
          @click="openFilePicker"
        >
          <q-tooltip>{{ attachTip }}</q-tooltip>
        </q-btn>
      </template>
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
    <!-- 숨은 파일 입력 — 클립 버튼이 pickFiles()로 연다. 모델을 묶지 않아 같은 파일 재선택도 emit된다. -->
    <q-file
      ref="fileInput"
      :model-value="null"
      multiple
      :disable="inputLocked"
      class="composer-file-input"
      data-test="composer-file-input"
      @update:model-value="onPick"
    />
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
.composer-files {
  gap: 2px;
  padding: 4px 4px 0;
}
/* 파일 대화상자는 클립 버튼이 연다 — 필드 자체는 화면에 두지 않는다 (display:none이어도 input.click()은 동작) */
.composer-file-input {
  display: none;
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
