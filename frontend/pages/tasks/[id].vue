<script setup lang="ts">
import { useQuasar } from 'quasar'
import ApproveDialog from '~/components/ApproveDialog.vue'
import InterviewPanel from '~/components/InterviewPanel.vue'
import InterviewHistoryCard from '~/components/InterviewHistoryCard.vue'
import TaskProgressStepper from '~/components/tasks/TaskProgressStepper.vue'
import TaskNextAction from '~/components/tasks/TaskNextAction.vue'
import TaskDetailMobile from '~/components/tasks/TaskDetailMobile.vue'
import TaskHistoryTimeline from '~/components/tasks/TaskHistoryTimeline.vue'
import { stageSteps, nextAction, ageOf, type NextActionKind } from '~/composables/taskStages'
import { renderMarkdown } from '~/composables/useMarkdown'

definePageMeta({ layout: 'default' })

interface AnalysisView {
  markdownResult: string
  subtasksJson: string
  durationMs: number | null
  approved: boolean
  approvedBy: number | null
  approvedAt: string | null
  completedAt: string
}

interface TaskMcpSpec {
  name: string
  url: string
  transport: string
}

interface ImplementationView {
  prUrl: string | null
  prNumber: number | null
  headBranch: string | null
  headSha: string | null
  implementationLog: string | null
}

interface DesignView {
  designMarkdown: string
  mockupFilesJson: string
  designProjectId: string | null
  designUrl: string | null
  rejectCount: number
  feedbackHistoryJson: string
  approved: boolean
  approvedBy: string | null
  approvedAt: string | null
  completedAt: string
}

interface DeploymentView {
  deployUrl: string | null
  deployHostPort: number | null
  deployImage: string | null
  deployedAt: string | null
  deployLog: string | null
}

interface EnvVar {
  key: string
  value: string
  secret: boolean
}

interface AttachmentMeta {
  id: number
  fileName: string
  contentType: string | null
  sizeBytes: number
  createdAt: string
}

interface StageUsageView {
  stage: string
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}

interface TaskResponse {
  id: number
  githubRepo: string
  repoAlias: string | null
  githubBranch: string
  title: string
  description: string
  status: string
  statusLabel: string
  requesterId: string
  retryCount: number
  maxRetry: number
  failureReason: string | null
  mcpsExtra: TaskMcpSpec[]
  envVars: EnvVar[]
  interviewSessionId: number | null
  createdAt: string
  updatedAt: string
  model: string
  effort: string
  designRequested: boolean
  analysis: AnalysisView | null
  design: DesignView | null
  implementation: ImplementationView | null
  deployment: DeploymentView | null
  attachments: AttachmentMeta[]
  stageUsage: StageUsageView[]
  totalCostUsd: number | null
  totalTokens: number | null
}

const route = useRoute()
const $q = useQuasar()
const auth = useAuthStore()
const taskId = computed(() => route.params.id as string)

const { data: task, refresh } = useTaskPolling<TaskResponse>(() =>
  useApi(`/api/tasks/${taskId.value}`),
)

const subtasks = computed(() => {
  if (!task.value?.analysis?.subtasksJson) return []
  try {
    return JSON.parse(task.value.analysis.subtasksJson) as Array<
      Record<string, string>
    >
  } catch {
    return []
  }
})

const usageByStage = computed<Record<string, StageUsageView>>(() =>
  Object.fromEntries((task.value?.stageUsage ?? []).map((u) => [u.stage, u])),
)

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
function fmtCost(c: number): string {
  return '$' + (c > 0 && c < 0.01 ? c.toFixed(4) : c.toFixed(2))
}

async function approve() {
  if (
    !confirm(
      '이 분석 결과를 승인하시겠습니까?\n승인 즉시 워커가 worktree에서 구현 + Draft PR 생성합니다.',
    )
  )
    return
  try {
    await useApi(`/api/tasks/${taskId.value}/approve`, { method: 'POST' })
    $q.notify({
      type: 'positive',
      message: '승인 완료 — 구현 큐에 진입했습니다',
    })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  }
}

const showApprove = ref(false)

function openApprove() {
  showApprove.value = true
}

// q-expansion-item은 접힌 상태에서도 콘텐츠를 always-mount(v-show)하므로, 진행 이력 카드는
// historyOpen을 별도로 두고 v-if로 TaskHistoryTimeline을 게이팅해 첫 펼침 때만 조회한다.
const historyOpen = ref(false)
// 모바일 아코디언 헤더에 건수를 보여주기 위한 값 — TaskHistoryTimeline의 loaded(count)를 받는다.
const historyCount = ref<number | null>(null)

// 승인대기 취소: 요청자 본인 또는 admin — getForView가 이미 조회 시점에 두 경우만
// 통과시키므로(그 외 403) 별도 소유권 가드 없이 재시도 버튼과 동일한 패턴을 따른다.
async function cancelTask() {
  if (!confirm('이 작업을 취소하시겠습니까?')) return
  try {
    await useApi(`/api/tasks/${taskId.value}/cancel`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '작업이 취소되었습니다' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '취소 실패' })
  }
}

// 승인 직후 작업을 다시 읽으면 대화형 분석 카드(#interview-card)가 생긴다 — 관리자가 바로 대화 위치를 보도록
// 스크롤까지 이어 준다(승인 팝업 UI/UX 개선 2026-09-06). useTaskPolling.refresh()는 진행 중 폴링이 있어도
// 그 뒤에 한 번 더 가져와 호출 시점 이후 데이터를 보장하므로, 스크롤 시점엔 카드가 있다.
async function onApproved() {
  await refresh()
  await nextTick()
  scrollTo('interview-card')
}

function onInterviewConfirmed() {
  refresh()
}

const isInterviewPhase = computed(() =>
  ['INTERVIEWING', 'INTERVIEW_INPUT', 'INTERVIEW_REVIEW'].includes(task.value?.status ?? ''),
)

// 편집용 행: 와이어 포맷(EnvVar)에 UI 전용 상태(reveal) + 안정적 key(id)를 더한다.
// id는 v-for의 stable key로 써서 행 삭제 시 입력/마스킹 상태가 어긋나지 않게 한다.
interface EnvRow extends EnvVar {
  id: number
  reveal: boolean
}

const envDialog = ref(false)
const envMode = ref<'deploy' | 'redeploy'>('deploy')
const envRows = ref<EnvRow[]>([])
let envRowSeq = 0

function openDeployDialog(mode: 'deploy' | 'redeploy') {
  envMode.value = mode
  envRows.value = (task.value?.envVars ?? []).map((e) => ({
    ...e,
    id: envRowSeq++,
    reveal: false,
  }))
  envDialog.value = true
}

function addEnvRow() {
  envRows.value.push({
    key: '',
    value: '',
    secret: false,
    id: envRowSeq++,
    reveal: false,
  })
}

function removeEnvRow(id: number) {
  envRows.value = envRows.value.filter((r) => r.id !== id)
}

async function submitDeploy() {
  const endpoint = envMode.value === 'deploy' ? 'deploy' : 'redeploy'
  // UI 전용 필드(id/reveal)는 제외하고 와이어 포맷만 전송.
  const envVars = envRows.value
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '')
  try {
    await useApi(`/api/tasks/${taskId.value}/${endpoint}`, {
      method: 'POST',
      body: { envVars },
    })
    $q.notify({
      type: 'positive',
      message: envMode.value === 'deploy' ? '배포 큐 등록' : '재배포 큐 등록',
    })
    envDialog.value = false
    refresh()
  } catch (e: any) {
    $q.notify({
      type: 'negative',
      message:
        e?.data?.message ??
        (envMode.value === 'deploy' ? '배포 실패' : '재배포 실패'),
    })
  }
}

function undeploy() {
  $q.dialog({
    title: '배포 중지',
    message: '실행 중인 컨테이너를 중지하고 제거합니다. 계속할까요?',
    cancel: true,
    persistent: true,
  }).onOk(async () => {
    try {
      await useApi(`/api/tasks/${taskId.value}/undeploy`, { method: 'POST' })
      $q.notify({ type: 'positive', message: '배포 중지 요청 등록' })
      refresh()
    } catch (e: any) {
      $q.notify({
        type: 'negative',
        message: e?.data?.message ?? '배포 중지 실패',
      })
    }
  })
}

async function retry() {
  try {
    await useApi(`/api/tasks/${taskId.value}/retry`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '재시도 큐 등록' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '재시도 실패' })
  }
}

const liveLog = ref('')
let logSource: EventSource | null = null

function closeLog() {
  if (logSource) {
    logSource.close()
    logSource = null
  }
}

function openLog() {
  closeLog()
  liveLog.value = ''
  const token = auth.accessToken
  if (!token) return
  const url = `/api/tasks/${taskId.value}/logs/stream?access_token=${encodeURIComponent(token)}`
  logSource = new EventSource(url)
  logSource.addEventListener('log', (e) => {
    liveLog.value += (e as MessageEvent).data + '\n'
  })
  logSource.addEventListener('done', () => closeLog())
  logSource.onerror = () => closeLog()
}

// in-flight 진입 시 SSE 열고, 벗어나면 닫는다.
watch(
  () => task.value?.status,
  (s) => {
    if (s === 'DEPLOYING' || s === 'UNDEPLOYING') openLog()
    else closeLog()
  },
)

onUnmounted(() => closeLog())

function statusClass(status: string) {
  return (
    {
      AWAITING_APPROVAL: 'status-chip status-pending',
      INTERVIEWING: 'status-chip status-in-progress',
      INTERVIEW_INPUT: 'status-chip status-pending',
      INTERVIEW_REVIEW: 'status-chip status-completed',
      PENDING: 'status-chip status-pending',
      IN_PROGRESS: 'status-chip status-in-progress',
      COMPLETED: 'status-chip status-completed',
      FAILED: 'status-chip status-failed',
      APPROVED: 'status-chip status-approved',
      IMPLEMENTING: 'status-chip status-implementing',
      PR_CREATED: 'status-chip status-pr-created',
      IMPLEMENTATION_FAILED: 'status-chip status-impl-failed',
      DEPLOY_PENDING: 'status-chip status-deploy-pending',
      DEPLOYING: 'status-chip status-deploying',
      DEPLOYED: 'status-chip status-deployed',
      DEPLOY_FAILED: 'status-chip status-deploy-failed',
      DEPLOY_LOST: 'status-chip status-deploy-lost',
      UNDEPLOY_PENDING: 'status-chip status-deploying',
      UNDEPLOYING: 'status-chip status-deploying',
      DESIGN_PENDING: 'status-chip status-approved',
      DESIGNING: 'status-chip status-implementing',
      DESIGN_REVIEW: 'status-chip status-completed',
      DESIGN_FAILED: 'status-chip status-failed',
      CANCELLED: 'status-chip status-cancelled',
    }[status] || 'status-chip'
  )
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}

// 반드시 useApi 경유 — 전역 $fetch는 Authorization 미첨부로 401 (스펙 §8.2).
// 파일명은 응답 헤더가 아니라 메타 fileName 사용 ($fetch는 헤더를 안 돌려준다).
// 파라미터 타입은 실제로 쓰는 필드(id/fileName)만 요구하는 부분집합 — 데스크톱 칩(a: AttachmentMeta)과
// 모바일 TaskDetailMobile의 download emit({id,fileName,sizeBytes}) 양쪽에서 그대로 바인딩할 수 있게 한다.
async function downloadAttachment(att: Pick<AttachmentMeta, 'id' | 'fileName'>) {
  try {
    const blob = await useApi<Blob>(`/api/tasks/${taskId.value}/attachments/${att.id}`, {
      responseType: 'blob',
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = att.fileName
    document.body.appendChild(a)
    a.click()
    // blob URL을 click과 같은 tick에 해제하면 일부 브라우저에서 다운로드가 시작 전에 중단된다(Chromium 41380177, Firefox 1282407).
    setTimeout(() => {
      URL.revokeObjectURL(url)
      a.remove()
    }, 0)
  } catch (e: any) {
    // responseType:'blob'이면 ofetch가 에러 본문도 Blob으로 파싱하므로 e.data는 {message}가 아니라 Blob이다.
    let message = '다운로드 실패'
    try {
      if (e?.data instanceof Blob) {
        const parsed = JSON.parse(await e.data.text())
        if (parsed?.message) message = parsed.message
      } else if (e?.data?.message) {
        message = e.data.message
      }
    } catch {
      // 본문이 JSON이 아니면 fallback 유지
    }
    $q.notify({ type: 'negative', message })
  }
}

// 진행 스테퍼 + 다음 할 일 배너 (스펙 2026-09-06 §4.2, 결정 6: 데스크톱도 노출) — 데스크톱/모바일 공용.
const steps = computed(() => (task.value ? stageSteps(task.value) : []))
const action = computed(() => (task.value ? nextAction(task.value, auth.isAdmin) : null))
const stepCaption = computed(() => {
  const t = task.value
  if (!t) return ''
  const parts = [t.statusLabel]
  if (t.implementation?.prNumber) parts.push(`PR #${t.implementation.prNumber}`)
  parts.push(`${ageOf(t.updatedAt)} 전 갱신`)
  return parts.join(' · ')
})
const analysisHtml = computed(() => renderMarkdown(task.value?.analysis?.markdownResult))

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function onAction(kind: NextActionKind) {
  const t = task.value
  if (!t) return
  switch (kind) {
    case 'approve-interview': return openApprove()
    case 'approve-impl': return approve()
    case 'deploy': return openDeployDialog('deploy')
    case 'redeploy': return openDeployDialog('redeploy')
    case 'retry': return retry()
    case 'open-pr': return void window.open(t.implementation?.prUrl ?? '', '_blank', 'noopener')
    case 'open-url': return void window.open(t.deployment?.deployUrl ?? '', '_blank', 'noopener')
    case 'open-interview': return scrollTo('interview-card')
    case 'review-design': return scrollTo('design-card')
  }
}
</script>

<template>
  <q-page padding :style="$q.screen.lt.md ? 'padding-bottom: 88px' : ''">
    <div v-if="!task" class="flex flex-center q-pa-xl">
      <q-spinner-dots color="primary" size="3em" />
    </div>
    <template v-else>
      <q-breadcrumbs class="q-mb-md">
        <q-breadcrumbs-el to="/tasks" label="작업 목록" />
        <q-breadcrumbs-el :label="`#${task.id}`" />
      </q-breadcrumbs>

      <div class="row items-center q-mb-md">
        <div class="text-h5">{{ task.title }}</div>
        <q-space />
        <span :class="statusClass(task.status)">{{ task.statusLabel }}</span>
        <!-- 모델/effort/비용 칩은 데스크톱 전용 — 모바일에서는 정보 섹션(section-info)이 대신 보여준다 -->
        <div v-if="!$q.screen.lt.md" class="row items-center conv-header-chips">
          <q-chip dense size="sm" outline icon="smart_toy" :label="task.model" class="q-ml-sm" />
          <q-chip dense size="sm" outline icon="tune" :label="task.effort" />
          <q-chip v-if="task.totalCostUsd != null" dense size="sm" outline
                  icon="paid" color="primary"
                  :label="`${fmtTokens(task.totalTokens ?? 0)} 토큰 · ${fmtCost(task.totalCostUsd)}`" />
        </div>
      </div>

      <q-card flat bordered class="q-mb-md" data-test="progress-card">
        <div :class="$q.screen.lt.md ? 'column' : 'row items-stretch no-wrap'">
          <div class="col q-pa-md">
            <TaskProgressStepper :steps="steps" :caption="stepCaption" />
            <!-- 모바일 전용: 하단 액션 바 옆에 실패 사유가 없으면 안 보이므로 여기 1줄로 노출한다
                 (리뷰 파인딩 5). 데스크톱은 기존 실패 사유 카드/배포실패 배너가 이미 있으므로
                 $q.screen.lt.md로 게이트해 중복 표시를 막는다(리뷰 파인딩 5 후속). -->
            <div
              v-if="$q.screen.lt.md && task.failureReason && steps.some((s) => s.state === 'failed')"
              class="text-negative text-caption ellipsis q-mt-xs"
              data-test="failure-line"
            >
              {{ task.failureReason }}
            </div>
          </div>
          <q-separator :vertical="!$q.screen.lt.md" />
          <div :class="$q.screen.lt.md ? '' : 'col-4'">
            <!-- 모바일은 하단 액션 바(variant="bar")가 이미 같은 주 행동을 노출하므로 배너 쪽은
                 문구만 남긴다 — 중복 노출 정리 (리뷰 파인딩 1) -->
            <TaskNextAction :action="action" variant="banner" :hide-primary="$q.screen.lt.md" @act="onAction">
              <template #extra>
                <q-btn v-if="auth.isAdmin && task.implementation?.prUrl" outline color="primary" icon="open_in_new" :label="`PR #${task.implementation.prNumber} 열기`" :href="task.implementation.prUrl" target="_blank" />
              </template>
            </TaskNextAction>
          </div>
        </div>
      </q-card>

      <template v-if="$q.screen.lt.md">
        <TaskDetailMobile
          :task="task"
          :is-admin="auth.isAdmin"
          :steps="steps"
          :history-count="historyCount"
          @retry="retry"
          @download="downloadAttachment"
        >
          <template #history>
            <TaskHistoryTimeline :task-id="task.id" @loaded="historyCount = $event" />
          </template>
          <template #interviews>
            <InterviewHistoryCard :task-id="task.id" :task-status="task.status" />
          </template>
          <template v-if="task.design" #design>
            <div id="design-card">
              <DesignReviewCard
                :task-id="task.id"
                :status="task.status"
                :design="task.design"
                :is-admin="auth.isAdmin"
                @refresh="refresh"
              />
            </div>
          </template>
        </TaskDetailMobile>
        <q-card v-if="isInterviewPhase && task.interviewSessionId" id="interview-card" flat bordered class="q-mt-md">
          <q-card-section class="text-h6">대화형 분석</q-card-section>
          <q-separator />
          <q-card-section class="q-pa-none">
            <InterviewPanel
              :session-id="task.interviewSessionId"
              :readonly="!auth.isAdmin"
              @confirmed="onInterviewConfirmed"
              @close="refresh"
            />
          </q-card-section>
        </q-card>
        <TaskNextAction :action="action" variant="bar" @act="onAction">
          <template #extra>
            <q-btn outline color="grey-7" icon="more_vert" aria-label="더 보기" class="bar-more" data-test="bar-more">
              <q-menu anchor="top right" self="bottom right">
                <q-list style="min-width: 200px">
                  <q-item v-if="task.implementation?.prUrl" v-close-popup clickable tag="a" :href="task.implementation.prUrl" target="_blank" rel="noopener" data-test="bar-more-pr">
                    <q-item-section avatar><q-icon name="open_in_new" /></q-item-section>
                    <q-item-section>PR #{{ task.implementation.prNumber }} 열기</q-item-section>
                  </q-item>
                  <q-item v-if="task.deployment?.deployUrl" v-close-popup clickable tag="a" :href="task.deployment.deployUrl" target="_blank" rel="noopener" data-test="bar-more-url">
                    <q-item-section avatar><q-icon name="open_in_new" /></q-item-section>
                    <q-item-section>접속 URL 열기</q-item-section>
                  </q-item>
                  <!-- 관리자 재배포/중지 — 모바일 상세에는 데스크톱 배포 카드의 버튼이 없어 여기로만 접근 가능했다 (리뷰 파인딩 4) -->
                  <q-item v-if="auth.isAdmin && ['DEPLOYED','DEPLOY_FAILED','DEPLOY_LOST'].includes(task.status)" v-close-popup clickable data-test="bar-more-redeploy" @click="openDeployDialog('redeploy')">
                    <q-item-section avatar><q-icon name="refresh" /></q-item-section>
                    <q-item-section>재배포</q-item-section>
                  </q-item>
                  <q-item v-if="auth.isAdmin && ['DEPLOYED','DEPLOY_LOST'].includes(task.status)" v-close-popup clickable class="text-negative" data-test="bar-more-undeploy" @click="undeploy">
                    <q-item-section avatar><q-icon name="stop" color="negative" /></q-item-section>
                    <q-item-section>중지</q-item-section>
                  </q-item>
                  <q-item v-if="isInterviewPhase && task.interviewSessionId" v-close-popup clickable data-test="bar-more-interview" @click="onAction('open-interview')">
                    <q-item-section avatar><q-icon name="forum" /></q-item-section>
                    <q-item-section>대화 열기</q-item-section>
                  </q-item>
                  <q-item v-if="task.status === 'FAILED' || task.status === 'DESIGN_FAILED'" v-close-popup clickable data-test="bar-more-retry" @click="retry">
                    <q-item-section avatar><q-icon name="refresh" /></q-item-section>
                    <q-item-section>재시도</q-item-section>
                  </q-item>
                  <q-item v-if="task.status === 'AWAITING_APPROVAL' || task.status === 'PENDING'" v-close-popup clickable class="text-warning" data-test="bar-more-cancel" @click="cancelTask">
                    <q-item-section avatar><q-icon name="block" color="warning" /></q-item-section>
                    <q-item-section>취소</q-item-section>
                  </q-item>
                </q-list>
              </q-menu>
            </q-btn>
          </template>
        </TaskNextAction>
      </template>
      <template v-else>
      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-caption">레포</div>
          <div>
            {{ task.repoAlias ?? task.githubRepo }}
            <q-chip dense size="sm" :label="task.githubBranch" />
          </div>
        </q-card-section>
        <q-separator />
        <q-card-section>
          <div class="text-caption">요청 상세</div>
          <div class="md-scroll">
            <pre style="white-space: pre-wrap; margin: 0">{{ task.description }}</pre>
          </div>
        </q-card-section>
        <q-separator v-if="task.mcpsExtra && task.mcpsExtra.length" />
        <q-card-section v-if="task.mcpsExtra && task.mcpsExtra.length">
          <div class="text-caption q-mb-xs">
            분석에 주입된 추가 MCP (등록 시점 스냅샷)
          </div>
          <q-chip
            v-for="m in task.mcpsExtra"
            :key="m.name"
            color="indigo-1"
            text-color="indigo-9"
            icon="extension"
            size="sm"
            dense
            :label="`${m.name} (${m.transport})`"
            class="q-mr-xs q-mb-xs"
          >
            <q-tooltip>{{ m.url }}</q-tooltip>
          </q-chip>
        </q-card-section>
        <q-separator v-if="task.attachments && task.attachments.length" />
        <q-card-section v-if="task.attachments && task.attachments.length">
          <div class="text-caption q-mb-xs">첨부파일</div>
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
        </q-card-section>
        <q-separator />
        <q-card-section v-if="task.failureReason || task.status === 'DESIGN_FAILED'">
          <div class="text-caption text-negative">실패 사유</div>
          <div class="md-scroll">
            <pre style="white-space: pre-wrap; color: #c62828; margin: 0">{{
              task.failureReason
            }}</pre>
          </div>
          <!-- 재시도는 백엔드가 분석실패(FAILED)/디자인실패(DESIGN_FAILED)만 허용 — 그 외 실패 상태는 버튼 비노출.
               디자인 재시도는 retryCount를 소비하지 않으므로(admin 수동 조작) 한도 비활성/카운트 표시 제외 -->
          <q-btn
            v-if="task.status === 'FAILED' || task.status === 'DESIGN_FAILED'"
            unelevated
            color="warning"
            icon="refresh"
            :label="
              task.status === 'DESIGN_FAILED'
                ? '재시도'
                : `재시도 (${task.retryCount}/${task.maxRetry})`
            "
            :disable="
              task.status !== 'DESIGN_FAILED' && task.retryCount >= task.maxRetry
            "
            @click="retry"
          />
        </q-card-section>
      </q-card>

      <q-card v-if="task.status === 'AWAITING_APPROVAL'" flat bordered class="q-mb-md">
        <q-card-section class="row items-center">
          <div class="text-h6">승인 대기</div>
          <q-space />
          <q-btn
            data-test="cancel-task"
            flat
            dense
            color="warning"
            icon="block"
            label="취소"
            class="q-mr-sm"
            @click="cancelTask"
          />
          <q-btn
            v-if="auth.isAdmin"
            data-test="approve"
            unelevated
            color="primary"
            icon="forum"
            label="승인 — 인터뷰 시작"
            @click="openApprove"
          />
        </q-card-section>
        <q-separator />
        <q-card-section class="text-grey-8">
          관리자가 승인하면 대화형 분석(인터뷰)이 시작됩니다. 승인 전에는 워커 자원을 쓰지 않습니다.
        </q-card-section>
      </q-card>

      <q-card v-if="isInterviewPhase && task.interviewSessionId" id="interview-card" flat bordered class="q-mb-md">
        <q-card-section class="text-h6">대화형 분석</q-card-section>
        <q-separator />
        <q-card-section class="q-pa-none">
          <InterviewPanel
            :session-id="task.interviewSessionId"
            :readonly="!auth.isAdmin"
            @confirmed="onInterviewConfirmed"
            @close="refresh"
          />
        </q-card-section>
      </q-card>

      <!-- INTERVIEW 단계 usage chip — isInterviewPhase 카드는 인터뷰 중에만 존재하므로
           (완료 후 영구 소멸) 여기, 상태와 무관하게 항상 렌더되는 영속 anchor에 배치한다. -->
      <div v-if="usageByStage['INTERVIEW']" class="row items-center q-mb-xs">
        <q-chip dense size="sm" outline icon="bolt"
                :label="`인터뷰 ${fmtTokens(usageByStage['INTERVIEW'].inputTokens)} 입력 · ${fmtTokens(usageByStage['INTERVIEW'].outputTokens)} 출력 · ${fmtCost(usageByStage['INTERVIEW'].costUsd)}`">
          <q-tooltip>
            캐시 생성 {{ fmtTokens(usageByStage['INTERVIEW'].cacheCreationTokens) }} ·
            캐시 읽기 {{ fmtTokens(usageByStage['INTERVIEW'].cacheReadTokens) }}
          </q-tooltip>
        </q-chip>
      </div>

      <!-- 지난 인터뷰 이력 — 인터뷰 phase 여부와 무관하게 항상 마운트 (카드 스스로 숨김 판단) -->
      <InterviewHistoryCard :task-id="task.id" :task-status="task.status" />

      <!-- 구현 결과 카드 (PR 생성 또는 구현 실패 시 노출) -->
      <q-card
        v-if="task.implementation"
        flat
        bordered
        class="q-mb-md"
        :class="task.status === 'PR_CREATED' ? 'bg-green-1' : 'bg-red-1'"
      >
        <q-card-section class="row items-center">
          <div class="text-h6">
            <q-icon
              :name="task.status === 'PR_CREATED' ? 'merge_type' : 'error'"
              :color="task.status === 'PR_CREATED' ? 'positive' : 'negative'"
              class="q-mr-sm"
            />
            구현 결과
          </div>
          <q-space />
          <q-chip v-if="usageByStage['IMPLEMENTATION']" dense size="sm" outline icon="bolt"
                  class="q-mr-sm"
                  :label="`${fmtTokens(usageByStage['IMPLEMENTATION'].inputTokens)} 입력 · ${fmtTokens(usageByStage['IMPLEMENTATION'].outputTokens)} 출력 · ${fmtCost(usageByStage['IMPLEMENTATION'].costUsd)}`">
            <q-tooltip>
              캐시 생성 {{ fmtTokens(usageByStage['IMPLEMENTATION'].cacheCreationTokens) }} ·
              캐시 읽기 {{ fmtTokens(usageByStage['IMPLEMENTATION'].cacheReadTokens) }}
            </q-tooltip>
          </q-chip>
          <q-btn
            v-if="task.implementation.prUrl"
            unelevated
            color="primary"
            icon="open_in_new"
            :label="`PR #${task.implementation.prNumber}`"
            :href="task.implementation.prUrl"
            target="_blank"
          />
        </q-card-section>
        <q-separator />
        <q-card-section v-if="task.implementation.prUrl">
          <div class="text-caption">PR URL</div>
          <a :href="task.implementation.prUrl" target="_blank">{{
            task.implementation.prUrl
          }}</a>
          <div class="text-caption q-mt-sm">브랜치</div>
          <code>{{ task.implementation.headBranch }}</code>
          <span v-if="task.implementation.headSha" class="text-caption q-ml-sm">
            @ {{ task.implementation.headSha.slice(0, 7) }}
          </span>
        </q-card-section>
        <q-card-section v-else-if="task.implementation.headBranch">
          <div class="text-caption text-negative">
            구현 실패 — 일부 진행됨 (브랜치까지)
          </div>
          <div>
            브랜치: <code>{{ task.implementation.headBranch }}</code>
            <span
              v-if="task.implementation.headSha"
              class="text-caption q-ml-sm"
            >
              @ {{ task.implementation.headSha.slice(0, 7) }}
            </span>
          </div>
        </q-card-section>
        <q-expansion-item
          v-if="task.implementation.implementationLog"
          icon="terminal"
          label="구현 로그 (디버그)"
          header-class="text-grey-9"
          dense
        >
          <q-card-section>
            <div class="md-scroll">
              <pre
                style="
                  white-space: pre-wrap;
                  max-height: 400px;
                  overflow: auto;
                  font-size: 11px;
                  font-family: 'Menlo', monospace;
                  margin: 0;
                "
                >{{ task.implementation.implementationLog }}</pre
              >
            </div>
          </q-card-section>
        </q-expansion-item>
      </q-card>

      <!-- 배포 카드 -->
      <q-card
        v-if="task.status === 'PR_CREATED' || task.deployment"
        flat
        bordered
        class="q-mb-md"
      >
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="rocket_launch" color="primary" size="sm" />
          <div class="text-h6">배포</div>
          <q-space />
          <q-btn
            v-if="auth.isAdmin && task.status === 'PR_CREATED'"
            unelevated
            color="primary"
            icon="rocket_launch"
            label="배포"
            @click="openDeployDialog('deploy')"
          />
          <q-chip
            v-if="
              task.status === 'DEPLOYING' ||
              task.status === 'DEPLOY_PENDING' ||
              task.status === 'UNDEPLOY_PENDING' ||
              task.status === 'UNDEPLOYING'
            "
            color="orange"
            text-color="white"
            >{{ task.statusLabel }}</q-chip
          >
          <template
            v-if="
              auth.isAdmin &&
              (task.status === 'DEPLOYED' ||
                task.status === 'DEPLOY_FAILED' ||
                task.status === 'DEPLOY_LOST')
            "
          >
            <q-btn
              unelevated
              color="primary"
              icon="refresh"
              label="재배포"
              @click="openDeployDialog('redeploy')"
            />
            <q-btn
              v-if="task.status === 'DEPLOYED' || task.status === 'DEPLOY_LOST'"
              color="negative"
              icon="stop"
              label="중지"
              outline
              @click="undeploy"
            />
          </template>
        </q-card-section>

        <q-banner
          v-if="task.status === 'DEPLOY_LOST'"
          dense
          class="bg-orange-1 text-orange-9"
        >
          <template #avatar>
            <q-icon name="warning" color="orange" />
          </template>
          배포 컨테이너가 실행 중이 아닙니다 (도커 데몬 재시작 등). 접속 URL이
          응답하지 않으면 재배포하세요. 컨테이너가 다시 살아나면 자동으로
          배포완료로 복귀합니다.
        </q-banner>

        <q-separator v-if="task.deployment && task.deployment.deployUrl" />
        <q-card-section v-if="task.deployment && task.deployment.deployUrl">
          <div class="text-caption text-grey-7">접속 URL</div>
          <a :href="task.deployment.deployUrl" target="_blank" rel="noopener">{{
            task.deployment.deployUrl
          }}</a>
          <div class="text-caption q-mt-xs">
            포트 <code>{{ task.deployment.deployHostPort }}</code> · 이미지
            <code>{{ task.deployment.deployImage }}</code>
            <span v-if="task.deployment.deployedAt">
              ·
              {{ new Date(task.deployment.deployedAt).toLocaleString() }}</span
            >
          </div>
        </q-card-section>

        <q-separator v-if="task.envVars && task.envVars.length" />
        <q-card-section v-if="task.envVars && task.envVars.length">
          <div class="text-caption text-grey-7 q-mb-xs">환경변수</div>
          <div v-for="(e, i) in task.envVars" :key="i" class="text-body2">
            <code>{{ e.key }}</code> =
            <span v-if="e.secret" class="text-grey">••••••</span>
            <code v-else>{{ e.value }}</code>
          </div>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'" />
        <q-card-section v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'">
          <div class="text-caption text-grey-7 q-mb-xs">실시간 로그</div>
          <pre class="deploy-log">{{ liveLog || '로그 대기 중…' }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOY_FAILED'" />
        <q-card-section v-if="task.status === 'DEPLOY_FAILED'">
          <q-banner class="bg-red-1 text-red-9">배포 실패: {{ task.failureReason }}</q-banner>
          <pre v-if="task.deployment && task.deployment.deployLog" class="deploy-log">{{ task.deployment.deployLog }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog" />
        <q-card-section v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog">
          <q-expansion-item dense label="배포 로그" icon="article">
            <pre class="deploy-log">{{ task.deployment.deployLog }}</pre>
          </q-expansion-item>
        </q-card-section>
      </q-card>

      <div v-if="usageByStage['DESIGN']" class="row items-center q-mb-xs">
        <q-chip dense size="sm" outline icon="bolt"
                :label="`${fmtTokens(usageByStage['DESIGN'].inputTokens)} 입력 · ${fmtTokens(usageByStage['DESIGN'].outputTokens)} 출력 · ${fmtCost(usageByStage['DESIGN'].costUsd)}`">
          <q-tooltip>
            캐시 생성 {{ fmtTokens(usageByStage['DESIGN'].cacheCreationTokens) }} ·
            캐시 읽기 {{ fmtTokens(usageByStage['DESIGN'].cacheReadTokens) }}
          </q-tooltip>
        </q-chip>
      </div>
      <div id="design-card">
        <DesignReviewCard
          v-if="task.design"
          :task-id="task.id"
          :status="task.status"
          :design="task.design"
          :is-admin="auth.isAdmin"
          @refresh="refresh"
        />
      </div>

      <q-card v-if="task.analysis" flat bordered>
        <q-card-section class="row items-center q-gutter-sm">
          <div class="text-h6">분석 결과</div>
          <q-chip v-if="usageByStage['ANALYSIS']" dense size="sm" outline icon="bolt"
                  :label="`${fmtTokens(usageByStage['ANALYSIS'].inputTokens)} 입력 · ${fmtTokens(usageByStage['ANALYSIS'].outputTokens)} 출력 · ${fmtCost(usageByStage['ANALYSIS'].costUsd)}`">
            <q-tooltip>
              캐시 생성 {{ fmtTokens(usageByStage['ANALYSIS'].cacheCreationTokens) }} ·
              캐시 읽기 {{ fmtTokens(usageByStage['ANALYSIS'].cacheReadTokens) }}
            </q-tooltip>
          </q-chip>
          <q-space />
          <!-- 승인 상태 chip -->
          <q-chip
            v-if="task.analysis.approved"
            color="positive"
            text-color="white"
            icon="check"
            :label="
              task.status === 'APPROVED' ? '승인됨 (구현 대기)' : '승인됨'
            "
            dense
          />
          <!-- 구현 승인/시작 버튼: admin이고 status=COMPLETED일 때 (승인 여부 무관) -->
          <!-- - 미승인: 1차 승인 + 큐잉 → '구현 승인' -->
          <!-- - 이미 승인됨(구 데이터): status만 APPROVED로 전이 → '구현 시작' -->
          <q-btn
            v-if="auth.isAdmin && task.status === 'COMPLETED'"
            unelevated
            color="positive"
            icon="rocket_launch"
            :label="task.analysis.approved ? '구현 시작' : '구현 승인'"
            @click="approve"
          >
            <q-tooltip>
              {{
                task.analysis.approved
                  ? '이미 승인된 작업입니다. 클릭하면 즉시 구현 큐에 진입합니다.'
                  : '승인 시 즉시 워커가 worktree에서 구현 + Draft PR 생성'
              }}
            </q-tooltip>
          </q-btn>
        </q-card-section>
        <q-separator />
        <q-card-section>
          <div class="text-caption">
            소요 시간: {{ task.analysis.durationMs }} ms
          </div>
          <div class="md-scroll markdown" data-test="analysis-markdown" v-html="analysisHtml" />
        </q-card-section>
        <q-separator />
        <q-card-section v-if="subtasks.length">
          <div class="text-subtitle1 q-mb-sm">Subtask 분해</div>
          <q-list dense bordered>
            <q-item v-for="(s, idx) in subtasks" :key="idx">
              <q-item-section>
                <q-item-label>{{ idx + 1 }}. {{ s.title }}</q-item-label>
                <q-item-label caption>{{ s.summary }}</q-item-label>
                <q-item-label caption>
                  파일: <code>{{ s.files }}</code> · LoC: {{ s.estimatedLoc }}
                  · 위험도:
                  <q-chip dense size="sm" :label="s.risk" />
                </q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
        </q-card-section>
      </q-card>

      <q-card flat bordered class="q-mt-md">
        <q-expansion-item v-model="historyOpen" icon="history" label="진행 이력" header-class="text-subtitle1" data-test="history-desktop">
          <TaskHistoryTimeline v-if="historyOpen" :task-id="task.id" />
        </q-expansion-item>
      </q-card>
      </template>

      <ApproveDialog v-model="showApprove" :task="task" @approved="onApproved" />

      <q-dialog v-model="envDialog" :maximized="$q.screen.lt.md">
        <q-card style="width: min(480px, 100vw)">
          <div class="dialog-body">
            <q-card-section class="row items-center">
              <div class="text-h6">
                {{ envMode === 'deploy' ? '배포' : '재배포' }} — 환경변수
              </div>
              <q-space />
              <q-btn v-close-popup flat round dense icon="close" />
            </q-card-section>
            <q-card-section class="text-caption text-grey-7">
              컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. DB 접속
              정보·시크릿을 여기에 입력하세요. (예:
              <code>SPRING_DATASOURCE_URL</code>, <code>JWT_SECRET</code>) 비밀
              값은 마스킹 표시되지만 평문 저장됩니다.
            </q-card-section>
            <q-card-section class="q-gutter-sm">
              <div
                v-for="row in envRows"
                :key="row.id"
                :class="$q.screen.lt.md ? 'column q-gutter-y-xs' : 'row items-center q-gutter-xs no-wrap'"
              >
                <q-input
                  v-model="row.key"
                  dense
                  outlined
                  placeholder="KEY"
                  style="flex: 1"
                />
                <q-input
                  v-model="row.value"
                  dense
                  outlined
                  placeholder="value"
                  style="flex: 2"
                  :type="row.secret && !row.reveal ? 'password' : 'text'"
                >
                  <template v-if="row.secret" #append>
                    <q-icon
                      :name="row.reveal ? 'visibility_off' : 'visibility'"
                      class="cursor-pointer"
                      @click="row.reveal = !row.reveal"
                    />
                  </template>
                </q-input>
                <q-toggle v-model="row.secret" label="비밀" dense />
                <q-btn
                  flat
                  round
                  dense
                  icon="delete"
                  color="grey"
                  @click="removeEnvRow(row.id)"
                />
              </div>
              <q-btn flat dense icon="add" label="변수 추가" @click="addEnvRow" />
            </q-card-section>
          </div>
          <q-card-actions align="right">
            <q-btn v-close-popup flat label="취소" />
            <q-btn
              unelevated
              color="primary"
              :label="envMode === 'deploy' ? '배포' : '재배포'"
              @click="submitDeploy"
            />
          </q-card-actions>
        </q-card>
      </q-dialog>

    </template>
  </q-page>
</template>

<style scoped>
.bar-more {
  min-width: 44px;
  min-height: 44px;
}
.deploy-log {
  max-height: 240px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 8px;
  border-radius: 4px;
  font-size: 0.75rem;
  white-space: pre-wrap;
}
</style>
