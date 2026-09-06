<script setup lang="ts">
import { useQuasar } from 'quasar'
import { useInterviewStream } from '~/composables/useInterviewStream'
import type { InterviewStatus, InterviewSnapshot } from '~/composables/useInterviewStream'
import { useAutoScroll } from '~/composables/useAutoScroll'
import { interviewStatusLabel, type SessionKind } from '~/composables/interviewLabels'
import ChatBubble from '~/components/chat/ChatBubble.vue'
import TypingIndicator from '~/components/chat/TypingIndicator.vue'
import PendingBubble from '~/components/chat/PendingBubble.vue'

const props = defineProps<{
  sessionId: number
  model?: string
  effort?: string
  readonly?: boolean
  /** 'QUESTION'이면 Q&A 모드 — /api/questions 경로, 설계·플랜 컬럼 미렌더, 질문 문맥 문구 (스펙 2026-08-30 §7). */
  kind?: SessionKind
  /** 질문 채팅 셸이 헤더(상태/비용/종료)를 직접 그릴 때 — 상태 바의 배지·칩·버튼을 숨긴다(터미널 배너는 유지). 스펙 2026-09-05 §2. */
  hideStatusBar?: boolean
  /** 부모 column을 채운다(70vh 고정 높이 대신). 질문 채팅 셸 전용. */
  fill?: boolean
}>()
const emit = defineEmits<{
  (e: 'confirmed', taskId: number): void
  (e: 'close'): void
  /** SSE 상태 변화 — 셸 헤더 배지가 폴링(5초)보다 먼저 반영하도록. immediate. */
  (e: 'status', status: InterviewStatus | null): void
}>()

const isQuestion = computed(() => props.kind === 'QUESTION')
const apiBase = computed(() => (isQuestion.value ? '/api/questions' : '/api/interviews'))
// 문맥 문구 — 인터뷰/질문 분기를 한 곳에 모은다.
const ui = computed(() =>
  isQuestion.value
    ? {
        send: '추가 질문',
        placeholder: '추가 질문을 입력하세요…',
        cancel: '세션 종료',
        cancelTitle: '질문 세션을 종료할까요?',
        cancelBody: '종료 후에는 추가 질문을 할 수 없습니다. 지금까지의 문답은 읽기 전용으로 남습니다.',
        cancelConfirm: '종료하기',
        preparing: '답변을 준비 중입니다…',
        starting: '질문 세션을 시작합니다…',
        expired: '세션이 만료되었습니다. 새 질문 세션을 열어 주세요.',
        cancelled: '질문 세션이 종료되었습니다.',
        failed: '답변 실패',
      }
    : {
        send: '전송',
        placeholder: '답변을 입력하세요…',
        cancel: '대화 취소',
        cancelTitle: '진행 중인 분석을 취소할까요?',
        cancelBody: '지금까지의 대화와 분석 진행 상황이 사라집니다. 작업은 등록되지 않습니다.',
        cancelConfirm: '취소하기',
        preparing: '첫 질문을 준비 중입니다…',
        starting: '인터뷰를 시작합니다…',
        expired: '세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.',
        cancelled: '인터뷰가 취소되었습니다.',
        failed: '인터뷰 실패',
      },
)

const stream = useInterviewStream()
const { connState, status, turns, designSections, plan, error, pending } = stream

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

// 화면 표시 전용 한글 라벨 (공유 상수 — interviewLabels.ts).
// 상태 비교/터미널 판정은 영문 enum 이름으로만 한다.
const statusLabel = computed(() =>
  status.value ? interviewStatusLabel(status.value, props.kind ?? 'INTERVIEW') : '연결 중',
)

const confirming = ref(false)
const designRequested = ref(false)

const isTerminal = computed(() =>
  ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(status.value as string),
)

// 셸 헤더용 실시간 상태 emit (스펙 2026-09-05 §6 [id].vue)
watch(status, (s) => emit('status', s), { immediate: true })

// hideStatusBar여도 만료/종료/실패/오류 배너는 사용자가 봐야 한다.
const showBanner = computed(
  () => ['EXPIRED', 'CANCELLED', 'FAILED'].includes(status.value as string) || !!error.value,
)

// AI 응답을 기다리는 중이면 타이핑 표시. 입력 차례/플랜 완료/종료에는 숨긴다.
// (스펙 §5.2 정밀화: AWAITING_INPUT·PLAN_READY를 먼저 제외해 PLAN_READY+턴0 경계 버그 방지.)
const waitingForAi = computed(() => {
  if (isTerminal.value) return false
  // 스트림이 종료됐는데 터미널 status가 안 온 경우(done-without-status 레이스): 스피너를 무한정 돌리지 않는다.
  if (connState.value === 'closed') return false
  if (status.value === 'AWAITING_INPUT' || status.value === 'PLAN_READY') return false
  if (status.value === 'QUEUED' || status.value === 'RUNNING') return true
  // status가 아직 없음(연결 직후, 첫 질문 전): 스트림이 살아있고 턴이 없으면 준비 중 표시.
  return turns.value.length === 0 && connState.value !== 'idle'
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

// 진행(활동/델타) 갱신도 '새 메시지'가 아니다 — 하단 근처일 때만 따라 내려가고 unread는 올리지 않는다.
watch(
  () =>
    pending.value
      ? pending.value.narration.length +
        pending.value.thinking.length +
        pending.value.activities.length
      : 0,
  async (len) => {
    if (len === 0) return
    await nextTick()
    if (nearBottom.value) scrollToBottom()
  },
)

/** composer 슬롯의 send(extra) — 질문 세션은 다음 턴에 쓸 모델·effort를 ask 바디에 싣는다 (스펙 2026-09-05 §2 개정). */
type AskExtra = { model?: string; effort?: string }

async function sendAnswer(extra?: AskExtra) {
  const text = answer.value.trim()
  if (!text || status.value !== 'AWAITING_INPUT') return
  const replyToSeq = lastQuestionSeq.value
  const body: Record<string, unknown> = { answer: text, replyToSeq }
  // 질문 세션만: 서버가 검증 후 세션 값을 갱신해 다음 claim부터 적용한다. 인터뷰 answer 바디는 그대로.
  if (isQuestion.value) {
    if (typeof extra?.model === 'string') body.model = extra.model
    if (typeof extra?.effort === 'string') body.effort = extra.effort
  }
  sending.value = true
  try {
    await useApi(`${apiBase.value}/${props.sessionId}/${isQuestion.value ? 'ask' : 'answer'}`, {
      method: 'POST',
      body,
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
    } else if (st === 400) {
      // 질문 세션 문답 상한 등 — 서버 메시지 그대로, 입력은 유지
      $q.notify({ type: 'warning', message: e?.data?.message ?? '요청이 거부되었습니다' })
    } else {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '답변 전송 실패' })
    }
  } finally {
    sending.value = false
  }
}

async function confirm() {
  if (status.value !== 'PLAN_READY' || confirming.value) return
  confirming.value = true
  try {
    const res = await useApi<{ taskId: number }>(`/api/interviews/${props.sessionId}/confirm`, {
      method: 'POST',
      body: { designRequested: designRequested.value },
    })
    $q.notify({ type: 'positive', message: '확정 완료 — 구현 큐에 진입했습니다' })
    emit('confirmed', res.taskId)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '확정 실패' })
  } finally {
    confirming.value = false
  }
}

// 대화 취소: 확인 다이얼로그를 거친 뒤에만 실제 취소.
const showCancelConfirm = ref(false)

async function cancelInterview() {
  try {
    await useApi(`${apiBase.value}/${props.sessionId}/${isQuestion.value ? 'close' : 'cancel'}`, { method: 'POST' })
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
  // 새로고침 복원: SSE replay는 어시스턴트 질문/설계만 주므로, 내 답변·status·plan은
  // REST 스냅샷으로 먼저 시드한 뒤 스트림을 연다(seq dedup으로 중복 없음). 스냅샷 실패는 비치명적.
  try {
    const snapshot = await useApi<InterviewSnapshot>(`${apiBase.value}/${props.sessionId}`)
    stream.hydrate(snapshot)
  } catch {
    /* 스냅샷 실패 — 스트림만으로 진행 */
  }
  stream.open(props.sessionId, apiBase.value)
  await nextTick()
  scrollToBottom('auto')
})
onUnmounted(() => stream.close())

// 셸의 "세션 종료" 버튼이 같은 확인 다이얼로그를 열 수 있게 노출 (스펙 2026-09-05 §3).
defineExpose({
  requestCancel() {
    showCancelConfirm.value = true
  },
})
</script>

<template>
  <div class="interview-panel column no-wrap" :class="{ 'interview-panel--fill': props.fill }">
    <div v-if="!props.hideStatusBar || showBanner" class="status-bar row items-center q-pa-sm q-gutter-sm">
      <template v-if="!props.hideStatusBar">
        <q-spinner
          v-if="connState === 'connecting' || connState === 'reconnecting'"
          size="18px"
          color="primary"
        />
        <!-- 색상/터미널 판정은 영문 enum(status), 표시는 한글(statusLabel). -->
        <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
        <q-chip v-if="props.model" dense size="sm" outline icon="smart_toy" :label="props.model" />
        <q-chip v-if="props.effort" dense size="sm" outline icon="tune" :label="props.effort" />
      </template>
      <q-banner v-if="status === 'EXPIRED'" dense class="bg-orange-1 text-orange-10 col"
        >{{ ui.expired }}</q-banner
      >
      <q-banner v-else-if="status === 'CANCELLED'" dense class="bg-grey-2 text-grey-9 col"
        >{{ ui.cancelled }}</q-banner
      >
      <q-banner v-else-if="status === 'FAILED'" dense class="bg-red-1 text-red-9 col"
        >{{ ui.failed }}: {{ error ?? '알 수 없는 오류가 발생했습니다' }}</q-banner
      >
      <q-banner v-else-if="error" dense class="bg-red-1 text-red-9 col">{{ error }}</q-banner>
      <q-space />
      <template v-if="!props.hideStatusBar">
        <q-btn
          v-if="!isTerminal"
          data-test="later-interview"
          flat
          dense
          no-caps
          color="grey-7"
          icon="schedule"
          label="나중에"
          @click="closePanel"
        />
        <q-btn
          v-if="!isTerminal && !readonly"
          data-test="cancel-interview"
          outline
          dense
          no-caps
          color="grey-7"
          icon="stop_circle"
          :label="ui.cancel"
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
      </template>
    </div>

    <!-- 좁은 화면 탭 전환 -->
    <q-tabs
      v-if="!isQuestion"
      v-model="activeTab"
      class="lt-md text-primary interview-tabs"
      dense
      align="justify"
    >
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
          <PendingBubble
            v-if="waitingForAi && pending"
            data-test="pending-bubble"
            :pending="pending"
          />
          <TypingIndicator v-else-if="waitingForAi" data-test="typing-indicator" />
          <div
            v-if="turns.length === 0 && waitingForAi"
            class="text-grey-6 q-mt-xs text-center"
            style="font-size: 12px"
          >
            {{ ui.preparing }}
          </div>
          <div
            v-if="turns.length === 0 && !waitingForAi"
            class="text-grey-6 q-pa-md text-center"
          >
            {{ ui.starting }}
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
        <div v-if="!readonly" class="answer-bar q-pa-sm">
          <slot
            name="composer"
            :answer="answer"
            :setAnswer="(v: string) => (answer = v)"
            :canSend="canAnswer"
            :sending="sending"
            :send="sendAnswer"
            :awaiting="status === 'AWAITING_INPUT'"
          >
            <q-input
              v-model="answer"
              type="textarea"
              outlined
              dense
              autogrow
              :disable="status !== 'AWAITING_INPUT' || sending"
              :placeholder="ui.placeholder"
              @keydown.enter.exact.prevent="sendAnswer()"
            />
            <div class="row justify-end q-mt-xs">
              <q-btn
                data-test="send-answer"
                unelevated
                color="primary"
                icon="send"
                :label="ui.send"
                :loading="sending"
                :disable="!canAnswer"
                @click="sendAnswer()"
              />
            </div>
          </slot>
        </div>
      </section>

      <q-separator v-if="!isQuestion" vertical class="gt-sm" />

      <!-- 우: 설계 섹션 + 플랜 (기능 동일) -->
      <section
        v-if="!isQuestion"
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

          <div v-if="!readonly" class="row items-center justify-end q-mt-md q-gutter-sm">
            <q-toggle
              v-model="designRequested"
              dense
              label="디자인 단계 포함"
            >
              <q-tooltip>분석 승인 후 화면 목업을 생성해 승인받습니다</q-tooltip>
            </q-toggle>
            <q-btn
              data-test="confirm"
              unelevated
              color="positive"
              icon="task_alt"
              label="구현 진행"
              :loading="confirming"
              :disable="status !== 'PLAN_READY' || confirming"
              @click="confirm"
            />
          </div>
        </div>
      </section>
    </div>

    <!-- 대화 취소 확인 -->
    <q-dialog v-model="showCancelConfirm">
      <q-card style="min-width: 320px">
        <q-card-section class="text-subtitle1 text-weight-bold">
          {{ ui.cancelTitle }}
        </q-card-section>
        <q-card-section class="q-pt-none text-grey-8">
          {{ ui.cancelBody }}
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
            :label="ui.cancelConfirm"
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
.interview-panel--fill {
  height: auto;
  min-height: 0;
  flex: 1 1 auto;
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
