<script setup lang="ts">
import { useQuasar } from 'quasar'

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
const draft = reactive({ githubRepo: '', githubBranch: '', title: '', description: '' })
const submitting = ref(false)

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

const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let inflightRepo = ''  // 응답 도착 시 최신 입력과 일치하는지 가드

function resetBranchState() {
  repoStatus.value = 'empty'
  repoStatusMsg.value = ''
  branches.value = []
  defaultBranch.value = null
  filteredBranchOptions.value = []
  draft.githubBranch = ''
}

function normalizeRepo(input: string): string {
  // "https://github.com/owner/repo(.git)?" 또는 "owner/repo" 모두 허용
  const trimmed = input.trim()
  const m = trimmed.match(/(?:github\.com[\/:])?([A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+?)(?:\.git)?\/?$/)
  return m ? m[1] : trimmed
}

watch(
  () => draft.githubRepo,
  (val) => {
    if (debounceTimer) clearTimeout(debounceTimer)
    const raw = (val ?? '').trim()
    if (!raw) {
      resetBranchState()
      return
    }
    const normalized = normalizeRepo(raw)
    if (!REPO_RE.test(normalized)) {
      branches.value = []
      defaultBranch.value = null
      draft.githubBranch = ''
      repoStatus.value = 'invalid'
      repoStatusMsg.value = "'owner/repo' 형식이어야 합니다"
      return
    }
    debounceTimer = setTimeout(() => loadBranches(normalized), 600)
  },
)

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
  draft.githubRepo = ''
  draft.githubBranch = ''
  draft.title = ''
  draft.description = ''
  selectedCatalogIds.value = []
  resetBranchState()
  showCreate.value = true
  loadAvailableMcps()
  loadCatalog()
}

function toggleCatalog(id: number) {
  const idx = selectedCatalogIds.value.indexOf(id)
  if (idx >= 0) selectedCatalogIds.value.splice(idx, 1)
  else selectedCatalogIds.value.push(id)
}

const canSubmit = computed(
  () =>
    !submitting.value &&
    repoStatus.value === 'ok' &&
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
        githubRepo: normalizeRepo(draft.githubRepo),
        githubBranch: draft.githubBranch,
        title: draft.title,
        description: draft.description,
        mcpCatalogIds: selectedCatalogIds.value,
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
        { name: 'repo', label: '레포', field: 'githubRepo', align: 'left' },
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
          <q-btn flat dense color="negative" icon="delete" @click="remove(props.row)" />
        </q-td>
      </template>
    </q-table>

    <!-- 등록 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent>
      <q-card style="min-width: 520px">
        <q-card-section>
          <div class="text-h6">새 작업 등록</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
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

          <div>
            <q-input
              v-model="draft.githubRepo"
              label="GitHub 레포"
              placeholder="owner/repo 또는 https://github.com/owner/repo"
              outlined
              dense
              autofocus
              :loading="repoStatus === 'loading'"
            />
            <div
              v-if="repoStatusMsg"
              class="text-caption q-mt-xs row items-center q-gutter-xs"
              :class="`text-${repoStatusColor[repoStatus]}`"
            >
              <q-icon
                v-if="repoStatusIcon[repoStatus]"
                :name="repoStatusIcon[repoStatus]"
                size="14px"
              />
              <span>{{ repoStatusMsg }}</span>
            </div>
          </div>

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
                : '레포 입력 후 브랜치 선택 가능'
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
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showCreate = false" />
          <q-btn
            unelevated
            color="primary"
            label="등록"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
