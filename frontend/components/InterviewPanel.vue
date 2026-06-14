<script setup lang="ts">
import { useQuasar } from 'quasar'
import { useInterviewStream } from '~/composables/useInterviewStream'
import type { InterviewStatus } from '~/composables/useInterviewStream'
import { useAutoScroll } from '~/composables/useAutoScroll'
import ChatBubble from '~/components/chat/ChatBubble.vue'
import TypingIndicator from '~/components/chat/TypingIndicator.vue'

const props = defineProps<{ sessionId: number }>()
const emit = defineEmits<{ (e: 'registered', taskId: number): void; (e: 'close'): void }>()

const stream = useInterviewStream()
const { connState, status, turns, designSections, plan, error } = stream

// Narrow-screen tab fallback ('chat' | 'design').
const activeTab = ref<'chat' | 'design'>('chat')

const $q = useQuasar()
const answer = ref('')
const sending = ref(false)

// 마지막 미답변 assistant 질문의 seq (idempotency용 replyToSeq).
const lastQuestionSeq = computed(() => {
  for (let i = turns.value.length - 1; i >= 0; i--) {
    if (turns.value[i].role === 'assistant' && turns.value[i].kind === 'question') {
      return turns.value[i].seq
    }
  }
  return null
})

const canAnswer = computed(
  () => status.value === 'AWAITING_INPUT' && !sending.value && !!answer.value.trim(),
)

// 화면 표시 전용 한글 라벨. 상태 비교/터미널 판정은 영문 enum 이름으로만 한다.
const STATUS_LABELS: Record<InterviewStatus, string> = {
  QUEUED: '대기 중',
  RUNNING: '분석 중',
  AWAITING_INPUT: '입력 대기',
  PLAN_READY: '플랜 완료',
  REGISTERED: '등록됨',
  CANCELLED: '취소됨',
  EXPIRED: '만료됨',
  FAILED: '실패',
}
const statusLabel = computed(() =>
  status.value ? (STATUS_LABELS[status.value] ?? status.value) : '연결 중',
)

const registering = ref(false)

const isTerminal = computed(() =>
  ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(status.value as string),
)

// AI 응답을 기다리는 중이면 타이핑 표시. 입력 차례/플랜 완료/종료에는 숨긴다.
// (스펙 §5.2 정밀화: AWAITING_INPUT·PLAN_READY를 먼저 제외해 PLAN_READY+턴0 경계 버그 방지.)
const waitingForAi = computed(() => {
  if (isTerminal.value) return false
  if (status.value === 'AWAITING_INPUT' || status.value === 'PLAN_READY') return false
  if (status.value === 'QUEUED' || status.value === 'RUNNING') return true
  // status가 아직 없음(연결 직후, 첫 질문 전): 스트림이 살아있고 턴이 없으면 준비 중 표시.
  return turns.value.length === 0 && connState.value !== 'closed' && connState.value !== 'idle'
})

// 스마트 자동 스크롤.
const transcriptEl = ref<HTMLElement | null>(null)
const { nearBottom, unread, onScroll, scrollToBottom, notifyNewContent } = useAutoScroll(transcriptEl)

// 새 턴이 도착하면 스크롤 정책 적용: 하단 근처면 따라가고, 위로 읽는 중이면 unread++.
watch(
  () => turns.value.length,
  async () => {
    await nextTick()
    notifyNewContent()
  },
)
// 타이핑 표시 등장은 '새 메시지'가 아니다 — 하단 근처일 때만 따라 내려가고 unread는 올리지 않는다.
watch(waitingForAi, async (v) => {
  if (!v) return
  await nextTick()
  if (nearBottom.value) scrollToBottom()
})

async function sendAnswer() {
  const text = answer.value.trim()
  if (!text || status.value !== 'AWAITING_INPUT') return
  const replyToSeq = lastQuestionSeq.value
  sending.value = true
  try {
    await useApi(`/api/interviews/${props.sessionId}/answer`, {
      method: 'POST',
      body: { answer: text, replyToSeq },
    })
    // 낙관적 추가: 서버 재큐 후 다음 질문이 새 seq로 도착한다.
    turns.value = [
      ...turns.value,
      {
        seq: (replyToSeq ?? turns.value.length) + 0.5,
        role: 'user',
        kind: 'answer',
        content: text,
      },
    ]
    answer.value = ''
    status.value = 'QUEUED'
    await nextTick()
    scrollToBottom() // 내가 보낸 답변은 항상 하단으로
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    if (st === 409) {
      $q.notify({ type: 'warning', message: '세션이 만료되었거나 이미 처리된 답변입니다' })
    } else {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '답변 전송 실패' })
    }
  } finally {
    sending.value = false
  }
}

async function register() {
  if (status.value !== 'PLAN_READY' || registering.value) return
  registering.value = true
  try {
    const res = await useApi<{ taskId: number }>(`/api/interviews/${props.sessionId}/register`, {
      method: 'POST',
    })
    $q.notify({ type: 'positive', message: '작업 등록 완료' })
    emit('registered', res.taskId)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '작업 등록 실패' })
  } finally {
    registering.value = false
  }
}

// 대화 취소: 확인 다이얼로그를 거친 뒤에만 실제 취소.
const showCancelConfirm = ref(false)

async function cancelInterview() {
  try {
    await useApi(`/api/interviews/${props.sessionId}/cancel`, { method: 'POST' })
  } catch {
    /* 취소 실패는 무시 — 세션은 어차피 닫는다 */
  }
  status.value = 'CANCELLED' // 닫히기 전 UI 상태 정합 (배너/터미널 판정)
  stream.close()
  emit('close')
}

function confirmCancel() {
  showCancelConfirm.value = false
  cancelInterview()
}

function closePanel() {
  emit('close')
}

onMounted(async () => {
  stream.open(props.sessionId)
  await nextTick()
  scrollToBottom('auto')
})
onUnmounted(() => stream.close())
</script>

<template>
  <div class="interview-panel column no-wrap">
    <div class="status-bar row items-center q-pa-sm q-gutter-sm">
      <q-spinner
        v-if="connState === 'connecting' || connState === 'reconnecting'"
        size="18px"
        color="primary"
      />
      <!-- 색상/터미널 판정은 영문 enum(status), 표시는 한글(statusLabel). -->
      <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
      <q-banner v-if="status === 'EXPIRED'" dense class="bg-orange-1 text-orange-10 col"
        >세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.</q-banner
      >
      <q-banner v-else-if="status === 'CANCELLED'" dense class="bg-grey-2 text-grey-9 col"
        >인터뷰가 취소되었습니다.</q-banner
      >
      <q-banner v-else-if="status === 'FAILED'" dense class="bg-red-1 text-red-9 col"
        >인터뷰 실패: {{ error ?? '알 수 없는 오류가 발생했습니다' }}</q-banner
      >
      <q-banner v-else-if="error" dense class="bg-red-1 text-red-9 col">{{ error }}</q-banner>
      <q-space />
      <q-btn
        v-if="!isTerminal"
        data-test="cancel-interview"
        outline
        dense
        no-caps
        color="grey-7"
        icon="stop_circle"
        label="대화 취소"
        class="cancel-btn"
        @click="showCancelConfirm = true"
      />
      <q-btn
        v-if="isTerminal && status !== 'REGISTERED'"
        data-test="close-interview"
        flat
        dense
        no-caps
        color="grey-7"
        icon="close"
        label="닫기"
        @click="closePanel"
      />
    </div>

    <!-- 좁은 화면 탭 전환 -->
    <q-tabs v-model="activeTab" class="lt-md text-primary interview-tabs" dense align="justify">
      <q-tab name="chat" icon="forum" label="대화" />
      <q-tab name="design" icon="design_services" label="설계·플랜" />
    </q-tabs>

    <div class="interview-body row no-wrap">
      <!-- 좌: 대화 트랜스크립트 -->
      <section class="chat-col column no-wrap" :class="{ 'mobile-hidden': activeTab !== 'chat' }">
        <div
          ref="transcriptEl"
          class="transcript col scroll q-pa-sm"
          aria-live="polite"
          @scroll="onScroll"
        >
          <ChatBubble v-for="t in turns" :key="t.seq" :role="t.role" :content="t.content" />
          <TypingIndicator v-if="waitingForAi" data-test="typing-indicator" />
          <div
            v-if="turns.length === 0 && !waitingForAi"
            class="text-grey-6 q-pa-md text-center"
          >
            인터뷰를 시작합니다…
          </div>
          <div
            v-if="unread > 0"
            data-test="new-msg-pill"
            class="new-msg-pill"
            @click="scrollToBottom()"
          >
            ↓ 새 메시지 {{ unread }}
          </div>
        </div>
        <div class="answer-bar q-pa-sm">
          <q-input
            v-model="answer"
            type="textarea"
            outlined
            dense
            autogrow
            :disable="status !== 'AWAITING_INPUT' || sending"
            placeholder="답변을 입력하세요…"
            @keydown.enter.exact.prevent="sendAnswer"
          />
          <div class="row justify-end q-mt-xs">
            <q-btn
              data-test="send-answer"
              unelevated
              color="primary"
              icon="send"
              label="전송"
              :loading="sending"
              :disable="!canAnswer"
              @click="sendAnswer"
            />
          </div>
        </div>
      </section>

      <q-separator vertical class="gt-sm" />

      <!-- 우: 설계 섹션 + 플랜 (기능 동일) -->
      <section
        class="design-col column no-wrap"
        :class="{ 'mobile-hidden': activeTab !== 'design' }"
      >
        <div class="col scroll q-pa-sm">
          <div class="text-subtitle2 q-mb-sm">설계 섹션</div>
          <q-list bordered separator>
            <q-item v-for="d in designSections" :key="d.key">
              <q-item-section avatar>
                <q-icon
                  :name="d.approved ? 'check_circle' : 'radio_button_unchecked'"
                  :color="d.approved ? 'positive' : 'grey-5'"
                />
              </q-item-section>
              <q-item-section>
                <q-item-label>{{ d.title }}</q-item-label>
                <q-item-label caption style="white-space: pre-wrap">{{ d.body }}</q-item-label>
              </q-item-section>
            </q-item>
            <q-item v-if="designSections.length === 0">
              <q-item-section class="text-grey-6">아직 설계 섹션이 없습니다.</q-item-section>
            </q-item>
          </q-list>

          <div v-if="plan && plan.designMarkdown" class="q-mt-md">
            <div class="text-subtitle2 q-mb-sm">설계 문서</div>
            <pre class="plan-md">{{ plan.designMarkdown }}</pre>
          </div>

          <div v-if="plan" class="q-mt-md">
            <div class="text-subtitle2 q-mb-sm">구현 플랜</div>
            <pre class="plan-md">{{ plan.planMarkdown }}</pre>
          </div>

          <div class="row justify-end q-mt-md">
            <q-btn
              data-test="register"
              unelevated
              color="positive"
              icon="task_alt"
              label="작업 등록"
              :loading="registering"
              :disable="status !== 'PLAN_READY' || registering"
              @click="register"
            />
          </div>
        </div>
      </section>
    </div>

    <!-- 대화 취소 확인 -->
    <q-dialog v-model="showCancelConfirm">
      <q-card style="min-width: 320px">
        <q-card-section class="text-subtitle1 text-weight-bold">
          진행 중인 분석을 취소할까요?
        </q-card-section>
        <q-card-section class="q-pt-none text-grey-8">
          지금까지의 대화와 분석 진행 상황이 사라집니다. 작업은 등록되지 않습니다.
        </q-card-section>
        <q-card-actions align="right">
          <q-btn
            data-test="cancel-keep"
            flat
            no-caps
            label="계속하기"
            @click="showCancelConfirm = false"
          />
          <q-btn
            data-test="cancel-confirm"
            unelevated
            color="negative"
            no-caps
            label="취소하기"
            @click="confirmCancel"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>

<style scoped>
.interview-panel {
  height: 70vh;
  min-height: 420px;
}
.interview-body {
  flex: 1;
  min-height: 0;
}
.chat-col,
.design-col {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.transcript {
  min-height: 0;
  position: relative;
}
.plan-md {
  white-space: pre-wrap;
  font-family: 'Pretendard', sans-serif;
  font-size: 0.85rem;
}
.cancel-btn:hover {
  color: #c0392b !important;
}
.new-msg-pill {
  position: sticky;
  bottom: 8px;
  width: fit-content;
  margin: 4px auto 0;
  z-index: 5;
  background: var(--q-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 14px;
  border-radius: 16px;
  cursor: pointer;
  box-shadow: 0 3px 10px rgba(25, 118, 210, 0.4);
}
@media (max-width: 1023px) {
  .mobile-hidden {
    display: none;
  }
}
</style>
