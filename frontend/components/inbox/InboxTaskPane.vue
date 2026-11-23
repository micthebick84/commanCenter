<script setup lang="ts">
// 처리함 처리 영역 (스펙 2026-11-23 §5.3·§6.2) — 고른 작업 하나를 폴링해 머리글과 상태별 본문을 그린다.
// 부모가 작업마다 :key로 다시 마운트한다(폴링·대화 패널 SSE는 한 번에 하나). 처리 성공은 done으로, 이전/다음은 navigate로,
// 조회 404는 missing으로 올린다 — 다음 건으로 넘어갈지는 부모가 정한다. 이 화면은 아무것도 저절로 보내지 않는다.
import { onBeforeUnmount, toRaw } from 'vue'
import { useQuasar } from 'quasar'
import { failMsg } from '~/composables/failMessage'
import InterviewPanel from '~/components/InterviewPanel.vue'
import ApproveForm from '~/components/ApproveForm.vue'
import RefRepoChips from '~/components/RefRepoChips.vue'
import InboxGateActions from '~/components/inbox/InboxGateActions.vue'
import InboxFailureActions from '~/components/inbox/InboxFailureActions.vue'
import DeployEnvDialog from '~/components/tasks/DeployEnvDialog.vue'
import ImplementationRetryDialog from '~/components/tasks/ImplementationRetryDialog.vue'
import FixRequestDialog from '~/components/tasks/FixRequestDialog.vue'
import TaskRefChangeMenu from '~/components/tasks/TaskRefChangeMenu.vue'
import { useTaskActions } from '~/composables/useTaskActions'
import { useInterviewRefDraft } from '~/composables/useInterviewRefDraft'
import { CHIP, ageOf, nextAction } from '~/composables/taskStages'
import { taskTypeLabel } from '~/composables/taskType'
import { formatSize } from '~/composables/attachmentLimits'
import { downloadAttachment as downloadBlob } from '~/composables/attachmentDownload'
import { INBOX_GROUPS, isInboxCandidate, type HandledMark } from '~/composables/taskInbox'
import type { AttachmentMeta, TaskResponse } from '~/composables/taskDetailTypes'
import type { InterviewStatus } from '~/composables/useInterviewStream'
// defineProps 타입이므로 상대 경로 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import { taskRefContext, toInputs } from '../../composables/refRepos'
import type { RefDraftEntry } from '../../composables/useInterviewRefDraft'

const props = defineProps<{
  taskId: number
  /** 세션 id별 쓰던 답변 — 처리함(페이지)이 들고 있어 작업을 오가도 남는다. */
  answerDrafts: Map<number, string>
  /** 세션 id별 보내지 않은 참조 변경 — 같은 이유. */
  refDraftStore: Map<number, RefDraftEntry>
  canPrev: boolean
  canNext: boolean
  /** 폰 전체 화면 창 — 남은 높이를 채우고, 제목·이전/다음은 창의 위 바가 보인다. */
  fill?: boolean
}>()
const emit = defineEmits<{
  (e: 'done', mark: HandledMark): void
  (e: 'navigate', dir: 1 | -1): void
  (e: 'missing'): void
}>()

const $q = useQuasar()
const auth = useAuthStore()

const { data: task, error, refresh } = useTaskPolling<TaskResponse>(() => useApi(`/api/tasks/${props.taskId}`))

function errorStatus(e: unknown): number | undefined {
  const err = e as { statusCode?: number; response?: { status?: number }; status?: number } | null
  return err?.statusCode ?? err?.response?.status ?? err?.status
}

// 지워졌거나 없는 작업(?task=) — 부모가 목록 맨 위를 고른다. 한 번만 알린다.
let missingSent = false
watch(error, (e) => {
  if (errorStatus(e) === 404 && !missingSent) {
    missingSent = true
    emit('missing')
  }
})
// 404 말고 못 읽었으면(네트워크·5xx) 스피너만 돌리지 않고 알린다 — 폴링이 계속 다시 읽어 되면 그대로 그린다.
const loadFailed = computed(() => error.value != null && errorStatus(error.value) !== 404)

const actions = useTaskActions(task, refresh)

// 인터뷰 참조 초안 — 세션별 저장소를 넘겨 다른 세션을 갔다 와도 보내지 않은 변경이 남는다(스펙 §5.5).
const { isInterviewPhase, pickedRefs, answerRefs, canChangeRefs, showRefRow } = useInterviewRefDraft(task, {
  store: props.refDraftStore,
})
// 공용 모듈은 "같은 화면에서 세션이 바뀔 때" 떠나는 세션의 초안을 저장하는데, 처리 영역은 작업마다 다시 마운트돼 그 순간이 없다 —
// 떠날 때 지금 세션의 초안을 저장한다. 맞춘 서버 집합은 지금 서버 값으로 둔다: 보내지 않은 변경이 없으면 초안이 이미 서버를 따라가 있어
// 같은 값이고, 있으면 돌아왔을 때 초안 ≠ 기준이라 공용 모듈의 규칙대로 그대로 남는다.
onBeforeUnmount(() => {
  const t = task.value
  if (!t || !isInterviewPhase.value || t.interviewSessionId == null) return
  props.refDraftStore.set(t.interviewSessionId, { picked: [...pickedRefs.value], syncedTo: toInputs(t.refRepos ?? []) })
})

// 쓰던 답변 — 패널은 draft를 마운트 때만 읽는다(세션 키로 다시 마운트). 빈 글이라도 넘겨야 패널이 update:draft를 보낸다.
// 읽기를 추적하지 않아 글자마다 처리 영역 전체가 다시 그려지지 않게 한다.
function draftOf(sessionId: number): string {
  return toRaw(props.answerDrafts).get(sessionId) ?? ''
}
function saveDraft(sessionId: number, text: string) {
  if (text) props.answerDrafts.set(sessionId, text)
  else props.answerDrafts.delete(sessionId)
}

// 처리 성공 때 부모가 목록에서 숨길 기준 {id, status, updatedAt} — 동작 직전에 본 확인 필요 상태다. 대화 패널은 동작 시작을 알리지 않고,
// 전송 중에 폴링이 이미 인터뷰중(새 updatedAt)을 받아 올 수 있어 마지막으로 본 확인 필요 상태를 들고 있는다. 본 적이 없으면 지금 값.
let lastActionable: HandledMark | null = null
watch(
  task,
  (t) => {
    if (t && isInboxCandidate(t)) lastActionable = { id: t.id, status: t.status, updatedAt: t.updatedAt }
  },
  { immediate: true },
)
function markNow(): HandledMark | null {
  const t = task.value
  return lastActionable ?? (t ? { id: t.id, status: t.status, updatedAt: t.updatedAt } : null)
}
function emitDone(mark: HandledMark | null = markNow()) {
  if (mark) emit('done', mark)
}
// 승인·계획 확정은 성공 뒤 작업을 바로 다시 읽는다(상세의 onApproved·onInterviewConfirmed와 같다) — 자동 이동을 껐으면 이 화면에 머물므로
// 다음 폴링까지 끝난 승인 폼이 남아 다시 누를 수 있게 두지 않는다. 작업 취소는 공용 동작이 이미 다시 읽는다.
function onFormDone() {
  emitDone()
  refresh()
}

// 패널의 실시간 상태(SSE) — "✎ 참조 변경"은 입력 대기일 때만 연다. 턴이 끝나 입력대기·계획 완료로 바뀌면 작업을 바로 다시 읽는다
// (작업 상세 onInterviewStatus와 같은 규칙 — 참조 고정 상태가 폴링보다 늦게 온다). 처음 연결(null → 상태)은 건너뛴다.
const interviewLive = ref<InterviewStatus | null>(null)
function onStatus(s: InterviewStatus | null) {
  const prev = interviewLive.value
  interviewLive.value = s
  if (prev !== null && s !== prev && (s === 'PLAN_READY' || s === 'AWAITING_INPUT')) refresh()
}

// 승인 폼이 보내는 중이면 작업 취소를 잠근다
const approving = ref(false)
async function cancelTask() {
  const mark = markNow()
  if (await actions.cancelTask()) emitDone(mark)
}

// 승인 계열·실패 계열 본문은 각자 컴포넌트가 맡는다(스펙 §5.3 표). 표에 없는 상태는 처리할 것이 없는 상태로 그린다.
const GATE_STATUSES = ['COMPLETED', 'DESIGN_REVIEW', 'FIX_REVIEW', 'CLOSE_REVIEW']
const FAILURE_STATUSES = ['FAILED', 'DESIGN_FAILED', 'IMPLEMENTATION_FAILED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'CLOSE_FAILED']

// 창 세 개 — 상세와 같은 부품·같은 props. 보내기에 성공하면(submitted) 처리한 것으로 본다.
const implRetryOpen = ref(false)
const fixRequestOpen = ref(false)
const deployEnvOpen = ref(false)
function openDialog(kind: 'implRetry' | 'deployEnv' | 'fixRequest') {
  if (kind === 'implRetry') implRetryOpen.value = true
  else if (kind === 'fixRequest') fixRequestOpen.value = true
  else deployEnvOpen.value = true
}
function onDialogSubmitted() {
  emitDone()
  refresh()
}

type Body = 'interview' | 'approve' | 'gate' | 'failure' | 'idle'
// 인터뷰 세 상태는 한 덩어리다 — 입력대기 ↔ 인터뷰중이 바뀌어도 패널을 다시 마운트하지 않는다(고른 첨부·스크롤 유지).
const body = computed<Body | null>(() => {
  const t = task.value
  if (!t) return null
  if (isInterviewPhase.value && t.interviewSessionId != null) return 'interview'
  if (t.status === 'AWAITING_APPROVAL') return 'approve'
  if (!isInboxCandidate(t)) return 'idle' // 재시도 한도를 다 쓴 실패 등 — 처리할 것이 없는 상태(스펙 §5.2)
  if (GATE_STATUSES.includes(t.status)) return 'gate'
  if (FAILURE_STATUSES.includes(t.status)) return 'failure'
  return 'idle'
})

const nextText = computed(() => (task.value ? (nextAction(task.value, true)?.text ?? '') : ''))
// 머리글의 다음 할 일 한 줄 — 처리할 본문이 있을 때만. 처리할 것이 없는 상태는 본문이 같은 줄을 직접 보이고,
// 인터뷰중은 본문 위 안내("에이전트가 답을 만드는 중입니다")가 같은 말을 한다.
const headNext = computed(() =>
  body.value === 'approve' || body.value === 'gate' || body.value === 'failure' || (body.value === 'interview' && task.value?.status !== 'INTERVIEWING') ? nextText.value : '',
)
const chip = computed(() => CHIP[task.value?.status ?? ''] ?? ['#f5f5f5', '#616161'])
const FAILED_STATUSES = INBOX_GROUPS.find((g) => g.key === 'failed')?.statuses ?? []
const failureText = computed(() =>
  task.value && FAILED_STATUSES.includes(task.value.status) ? task.value.failureReason : null,
)
const detailHref = computed(() => `/tasks/${props.taskId}`)

async function downloadAttachment(att: Pick<AttachmentMeta, 'id' | 'fileName'>) {
  try {
    await downloadBlob(`/api/tasks/${props.taskId}/attachments/${att.id}`, att.fileName)
  } catch (e: unknown) {
    $q.notify({ type: 'negative', message: e instanceof Error ? e.message : failMsg('다운로드') })
  }
}
</script>

<template>
  <div class="inbox-pane" :class="{ 'inbox-pane--fill column no-wrap': fill }" data-test="inbox-pane">
    <div v-if="!task" class="flex flex-center q-pa-xl">
      <div v-if="loadFailed" class="pane-message" data-test="inbox-pane-error">
        <div class="text-subtitle1">작업을 읽지 못했습니다 — 잠시 뒤 다시 시도합니다</div>
        <q-btn
          outline
          no-caps
          color="primary"
          icon-right="open_in_new"
          label="상세 열기"
          :href="detailHref"
          target="_blank"
          rel="noopener"
          data-test="inbox-open-detail"
        />
      </div>
      <q-spinner-dots v-else color="primary" size="2em" />
    </div>
    <template v-else>
      <div class="inbox-pane__head">
        <div v-if="!fill" class="row items-start no-wrap pane-title-row">
          <div class="col pane-title"><span class="pane-id">#{{ task.id }}</span> {{ task.title }}</div>
          <q-btn flat dense no-caps icon="chevron_left" label="이전" :disable="!canPrev" data-test="inbox-prev" @click="emit('navigate', -1)" />
          <q-btn flat dense no-caps icon-right="chevron_right" label="다음" :disable="!canNext" data-test="inbox-next" @click="emit('navigate', 1)" />
        </div>
        <div class="row items-center pane-meta">
          <span class="pane-chip" :style="{ background: chip[0], color: chip[1] }" data-test="inbox-pane-status">{{ task.statusLabel }}</span>
          <span v-if="task.taskType" class="pane-chip pane-chip--type">{{ taskTypeLabel(task.taskType) }}</span>
          <span>{{ task.repoAlias ?? task.githubRepo }} · {{ task.githubBranch }}</span>
          <span>요청자 {{ task.requesterId }}</span>
          <span>{{ ageOf(task.updatedAt) }} 대기</span>
          <q-space />
          <q-btn
            flat
            dense
            no-caps
            color="primary"
            icon-right="open_in_new"
            label="상세 열기"
            :href="detailHref"
            target="_blank"
            rel="noopener"
            data-test="inbox-open-detail"
          />
        </div>
        <q-expansion-item dense dense-toggle label="등록 설명·첨부" class="pane-desc" data-test="inbox-desc">
          <div class="pane-desc__body">
            <div class="md-scroll">
              <pre style="white-space: pre-wrap; margin: 0">{{ task.description }}</pre>
            </div>
            <div v-if="task.attachments && task.attachments.length" class="q-mt-sm">
              <q-chip
                v-for="a in task.attachments"
                :key="a.id"
                clickable
                color="blue-grey-1"
                text-color="blue-grey-9"
                icon="attach_file"
                size="sm"
                dense
                :label="`${a.fileName} (${formatSize(a.sizeBytes)})`"
                class="q-mr-xs q-mb-xs"
                @click="downloadAttachment(a)"
              >
                <q-tooltip>클릭하여 다운로드</q-tooltip>
              </q-chip>
            </div>
          </div>
        </q-expansion-item>
        <div v-if="headNext" class="pane-next" data-test="inbox-next-action">{{ headNext }}</div>
        <div v-if="failureText" class="md-scroll pane-failure" data-test="inbox-failure">
          <div class="text-caption text-negative">실패 사유</div>
          <pre style="white-space: pre-wrap; margin: 0">{{ failureText }}</pre>
        </div>
      </div>

      <div
        class="inbox-pane__body"
        :class="fill ? (body === 'interview' ? 'col column no-wrap' : 'col scroll') : ''"
      >
        <template v-if="body === 'interview'">
          <div v-if="task.status === 'INTERVIEWING'" class="pane-working" data-test="inbox-agent-working">에이전트가 답을 만드는 중입니다</div>
          <div v-if="showRefRow" class="row items-end no-wrap task-refs pane-refs" data-test="inbox-ref-row">
            <RefRepoChips :items="task.refRepos ?? []" :context="taskRefContext(task.status)" class="task-refs-chips" />
            <TaskRefChangeMenu
              v-if="canChangeRefs"
              v-model="pickedRefs"
              :server-refs="task.refRepos ?? []"
              :target-catalog-id="task.repoCatalogId ?? null"
              :disable="interviewLive !== 'AWAITING_INPUT'"
            />
          </div>
          <div :class="fill ? 'col column no-wrap' : 'pane-panel'">
            <InterviewPanel
              :key="task.interviewSessionId!"
              :session-id="task.interviewSessionId!"
              :readonly="!auth.isAdmin"
              :fill="fill"
              :answer-ref-repos="answerRefs"
              :session-ref-repos="task.refRepos ?? []"
              :draft="draftOf(task.interviewSessionId!)"
              @update:draft="saveDraft(task.interviewSessionId!, $event)"
              @answered="emitDone()"
              @confirmed="onFormDone"
              @close="refresh"
              @status="onStatus"
            />
          </div>
        </template>

        <template v-else-if="body === 'approve'">
          <q-card flat bordered>
            <ApproveForm :key="task.id" :task="task" context="inbox" @approved="onFormDone" @update:approving="approving = $event" />
          </q-card>
          <q-btn
            flat
            dense
            no-caps
            color="grey-8"
            icon="block"
            label="작업 취소"
            class="q-mt-sm"
            :disable="approving"
            data-test="inbox-cancel-task"
            @click="cancelTask"
          />
        </template>

        <InboxGateActions v-else-if="body === 'gate'" :task="task" :actions="actions" data-test="inbox-body-gate" @done="onFormDone" />

        <InboxFailureActions
          v-else-if="body === 'failure'"
          :task="task"
          :actions="actions"
          data-test="inbox-body-failure"
          @done="emitDone()"
          @open="openDialog"
        />

        <div v-else class="pane-message" data-test="inbox-body-idle">
          <div class="text-subtitle1">지금 처리할 것이 없습니다(현재: {{ task.statusLabel }})</div>
          <div v-if="nextText" class="text-grey-8">{{ nextText }}</div>
          <div class="row q-gutter-sm">
            <q-btn outline no-caps color="primary" icon-right="open_in_new" label="상세 열기" :href="detailHref" target="_blank" rel="noopener" />
            <q-btn
              unelevated
              no-caps
              color="primary"
              icon-right="chevron_right"
              label="다음 건"
              :disable="!canNext"
              data-test="inbox-idle-next"
              @click="emit('navigate', 1)"
            />
          </div>
        </div>
      </div>

      <ImplementationRetryDialog
        v-model="implRetryOpen"
        :task-id="String(task.id)"
        :current-mode="task.fix?.activeRound != null ? (task.fix.activeMode ?? 'REVIEW') : (task.implementationMode ?? 'DIRECT')"
        :ref-repos="task.refRepos ?? []"
        :failure-reason="task.failureReason"
        :fix-round="task.fix?.activeRound ?? null"
        :fix-kind="task.fix?.activeKind ?? null"
        @submitted="onDialogSubmitted"
      />
      <FixRequestDialog
        v-model="fixRequestOpen"
        :task-id="String(task.id)"
        :is-admin="auth.isAdmin"
        :next-round="(task.fix?.approvedRounds ?? 0) + 1"
        :max-rounds="task.fix?.maxRounds ?? 0"
        @submitted="onDialogSubmitted"
      />
      <DeployEnvDialog
        v-model="deployEnvOpen"
        :task-id="String(task.id)"
        mode="redeploy"
        :saved-env-vars="task.envVars ?? []"
        @submitted="onDialogSubmitted"
      />
    </template>
  </div>
</template>

<style scoped>
.inbox-pane--fill {
  min-height: 0;
}
.inbox-pane__head {
  flex: none;
  padding: 12px 16px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.inbox-pane__body {
  padding: 0 16px 16px;
  min-height: 0;
}
.inbox-pane--fill .inbox-pane__body {
  padding: 0 0 8px;
}
.pane-title-row {
  gap: 4px;
}
.pane-title {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.35;
  min-width: 0;
  overflow-wrap: anywhere;
}
.pane-id {
  color: #757575;
}
.pane-meta {
  gap: 4px 10px;
  font-size: 13px;
  color: #616161;
}
.pane-chip {
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 4px;
  white-space: nowrap;
}
.pane-chip--type {
  color: #455a64;
  background: rgba(69, 90, 100, 0.1);
}
.pane-desc {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 4px;
}
/* 긴 설명이 처리 본문(특히 폰의 대화 패널)을 밀어내지 않게 펼친 내용만 스크롤 */
.pane-desc__body {
  max-height: 40vh;
  overflow-y: auto;
  padding: 8px 12px;
}
.pane-next {
  font-size: 14px;
  color: #424242;
}
.pane-failure pre {
  color: #c62828;
}
.pane-working {
  flex: none;
  padding: 8px 12px;
  margin-bottom: 8px;
  border-radius: 4px;
  background: #e1f5fe;
  color: #01579b;
  font-size: 14px;
}
.inbox-pane--fill .pane-working {
  margin: 0 0 4px;
  border-radius: 0;
}
.pane-refs {
  flex: none;
  padding: 4px 0 8px;
}
.inbox-pane--fill .pane-refs {
  padding: 4px 16px 8px;
}
.pane-panel {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 4px;
  overflow: hidden;
}
.pane-message {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  padding: 16px 0;
}
.inbox-pane--fill .pane-message {
  padding: 16px;
}
/* 참조 repo 줄: 칩 줄은 내용 폭만큼만 차지하고 "✎ 참조 변경"이 칩 바로 옆에 붙는다(작업 상세와 같은 규칙) */
.task-refs {
  gap: 8px;
}
.task-refs-chips {
  flex: 0 1 auto;
  min-width: 0;
}
</style>
