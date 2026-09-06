<script setup lang="ts">
import { useQuasar } from 'quasar'
import { buildStages, stageAccepts, type MoveDef, type StageCard } from '~/composables/taskStages'

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
  requesterId: string
  retryCount: number
  maxRetry: number
  failureReason: string | null
  designRequested: boolean
  createdAt: string
  updatedAt: string
  implementation: ImplementationView | null
  totalCostUsd: number | null
}

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
}

const $q = useQuasar()
const auth = useAuthStore()
const mine = ref(true)
const statusFilter = ref<string | null>(null)

const { data: page, refresh } = useTaskPolling<PageResponse<TaskResponse>>(() =>
  useApi('/api/tasks', {
    params: { mine: String(mine.value), status: statusFilter.value || undefined, size: 50 },
  }),
)

const tasks = computed<TaskResponse[]>(() => page.value?.content ?? [])

// ── 총비용 배지 ──────────────────────────────────────────────────
// 진행 중 상태 — 배지 제외 대상 (스펙 §6: 종결 상태만 표시)
const RUNNING_STATUSES = [
  'IN_PROGRESS', 'IMPLEMENTING', 'DESIGNING', 'DEPLOYING', 'UNDEPLOYING', 'INTERVIEWING',
]

function costBadge(t: TaskResponse): string | null {
  if (t.totalCostUsd == null || t.totalCostUsd === 0) return null
  if (RUNNING_STATUSES.includes(t.status)) return null
  const c = t.totalCostUsd
  return '$' + (c < 0.01 ? c.toFixed(4) : c.toFixed(2))
}

// ── 카드뷰(1b 여유형) ────────────────────────────────────────────
// 단계/상태/전이 정의는 composables/taskStages.ts. 상태 필터가 걸리면 그 상태만 남으므로
// 빈 단계는 감춘다(필터 결과가 한 줄로 보이는 편이 낫다).
const stages = computed(() =>
  buildStages(tasks.value, {
    isAdmin: auth.isAdmin,
    showEmptyStages: !statusFilter.value,
  }),
)

const dragId = ref<number | null>(null)
const overStage = ref<string | null>(null)
const moving = ref(false)

const draggingMove = computed<MoveDef | null>(() => {
  if (dragId.value == null) return null
  for (const s of stages.value) {
    for (const g of s.groups) {
      const hit = g.cards.find((c) => c.task.id === dragId.value)
      if (hit) return hit.move
    }
  }
  return null
})

function dropState(stageKey: string): 'none' | 'ready' | 'over' {
  if (!stageAccepts(stageKey, draggingMove.value)) return 'none'
  return overStage.value === stageKey ? 'over' : 'ready'
}

function onDragStart(card: StageCard<TaskResponse>) {
  if (!card.move) return
  dragId.value = card.task.id
}

function onDragEnd() {
  dragId.value = null
  overStage.value = null
}

function onDragOver(stageKey: string, e: DragEvent) {
  if (!stageAccepts(stageKey, draggingMove.value)) return
  e.preventDefault()
  if (overStage.value !== stageKey) overStage.value = stageKey
}

async function onDrop(stageKey: string, e: DragEvent) {
  e.preventDefault()
  const id = dragId.value
  const move = draggingMove.value
  onDragEnd()
  if (id == null || !stageAccepts(stageKey, move)) return
  await applyMove(id, move!)
}

/**
 * 드래그 전이 실행. 배포 계열은 되돌리기가 비싸서(컨테이너 빌드/기동, 공개 URL 회수)
 * 목업과 달리 확인을 한 번 받는다 — 승인 계열은 목업대로 바로 실행.
 */
async function applyMove(id: number, move: MoveDef) {
  if (move.confirm && !confirm(`작업 #${id} — ${move.label}을(를) 실행할까요?`)) return
  moving.value = true
  try {
    await useApi(`/api/tasks/${id}/${move.path}`, { method: 'POST' })
    $q.notify({ type: 'positive', message: `작업 #${id} — ${move.label} 완료` })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? `${move.label} 실패` })
  } finally {
    moving.value = false
  }
}

function repoLabel(t: TaskResponse) {
  return t.repoAlias ?? t.githubRepo
}

function initialOf(t: TaskResponse) {
  return String(t.requesterId ?? '?').trim().charAt(0).toUpperCase() || '?'
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

function cancelable(t: TaskResponse) {
  return ['PENDING', 'AWAITING_APPROVAL'].includes(t.status)
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
  if (DEPLOY_ACTIVE_STATUSES.includes(t.status)) {
    $q.notify({
      type: 'warning',
      message: '배포 이력이 활성인 작업은 먼저 중지 후 삭제할 수 있습니다',
    })
    return
  }
  if (!confirm(`작업 #${t.id}을 삭제하시겠습니까? (복구 불가)`)) return
  try {
    await useApi(`/api/tasks/${t.id}`, { method: 'DELETE' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '삭제 실패' })
  }
}

// ── 등록 다이얼로그 ──────────────────────────────────────────────
const showCreate = ref(false)
const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  title: '',
  description: '',
})
const submitting = ref(false)

// 첨부 (스펙 2026-08-16 §8.1) — 서버 한도와 동일 값의 사전 검증
const MAX_FILES = 10
const MAX_FILE_MB = 20
const MAX_TOTAL_MB = 50
const draftFiles = ref<File[]>([])
// 라벨은 상수에서 파생 — 한도를 문자열에 다시 하드코딩하면 서버/검증과 갈라진다
const attachmentLabel = `첨부파일 (선택 · 최대 ${MAX_FILES}개, 파일당 ${MAX_FILE_MB}MB, 합계 ${MAX_TOTAL_MB}MB)`

function validateFiles(files: File[]): string | null {
  if (files.length > MAX_FILES) return `첨부는 최대 ${MAX_FILES}개까지 가능합니다`
  const over = files.find((f) => f.size > MAX_FILE_MB * 1024 * 1024)
  if (over) return `파일당 ${MAX_FILE_MB}MB 이하만 첨부할 수 있습니다: ${over.name}`
  if (files.some((f) => f.size === 0)) return '빈 파일(0바이트)은 첨부할 수 없습니다'
  const total = files.reduce((s, f) => s + f.size, 0)
  if (total > MAX_TOTAL_MB * 1024 * 1024) return `첨부 합계는 ${MAX_TOTAL_MB}MB 이하여야 합니다`
  return null
}

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
  draftFiles.value = []
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
  const meta = {
    repoCatalogId: draft.repoCatalogId,
    githubBranch: draft.githubBranch,
    title: draft.title,
    description: draft.description,
  }
  if (draftFiles.value.length > 0) {
    const err = validateFiles(draftFiles.value)
    if (err) {
      $q.notify({ type: 'warning', message: err })
      return
    }
  }
  submitting.value = true
  try {
    if (draftFiles.value.length > 0) {
      // Content-Type을 명시하지 않는다 — $fetch가 FormData boundary를 스스로 설정 (스펙 §8.1)
      const form = new FormData()
      form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
      for (const f of draftFiles.value) form.append('files', f, f.name)
      await useApi('/api/tasks', { method: 'POST', body: form })
    } else {
      await useApi('/api/tasks', { method: 'POST', body: meta })
    }
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
          { label: '승인대기', value: 'AWAITING_APPROVAL' },
          { label: '인터뷰중', value: 'INTERVIEWING' },
          { label: '입력대기', value: 'INTERVIEW_INPUT' },
          { label: '플랜승인대기', value: 'INTERVIEW_REVIEW' },
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

    <div class="stage-rows">
      <div
        v-for="s in stages"
        :key="s.key"
        class="stage-row"
        :data-test="`stage-${s.key}`"
        @dragover="onDragOver(s.key, $event)"
        @drop="onDrop(s.key, $event)"
      >
        <!-- 단계 요약 -->
        <div class="stage-side">
          <div class="row items-baseline q-gutter-x-sm">
            <span class="stage-name">{{ s.name }}</span>
            <q-space />
            <span class="stage-count" :style="{ color: s.color }">{{ s.count }}</span>
          </div>
          <div v-if="!s.terminal" class="row q-gutter-x-xs">
            <div
              v-for="(p, i) in s.pipeline"
              :key="i"
              class="pipeline-seg"
              :style="{ background: p.bg }"
            />
          </div>
          <div class="stage-summary">{{ s.summary }}</div>
          <div class="stage-oldest">
            <q-icon name="schedule" size="14px" color="grey-6" />
            최장 {{ s.oldest }}
          </div>
          <div v-if="s.bottleneck" class="stage-bottleneck">
            <q-icon name="priority_high" size="14px" />
            병목 단계
          </div>
        </div>

        <!-- 카드 트랙 -->
        <div class="stage-track-wrap">
          <div class="stage-track">
            <div v-for="g in s.groups" :key="g.status" class="stage-group">
              <div class="row items-center q-gutter-x-sm">
                <span class="group-chip" :style="{ background: g.bg, color: g.fg }">
                  {{ g.label }}
                </span>
                <span class="group-count">{{ g.count }}</span>
              </div>
              <div class="row no-wrap q-gutter-x-md">
                <div
                  v-for="c in g.cards"
                  :key="c.task.id"
                  class="task-card"
                  :class="{ draggable: !!c.move }"
                  :draggable="!!c.move"
                  :title="c.move ? `드래그: ${c.move.label}` : undefined"
                  @dragstart="onDragStart(c)"
                  @dragend="onDragEnd"
                >
                  <div class="row items-center q-gutter-x-sm">
                    <span class="card-id">#{{ c.task.id }}</span>
                    <span class="card-status" :style="{ background: c.bg, color: c.fg }">
                      {{ c.task.statusLabel }}
                    </span>
                    <span v-if="costBadge(c.task)" class="card-cost">{{ costBadge(c.task) }}</span>
                    <q-space />
                    <q-icon
                      v-if="cancelable(c.task)"
                      name="block"
                      size="18px"
                      color="warning"
                      class="cursor-pointer"
                      @click="cancel(c.task)"
                    >
                      <q-tooltip>취소</q-tooltip>
                    </q-icon>
                    <q-icon
                      name="delete"
                      size="18px"
                      class="cursor-pointer"
                      :color="DEPLOY_ACTIVE_STATUSES.includes(c.task.status) ? 'grey-4' : 'negative'"
                      @click="remove(c.task)"
                    >
                      <q-tooltip>
                        {{
                          DEPLOY_ACTIVE_STATUSES.includes(c.task.status)
                            ? '배포 이력이 활성인 작업은 먼저 중지 후 삭제'
                            : '삭제'
                        }}
                      </q-tooltip>
                    </q-icon>
                  </div>

                  <NuxtLink :to="`/tasks/${c.task.id}`" class="card-title">
                    {{ c.task.title }}
                  </NuxtLink>

                  <div class="card-repo">
                    <q-icon name="folder" size="13px" color="grey-6" />
                    <span class="ellipsis">{{ repoLabel(c.task) }}</span>
                    <span class="text-grey-5">·</span>
                    <q-icon name="call_split" size="13px" color="grey-6" />
                    <span class="ellipsis">{{ c.task.githubBranch }}</span>
                  </div>

                  <div class="row no-wrap q-gutter-x-xs">
                    <div v-for="(st, i) in c.steps" :key="i" class="card-step">
                      <div class="card-step-bar" :style="{ background: st.bg }" />
                      <span
                        class="card-step-label"
                        :style="{ color: st.fg, fontWeight: st.weight }"
                      >{{ st.label }}</span>
                    </div>
                  </div>

                  <div class="card-age">
                    <span class="text-grey-7">등록 후 {{ c.age }}</span>
                    <div class="card-age-track">
                      <div
                        class="card-age-fill"
                        :style="{ background: c.ageColor, width: `${c.agePct}%` }"
                      />
                    </div>
                  </div>

                  <div class="card-foot">
                    <span class="card-avatar">{{ initialOf(c.task) }}</span>
                    <span>{{ c.task.requesterId }}</span>
                    <span class="text-grey-5">·</span>
                    <span>재시도 {{ c.task.retryCount }}/{{ c.task.maxRetry }}</span>
                    <a
                      v-if="c.task.implementation?.prUrl"
                      :href="c.task.implementation.prUrl"
                      target="_blank"
                      class="card-pr"
                      @click.stop
                    >
                      PR #{{ c.task.implementation.prNumber }}
                      <q-icon name="open_in_new" size="13px" />
                    </a>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="s.isEmpty" class="stage-empty">
              <q-icon name="inbox" size="22px" />
              이 단계에 작업이 없습니다
              <span v-if="s.emptyHint" class="stage-empty-hint">{{ s.emptyHint }}</span>
            </div>
          </div>

          <div
            v-if="dropState(s.key) !== 'none'"
            class="drop-overlay"
            :class="{ over: dropState(s.key) === 'over' }"
            :style="{ borderColor: s.color, color: s.color }"
          >
            {{ draggingMove?.label }}
          </div>
        </div>
      </div>
    </div>

    <div class="board-foot">
      <span v-if="auth.isAdmin">
        드래그로 이동 가능한 전이만 허용: 분석완료 → 구현대기 · 디자인승인대기 → 구현대기 ·
        PR생성 → 배포대기 · 배포완료 → 배포중지대기
      </span>
      <span v-else>상태 전이는 관리자만 수행할 수 있습니다</span>
      <q-space />
      <span>총 {{ tasks.length }}건</span>
    </div>

    <q-inner-loading :showing="moving" />

    <!-- 등록 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent :maximized="$q.screen.lt.md">
      <q-card style="width: min(520px, 100vw)">
        <div class="dialog-body">
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
            <q-file
              v-model="draftFiles"
              :label="attachmentLabel"
              outlined
              dense
              multiple
              use-chips
              counter
              append
              data-test="attachment-input"
            >
              <template #prepend>
                <q-icon name="attach_file" />
              </template>
            </q-file>
          </q-card-section>
        </div>
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

<style scoped>
/* 값은 디자인 문서 `작업 목록 카드뷰.dc.html` 1b(여유형) 그대로 */
.stage-rows { display: flex; flex-direction: column; gap: 14px; }

.stage-row {
  display: flex;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 4px;
  background: #fff;
  overflow: hidden;
}

.stage-side {
  flex: 0 0 208px;
  padding: 14px 16px;
  background: #fafafa;
  border-right: 1px solid rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.stage-name { font-size: 17px; font-weight: 700; color: #212121; }
.stage-count { font-size: 26px; font-weight: 600; line-height: 1; }
.pipeline-seg { flex: 1; height: 5px; border-radius: 3px; }
.stage-summary { font-size: 11.5px; color: #757575; line-height: 1.5; }
.stage-oldest {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: #616161;
}
.stage-bottleneck {
  display: flex;
  align-items: center;
  gap: 4px;
  align-self: flex-start;
  font-size: 11.5px;
  font-weight: 600;
  color: #ef6c00;
  background: #fff8e1;
  border: 1px solid #ffe082;
  border-radius: 3px;
  padding: 3px 7px;
}

.stage-track-wrap { flex: 1; min-width: 0; position: relative; }
.stage-track { display: flex; overflow-x: auto; padding: 14px 0; }
.stage-track::-webkit-scrollbar { height: 8px; }
.stage-track::-webkit-scrollbar-thumb { background: rgba(0, 0, 0, 0.18); border-radius: 4px; }
.stage-track::-webkit-scrollbar-track { background: transparent; }

.stage-group {
  flex: 0 0 auto;
  padding: 0 14px;
  border-right: 1px dashed rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.group-chip { font-size: 11.5px; font-weight: 600; padding: 2px 7px; border-radius: 4px; }
.group-count { font-size: 12px; font-weight: 600; color: #616161; }

.task-card {
  flex: 0 0 auto;
  width: 272px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 6px;
  background: #fff;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.07);
  transition: box-shadow 0.15s, border-color 0.15s;
}
.task-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  border-color: rgba(25, 118, 210, 0.45);
}
.task-card.draggable { cursor: grab; }
.task-card.draggable:active { cursor: grabbing; }

.card-id { font-size: 12px; font-weight: 700; color: #9e9e9e; }
.card-status { font-size: 11px; font-weight: 500; padding: 2px 7px; border-radius: 4px; }
.card-cost { font-size: 11px; font-weight: 600; color: #2e7d32; padding: 2px 6px; border-radius: 4px; background: rgba(46, 125, 50, 0.08); }
.card-title {
  font-size: 14.5px;
  font-weight: 600;
  line-height: 1.4;
  text-decoration: none;
  color: #1976d2;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 41px;
}
.card-title:hover { color: #0d47a1; text-decoration: underline; }
.card-repo {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: #757575;
  white-space: nowrap;
  overflow: hidden;
}
.card-repo .ellipsis { overflow: hidden; text-overflow: ellipsis; }

.card-step { flex: 1; display: flex; flex-direction: column; gap: 3px; }
.card-step-bar { height: 4px; border-radius: 2px; }
.card-step-label { font-size: 11px; }

.card-age { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #616161; }
.card-age-track {
  flex: 1;
  height: 3px;
  background: #eeeeee;
  border-radius: 2px;
  overflow: hidden;
}
.card-age-fill { height: 3px; border-radius: 2px; }

.card-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  border-top: 1px solid rgba(0, 0, 0, 0.07);
  padding-top: 8px;
  font-size: 11.5px;
  color: #616161;
}
.card-avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #e3f2fd;
  color: #1565c0;
  font-size: 10px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.card-pr {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 11.5px;
  text-decoration: none;
  color: #1976d2;
}

.stage-empty {
  flex: 1;
  margin: 0 14px;
  min-height: 150px;
  border: 1px dashed rgba(0, 0, 0, 0.18);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #9e9e9e;
  font-size: 13px;
}
.stage-empty-hint { font-size: 11.5px; color: #bdbdbd; }

.drop-overlay {
  position: absolute;
  inset: 0;
  border: 2px dashed;
  border-radius: 4px;
  background: rgba(25, 118, 210, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  pointer-events: none;
}
.drop-overlay.over { background: rgba(25, 118, 210, 0.1); }

.board-foot {
  display: flex;
  align-items: center;
  padding: 14px 4px 0;
  font-size: 12px;
  color: #757575;
}
</style>
