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
const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  title: '',
  description: '',
})
const submitting = ref(false)

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

function openCreate() {
  draft.repoCatalogId = null
  draft.githubBranch = ''
  draft.title = ''
  draft.description = ''
  resetBranchState()
  showCreate.value = true
  loadRepoCatalog()
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

function closeDialog() {
  showCreate.value = false
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
      <q-card style="min-width: 520px">
        <q-card-section>
          <div class="text-h6">작업 등록</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
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
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="closeDialog" />
          <q-btn
            unelevated
            color="primary"
            icon="add_task"
            label="작업 등록"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
