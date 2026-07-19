<script setup lang="ts">
import { useQuasar } from 'quasar'
import { decideResume, type InterviewSummary } from '~/composables/interviewResume'
import { MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort } from '~/composables/modelEffort'

definePageMeta({ layout: 'default' })

interface ImplementationView {
  prUrl: string | null
  prNumber: number | null
  headBranch: string | null
  headSha: string | null
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
  requesterId: number
  retryCount: number
  maxRetry: number
  failureReason: string | null
  createdAt: string
  updatedAt: string
  implementation: ImplementationView | null
}

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
}

const $q = useQuasar()
const mine = ref(true)
const statusFilter = ref<string | null>(null)

const { data: page, refresh } = useTaskPolling<PageResponse<TaskResponse>>(() =>
  useApi('/api/tasks', {
    params: { mine: String(mine.value), status: statusFilter.value || undefined, size: 50 },
  }),
)

const tasks = computed<TaskResponse[]>(() => page.value?.content ?? [])

// 등록 다이얼로그 상태
const showCreate = ref(false)
const draft = reactive({ repoCatalogId: null as number | null, githubBranch: '', title: '', description: '', model: DEFAULT_MODEL, effort: DEFAULT_EFFORT, designRequested: false })
const effortOptions = computed(() => effortsForModel(draft.model))
watch(() => draft.model, (m) => { draft.effort = coerceEffort(m, draft.effort) })
const submitting = ref(false)

// 다이얼로그 단계: 'form' = 입력(Phase 1), 'interview' = 분할 뷰(Phase 2)
const dialogPhase = ref<'form' | 'interview'>('form')
const interviewSessionId = ref<number | null>(null)
const starting = ref(false)

// 새로고침 후 '이어할 인터뷰' 선택(활성 2개 이상일 때)
const resumeCandidates = ref<InterviewSummary[]>([])
const showResumePicker = ref(false)

// 살아있는 워커들이 보고한 MCP 합집합 (다이얼로그 열 때 1회 조회)
const availableMcps = ref<string[]>([])
const aliveWorkerCount = ref(0)
const mcpsLoading = ref(false)

// 관리자 카탈로그 (작업별 추가 MCP)
interface CatalogEntry {
  id: number
  name: string
  displayName: string
  url: string
  transport: string
  description: string | null
  lastCheckStatus: string | null
  lastCheckAt: string | null
}
const catalog = ref<CatalogEntry[]>([])
const selectedCatalogIds = ref<number[]>([])
const catalogLoading = ref(false)

// 레포 카탈로그 (작업 등록 대상 레포)
interface RepoCatalogEntry {
  id: number
  alias: string
  ownerRepo: string | null
  defaultBranch: string | null
}
const repoCatalog = ref<RepoCatalogEntry[]>([])
const repoCatalogLoading = ref(false)
const repoOptions = computed(() =>
  repoCatalog.value.map((r) => ({ label: r.alias, value: r.id })),
)

async function loadRepoCatalog() {
  repoCatalogLoading.value = true
  try {
    repoCatalog.value = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
  } catch {
    repoCatalog.value = []
  } finally {
    repoCatalogLoading.value = false
  }
}

// 별칭 선택 → ownerRepo로 브랜치 로드 + 기본 브랜치 프리필
function onRepoSelected(catalogId: number | null) {
  draft.githubBranch = ''
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry || !entry.ownerRepo) return
  loadBranches(entry.ownerRepo).then(() => {
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}

function statusDotColor(s: string | null): string {
  if (!s) return 'grey-5'
  return { HEALTHY: 'positive', DEGRADED: 'warning', DOWN: 'negative' }[s] ?? 'grey-5'
}

const selectedHasDown = computed(() =>
  catalog.value.some(
    (c) => selectedCatalogIds.value.includes(c.id) && c.lastCheckStatus === 'DOWN',
  ),
)

// 브랜치 동기화 상태 (Phase 1: repo 입력 → /api/repos/branches 자동 호출)
type RepoStatus = 'empty' | 'invalid' | 'loading' | 'ok' | 'notfound' | 'error'
const repoStatus = ref<RepoStatus>('empty')
const repoStatusMsg = ref('')
interface BranchEntry { name: string; sha: string }
const branches = ref<BranchEntry[]>([])
const defaultBranch = ref<string | null>(null)
const branchOptions = computed(() =>
  branches.value.map((b) => ({
    label: b.name === defaultBranch.value ? `${b.name} (기본)` : b.name,
    value: b.name,
  })),
)
const filteredBranchOptions = ref<{ label: string; value: string }[]>([])

const repoStatusColor: Record<RepoStatus, string> = {
  empty: 'grey-7',
  invalid: 'orange-9',
  loading: 'grey-7',
  ok: 'positive',
  notfound: 'negative',
  error: 'warning',
}
const repoStatusIcon: Record<RepoStatus, string> = {
  empty: '',
  invalid: 'info',
  loading: 'sync',
  ok: 'check_circle',
  notfound: 'cancel',
  error: 'warning',
}

let inflightRepo = ''  // 응답 도착 시 최신 입력과 일치하는지 가드

function resetBranchState() {
  repoStatus.value = 'empty'
  repoStatusMsg.value = ''
  branches.value = []
  defaultBranch.value = null
  filteredBranchOptions.value = []
  draft.githubBranch = ''
}

async function loadBranches(repo: string) {
  inflightRepo = repo
  repoStatus.value = 'loading'
  repoStatusMsg.value = '브랜치 불러오는 중...'
  try {
    const res = await useApi<{
      repo: string
      defaultBranch: string | null
      branches: BranchEntry[]
      fetchedAt: string
    }>('/api/repos/branches', { params: { repo } })
    if (inflightRepo !== repo) return  // 다른 입력이 그 사이 발생, 응답 무시
    branches.value = res.branches ?? []
    defaultBranch.value = res.defaultBranch
    filteredBranchOptions.value = branchOptions.value
    draft.githubBranch = res.defaultBranch ?? (res.branches?.[0]?.name ?? '')
    repoStatus.value = 'ok'
    repoStatusMsg.value = `${branches.value.length}개 브랜치 · 방금 동기화`
  } catch (e: any) {
    if (inflightRepo !== repo) return
    const status = e?.statusCode ?? e?.response?.status ?? e?.status
    branches.value = []
    defaultBranch.value = null
    draft.githubBranch = ''
    if (status === 404) {
      repoStatus.value = 'notfound'
      repoStatusMsg.value = e?.data?.message ?? '레포를 찾을 수 없거나 비공개 레포입니다'
    } else {
      repoStatus.value = 'error'
      repoStatusMsg.value = e?.data?.message ?? '브랜치 동기화 실패'
    }
  }
}

function onBranchFilter(val: string, update: (cb: () => void) => void) {
  update(() => {
    if (!val) {
      filteredBranchOptions.value = branchOptions.value
      return
    }
    const lc = val.toLowerCase()
    filteredBranchOptions.value = branchOptions.value.filter((o) =>
      o.value.toLowerCase().includes(lc),
    )
  })
}

async function loadAvailableMcps() {
  mcpsLoading.value = true
  try {
    const res = await useApi<{ mcps: string[]; aliveWorkerCount: number }>(
      '/api/workers/mcps/available',
    )
    availableMcps.value = res.mcps ?? []
    aliveWorkerCount.value = res.aliveWorkerCount ?? 0
  } catch {
    availableMcps.value = []
    aliveWorkerCount.value = 0
  } finally {
    mcpsLoading.value = false
  }
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    catalog.value = await useApi<CatalogEntry[]>('/api/mcp-catalog')
  } catch {
    catalog.value = []
  } finally {
    catalogLoading.value = false
  }
}

function openCreate() {
  draft.repoCatalogId = null
  draft.githubBranch = ''
  draft.title = ''
  draft.description = ''
  draft.model = DEFAULT_MODEL
  draft.effort = DEFAULT_EFFORT
  draft.designRequested = false
  selectedCatalogIds.value = []
  resetBranchState()
  dialogPhase.value = 'form'
  interviewSessionId.value = null
  showCreate.value = true
  loadAvailableMcps()
  loadCatalog()
  loadRepoCatalog()
}

function toggleCatalog(id: number) {
  const idx = selectedCatalogIds.value.indexOf(id)
  if (idx >= 0) selectedCatalogIds.value.splice(idx, 1)
  else selectedCatalogIds.value.push(id)
}

const canSubmit = computed(
  () =>
    !submitting.value &&
    draft.repoCatalogId !== null &&
    !!draft.githubBranch &&
    !!draft.title.trim() &&
    !!draft.description.trim(),
)

async function submit() {
  submitting.value = true
  try {
    await useApi('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        description: draft.description,
        mcpCatalogIds: selectedCatalogIds.value,
        model: draft.model,
        effort: draft.effort,
        designRequested: draft.designRequested,
      },
    })
    $q.notify({ type: 'positive', message: '작업 등록 완료' })
    showCreate.value = false
    refresh()
  } catch (e: any) {
    const msg = e?.data?.message ?? '등록 실패'
    $q.notify({ type: 'negative', message: msg })
  } finally {
    submitting.value = false
  }
}

// Phase 1 제출: 작업이 아니라 인터뷰 세션을 생성하고 Phase 2(분할 뷰)로 전환한다.
// task는 인터뷰 완료(PLAN_READY) 후 '작업 등록'에서만 생성된다.
async function startInterview() {
  starting.value = true
  try {
    const res = await useApi<{ sessionId: number }>('/api/interviews', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        description: draft.description,
        mcpCatalogIds: selectedCatalogIds.value,
        model: draft.model,
        effort: draft.effort,
      },
    })
    openInterview(res.sessionId)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '인터뷰 시작 실패' })
  } finally {
    starting.value = false
  }
}

// 인터뷰 패널을 특정 세션으로 연다(신규 시작/재오픈 공통).
function openInterview(id: number) {
  interviewSessionId.value = id
  dialogPhase.value = 'interview'
  showCreate.value = true
}

// 새로고침 후 진행 중 인터뷰 발견 → 하이브리드 재오픈.
async function discoverActiveInterviews() {
  let list: InterviewSummary[] = []
  try {
    list = await useApi<InterviewSummary[]>('/api/interviews/active')
  } catch {
    return // 조용히 무시 — 작업 목록 로드는 방해하지 않음
  }
  const decision = decideResume(list)
  if (decision.mode === 'auto') {
    openInterview(decision.id)
  } else if (decision.mode === 'pick') {
    resumeCandidates.value = decision.candidates
    showResumePicker.value = true
  }
}

// 선택 다이얼로그에서 하나를 골라 이어하기.
function resumeFromPicker(id: number) {
  showResumePicker.value = false
  openInterview(id)
}

onMounted(discoverActiveInterviews)

// Phase 2에서 '작업 등록' 성공 시: 다이얼로그 닫고 목록 갱신.
function onRegistered(_taskId: number) {
  $q.notify({ type: 'positive', message: '작업이 등록되었습니다' })
  closeDialog()
  refresh()
}

function closeDialog() {
  showCreate.value = false
  dialogPhase.value = 'form'
  interviewSessionId.value = null
}

async function cancel(t: TaskResponse) {
  if (!confirm(`작업 #${t.id} '${t.title}'을 취소하시겠습니까?`)) return
  try {
    await useApi(`/api/tasks/${t.id}/cancel`, { method: 'POST' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '취소 실패' })
  }
}

async function remove(t: TaskResponse) {
  if (!confirm(`작업 #${t.id}을 삭제하시겠습니까? (복구 불가)`)) return
  try {
    await useApi(`/api/tasks/${t.id}`, { method: 'DELETE' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '삭제 실패' })
  }
}

function statusClass(status: string) {
  return (
    {
      PENDING: 'status-chip status-pending',
      IN_PROGRESS: 'status-chip status-in-progress',
      COMPLETED: 'status-chip status-completed',
      FAILED: 'status-chip status-failed',
      APPROVED: 'status-chip status-approved',
      IMPLEMENTING: 'status-chip status-implementing',
      PR_CREATED: 'status-chip status-pr-created',
      IMPLEMENTATION_FAILED: 'status-chip status-impl-failed',
      CANCELLED: 'status-chip status-cancelled',
    }[status] || 'status-chip'
  )
}

// 서버 softDelete 가드와 동일 집합 — 배포 이력이 활성이면 먼저 중지 후 삭제
const DEPLOY_ACTIVE_STATUSES = [
  'DEPLOYED',
  'DEPLOY_LOST',
  'DEPLOY_PENDING',
  'DEPLOYING',
  'UNDEPLOY_PENDING',
  'UNDEPLOYING',
]
</script>

<template>
  <q-page padding>
    <QueueStatsBar />
    <div class="row items-center q-mb-md">
      <div class="text-h5">작업 목록</div>
      <q-space />
      <q-toggle v-model="mine" label="내 작업만" @update:model-value="refresh" />
      <q-select
        v-model="statusFilter"
        :options="[
          { label: '전체', value: null },
          { label: '작업대기', value: 'PENDING' },
          { label: '분석중', value: 'IN_PROGRESS' },
          { label: '분석완료', value: 'COMPLETED' },
          { label: '분석실패', value: 'FAILED' },
          { label: '구현대기', value: 'APPROVED' },
          { label: '구현중', value: 'IMPLEMENTING' },
          { label: 'PR생성', value: 'PR_CREATED' },
          { label: '구현실패', value: 'IMPLEMENTATION_FAILED' },
          { label: '취소됨', value: 'CANCELLED' },
        ]"
        emit-value
        map-options
        dense
        outlined
        style="min-width: 140px"
        class="q-ml-md"
        @update:model-value="refresh"
      />
      <q-btn class="q-ml-md" color="primary" icon="add" label="작업 등록" @click="openCreate" />
    </div>

    <q-table
      :rows="tasks"
      row-key="id"
      flat
      bordered
      :columns="[
        { name: 'id', label: '#', field: 'id', align: 'left' },
        { name: 'title', label: '제목', field: 'title', align: 'left' },
        { name: 'repo', label: '레포', field: (r) => r.repoAlias ?? r.githubRepo, align: 'left' },
        { name: 'status', label: '상태', field: 'statusLabel', align: 'left' },
        { name: 'pr', label: 'PR', field: (r) => r.implementation?.prNumber ?? '', align: 'center' },
        { name: 'retry', label: '재시도', field: (r) => `${r.retryCount}/${r.maxRetry}`, align: 'center' },
        { name: 'createdAt', label: '등록', field: 'createdAt', align: 'left' },
        { name: 'actions', label: '', field: () => '', align: 'right' },
      ]"
      :pagination="{ rowsPerPage: 20 }"
    >
      <template #body-cell-title="props">
        <q-td :props="props">
          <NuxtLink :to="`/tasks/${props.row.id}`">{{ props.row.title }}</NuxtLink>
        </q-td>
      </template>
      <template #body-cell-status="props">
        <q-td :props="props">
          <span :class="statusClass(props.row.status)">{{ props.row.statusLabel }}</span>
        </q-td>
      </template>
      <template #body-cell-pr="props">
        <q-td :props="props">
          <a
            v-if="props.row.implementation?.prUrl"
            :href="props.row.implementation.prUrl"
            target="_blank"
            class="text-primary"
            @click.stop
          >
            #{{ props.row.implementation.prNumber }}
            <q-icon name="open_in_new" size="14px" />
          </a>
          <span v-else class="text-grey-5">—</span>
        </q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props">
          <q-btn
            v-if="props.row.status === 'PENDING'"
            flat
            dense
            color="warning"
            icon="block"
            @click="cancel(props.row)"
          />
          <q-btn
            flat
            dense
            color="negative"
            icon="delete"
            :disable="DEPLOY_ACTIVE_STATUSES.includes(props.row.status)"
            :title="
              DEPLOY_ACTIVE_STATUSES.includes(props.row.status)
                ? '배포 이력이 활성인 작업은 먼저 중지 후 삭제할 수 있습니다'
                : undefined
            "
            @click="remove(props.row)"
          />
        </q-td>
      </template>
    </q-table>

    <!-- 등록 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent>
      <q-card
        :style="
          dialogPhase === 'interview'
            ? 'min-width: 90vw; max-width: 1200px'
            : 'min-width: 520px'
        "
      >
        <q-card-section>
          <div class="text-h6">
            {{ dialogPhase === 'form' ? '대화형 분석 시작' : '대화형 분석' }}
          </div>
        </q-card-section>
        <q-card-section v-if="dialogPhase === 'form'" class="q-gutter-md">
          <q-banner
            v-if="!mcpsLoading"
            :class="
              aliveWorkerCount === 0
                ? 'bg-orange-1 text-orange-10'
                : availableMcps.length === 0
                  ? 'bg-grey-2 text-grey-9'
                  : 'bg-indigo-1 text-indigo-10'
            "
            dense
            rounded
          >
            <template #avatar>
              <q-icon
                :name="
                  aliveWorkerCount === 0
                    ? 'warning'
                    : availableMcps.length === 0
                      ? 'info'
                      : 'bolt'
                "
              />
            </template>
            <template v-if="aliveWorkerCount === 0">
              살아있는 워커가 없습니다. 작업 등록은 가능하지만 워커가 시작될 때까지 대기 상태로 남습니다.
            </template>
            <template v-else-if="availableMcps.length === 0">
              워커 {{ aliveWorkerCount }}개 활성 · 등록된 MCP 없음 (기본 Claude 도구만 사용)
            </template>
            <template v-else>
              <div class="q-mb-xs">
                이 분석에서 사용 가능한 MCP 도구
                <span class="text-caption">(워커 {{ aliveWorkerCount }}개 활성)</span>
              </div>
              <div>
                <q-chip
                  v-for="m in availableMcps"
                  :key="m"
                  color="white"
                  text-color="indigo-10"
                  icon="bolt"
                  size="sm"
                  dense
                  :label="m"
                  class="q-mr-xs q-mb-xs"
                />
              </div>
            </template>
          </q-banner>

          <q-select
            v-model="draft.repoCatalogId"
            :options="repoOptions"
            :loading="repoCatalogLoading"
            label="레포 (별칭 선택)"
            outlined
            dense
            emit-value
            map-options
            autofocus
            data-test="repo-select"
            :hint="repoCatalog.length === 0 ? '등록된 레포 없음 — 관리자에게 문의' : '관리자가 등록한 레포 중 선택'"
            @update:model-value="onRepoSelected"
          >
            <template #no-option>
              <q-item>
                <q-item-section class="text-grey">등록된 레포가 없습니다</q-item-section>
              </q-item>
            </template>
          </q-select>

          <q-select
            v-model="draft.githubBranch"
            :options="filteredBranchOptions"
            :disable="repoStatus !== 'ok'"
            label="브랜치"
            outlined
            dense
            use-input
            input-debounce="0"
            emit-value
            map-options
            :hint="
              repoStatus === 'ok'
                ? '입력해서 검색할 수 있습니다'
                : repoStatus === 'notfound' || repoStatus === 'error'
                  ? repoStatusMsg
                  : '레포 선택 후 브랜치 선택 가능'
            "
            @filter="onBranchFilter"
          >
            <template #no-option>
              <q-item>
                <q-item-section class="text-grey">결과 없음</q-item-section>
              </q-item>
            </template>
          </q-select>

          <q-input v-model="draft.title" label="작업 제목" outlined dense maxlength="500" />
          <q-input
            v-model="draft.description"
            label="작업 상세"
            type="textarea"
            outlined
            autogrow
            rows="4"
          />

          <div class="row q-col-gutter-md">
            <div class="col">
              <q-select
                v-model="draft.model"
                :options="MODEL_OPTIONS"
                label="모델"
                emit-value
                map-options
                dense
                outlined
              />
            </div>
            <div class="col">
              <q-select
                v-model="draft.effort"
                :options="effortOptions"
                label="effort"
                dense
                outlined
                :hint="draft.model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
              />
            </div>
          </div>

          <q-toggle
            v-model="draft.designRequested"
            dense
            label="디자인 단계 포함"
          >
            <q-tooltip>분석 승인 후 워커가 화면 목업을 생성하고, 승인해야 구현이 시작됩니다</q-tooltip>
          </q-toggle>

          <q-expansion-item
            icon="extension"
            label="이 분석에만 추가할 MCP 도구"
            :caption="
              catalog.length === 0
                ? '관리자 카탈로그 비어있음'
                : `${selectedCatalogIds.length}개 선택 · 활성 ${catalog.length}개 중`
            "
            header-class="text-grey-9 bg-grey-2"
            dense
          >
            <q-banner
              v-if="catalog.length === 0"
              class="bg-grey-1 text-grey-8 q-mt-sm"
              dense
            >
              <template #avatar><q-icon name="info" /></template>
              관리자가 등록한 SSE MCP 카탈로그가 없습니다. 관리자에게 등록 요청하세요.
            </q-banner>
            <div v-else class="q-pa-sm">
              <q-chip
                v-for="entry in catalog"
                :key="entry.id"
                clickable
                :color="selectedCatalogIds.includes(entry.id) ? 'indigo-6' : 'grey-3'"
                :text-color="selectedCatalogIds.includes(entry.id) ? 'white' : 'grey-9'"
                :icon="selectedCatalogIds.includes(entry.id) ? 'check' : 'add'"
                @click="toggleCatalog(entry.id)"
              >
                <q-badge
                  rounded
                  :color="statusDotColor(entry.lastCheckStatus)"
                  class="q-mr-xs"
                  style="min-height: 8px; min-width: 8px; padding: 0"
                />
                {{ entry.displayName }}
                <q-tooltip>
                  <div><strong>{{ entry.name }}</strong> ({{ entry.transport }})</div>
                  <div style="max-width: 360px; word-break: break-all">{{ entry.url }}</div>
                  <div v-if="entry.description" class="q-mt-xs">{{ entry.description }}</div>
                  <div class="q-mt-xs">
                    헬스: <strong>{{ entry.lastCheckStatus ?? 'UNKNOWN' }}</strong>
                  </div>
                </q-tooltip>
              </q-chip>
              <div
                v-if="selectedHasDown"
                class="text-caption text-negative q-mt-sm row items-center q-gutter-xs"
              >
                <q-icon name="warning" size="14px" />
                <span>
                  DOWN 상태 MCP가 포함됨 — claude가 연결 실패해도 분석은 진행되지만 해당 도구는 사용
                  안 됨. 관리자에게 확인 요청 권장.
                </span>
              </div>
            </div>
          </q-expansion-item>
        </q-card-section>
        <q-card-actions v-if="dialogPhase === 'form'" align="right">
          <q-btn flat label="취소" @click="closeDialog" />
          <q-btn
            unelevated
            color="primary"
            icon="forum"
            label="인터뷰 시작"
            :loading="starting"
            :disable="!canSubmit"
            @click="startInterview"
          />
        </q-card-actions>

        <q-card-section v-else class="q-pa-none">
          <InterviewPanel
            v-if="interviewSessionId"
            :session-id="interviewSessionId"
            :model="draft.model"
            :effort="draft.effort"
            @registered="onRegistered"
            @close="closeDialog"
          />
        </q-card-section>
      </q-card>
    </q-dialog>

    <!-- 진행 중 인터뷰 선택(활성 2개 이상) -->
    <q-dialog v-model="showResumePicker">
      <q-card style="min-width: 420px">
        <q-card-section class="text-h6">진행 중인 대화형 분석</q-card-section>
        <q-card-section class="q-pt-none text-grey-8">
          이어서 진행할 인터뷰를 선택하세요.
        </q-card-section>
        <q-list bordered separator>
          <q-item
            v-for="c in resumeCandidates"
            :key="c.id"
            clickable
            @click="resumeFromPicker(c.id)"
          >
            <q-item-section>
              <q-item-label>{{ c.title }}</q-item-label>
              <q-item-label caption>
                {{ c.githubRepo }} · {{ c.githubBranch }} · {{ c.status }}
              </q-item-label>
            </q-item-section>
            <q-item-section side>
              <q-btn flat dense color="primary" icon="forum" label="이어하기" no-caps />
            </q-item-section>
          </q-item>
        </q-list>
        <q-card-actions align="right">
          <q-btn flat label="닫기" @click="showResumePicker = false" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
