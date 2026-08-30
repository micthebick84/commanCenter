<script setup lang="ts">
import { useQuasar } from 'quasar'
import {
  MODEL_OPTIONS,
  DEFAULT_MODEL,
  DEFAULT_EFFORT,
  effortsForModel,
  coerceEffort,
} from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'

definePageMeta({ layout: 'default' })

// GET /api/questions (QuestionSummaryResponse) 계약 미러
interface QuestionSummary {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  repoAlias: string | null
  requesterId: string
  status: string
  statusName: string
  model: string
  effort: string
  totalCostUsd: number | null
  createdAt: string
  updatedAt: string
}

const $q = useQuasar()
const auth = useAuthStore()
const all = ref(false)

const { data: questions, refresh } = useTaskPolling<QuestionSummary[]>(() =>
  useApi('/api/questions', { params: { all: String(all.value) } }),
)
// 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지
const rows = computed(() => (Array.isArray(questions.value) ? questions.value : []))

function openQuestion(id: number) {
  navigateTo(`/questions/${id}`)
}

// ── 질문하기 다이얼로그 ─────────────────────────────────────────
// 레포/브랜치/제목/본문은 작업 등록 다이얼로그(pages/tasks/index.vue) 패턴,
// 모델/effort/MCP는 승인 다이얼로그(ApproveDialog.vue) 패턴 — 승인 게이트가 없어 등록자가 여기서 정한다.
const showCreate = ref(false)
const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  title: '',
  question: '',
  model: DEFAULT_MODEL,
  effort: DEFAULT_EFFORT,
  mcpCatalogIds: [] as number[],
})
const submitting = ref(false)
const effortOptions = computed(() => effortsForModel(draft.model))
watch(
  () => draft.model,
  (m) => {
    draft.effort = coerceEffort(m, draft.effort)
  },
)

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
    const res = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
    repoCatalog.value = Array.isArray(res) ? res : []
  } catch {
    repoCatalog.value = []
  } finally {
    repoCatalogLoading.value = false
  }
}

type RepoStatus = 'empty' | 'loading' | 'ok' | 'notfound' | 'error'
const repoStatus = ref<RepoStatus>('empty')
const repoStatusMsg = ref('')
interface BranchEntry {
  name: string
  sha: string
}
const branches = ref<BranchEntry[]>([])
const defaultBranch = ref<string | null>(null)
const branchOptions = computed(() =>
  branches.value.map((b) => ({
    label: b.name === defaultBranch.value ? `${b.name} (기본)` : b.name,
    value: b.name,
  })),
)
const filteredBranchOptions = ref<{ label: string; value: string }[]>([])
let inflightRepo = '' // 응답 도착 시 최신 입력과 일치하는지 가드

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
    }>('/api/repos/branches', { params: { repo } })
    if (inflightRepo !== repo) return
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
    repoStatus.value = status === 404 ? 'notfound' : 'error'
    repoStatusMsg.value =
      e?.data?.message ?? (status === 404 ? '레포를 찾을 수 없거나 비공개 레포입니다' : '브랜치 동기화 실패')
  }
}

function onRepoSelected(catalogId: number | null) {
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry || !entry.ownerRepo) return
  loadBranches(entry.ownerRepo).then(() => {
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}

function onBranchFilter(val: string, update: (cb: () => void) => void) {
  update(() => {
    if (!val) {
      filteredBranchOptions.value = branchOptions.value
      return
    }
    const lc = val.toLowerCase()
    filteredBranchOptions.value = branchOptions.value.filter((o) => o.value.toLowerCase().includes(lc))
  })
}

function openCreate() {
  draft.repoCatalogId = null
  draft.githubBranch = ''
  draft.title = ''
  draft.question = ''
  draft.model = DEFAULT_MODEL
  draft.effort = DEFAULT_EFFORT
  draft.mcpCatalogIds = []
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
    !!draft.question.trim(),
)

async function submit() {
  submitting.value = true
  try {
    const created = await useApi<{ id: number }>('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        question: draft.question,
        model: draft.model,
        effort: draft.effort,
        mcpCatalogIds: draft.mcpCatalogIds,
      },
    })
    $q.notify({ type: 'positive', message: '질문이 등록되었습니다 — 답변을 준비합니다' })
    showCreate.value = false
    await navigateTo(`/questions/${created.id}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    // 429(활성 세션 상한)는 실패가 아니라 안내 — 경고 톤.
    $q.notify({ type: st === 429 ? 'warning' : 'negative', message: e?.data?.message ?? '질문 등록 실패' })
  } finally {
    submitting.value = false
  }
}

function fmt(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' })
}

// 테스트에서 q-select 조작 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ openCreate, draft, submit })
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <div class="text-h5">질문</div>
      <q-space />
      <q-toggle
        v-if="auth.isAdmin"
        v-model="all"
        label="전체 보기"
        data-test="all-toggle"
        @update:model-value="refresh"
      />
      <q-btn class="q-ml-md" color="primary" icon="help_outline" label="질문하기" @click="openCreate" />
    </div>

    <q-list bordered separator>
      <q-item v-if="rows.length === 0">
        <q-item-section class="text-grey">아직 질문이 없습니다. 레포에 대해 궁금한 점을 물어보세요.</q-item-section>
      </q-item>
      <q-item
        v-for="q in rows"
        :key="q.id"
        clickable
        data-test="question-row"
        @click="openQuestion(q.id)"
      >
        <q-item-section>
          <q-item-label>{{ q.title }}</q-item-label>
          <q-item-label caption>
            {{ q.repoAlias ?? q.githubRepo }} · {{ q.githubBranch }}
            <span v-if="all"> · {{ q.requesterId }}</span>
          </q-item-label>
        </q-item-section>
        <q-item-section side>
          <div class="row items-center q-gutter-xs">
            <q-chip dense size="sm" outline icon="smart_toy" :label="q.model" />
            <q-chip
              dense
              size="sm"
              :style="{
                backgroundColor: interviewStatusChip(q.statusName)[0],
                color: interviewStatusChip(q.statusName)[1],
              }"
              :label="interviewStatusLabel(q.statusName, 'QUESTION')"
            />
          </div>
          <q-item-label caption class="q-mt-xs">{{ fmt(q.updatedAt) }}</q-item-label>
        </q-item-section>
      </q-item>
    </q-list>

    <!-- 질문하기 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent>
      <q-card style="min-width: 560px">
        <q-card-section>
          <div class="text-h6">질문하기</div>
          <div class="text-caption text-grey-8">
            선택한 레포를 읽고 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
          </div>
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

          <q-input v-model="draft.title" label="제목" outlined dense maxlength="500" />
          <q-input
            v-model="draft.question"
            label="질문"
            type="textarea"
            outlined
            autogrow
            rows="4"
            placeholder="예: 로그인 요청은 어느 컨트롤러가 처리하고 토큰은 어디서 검증하나요?"
          />

          <div class="row q-col-gutter-md">
            <q-select
              v-model="draft.model"
              :options="MODEL_OPTIONS"
              emit-value
              map-options
              outlined
              dense
              label="Claude 모델"
              class="col"
            />
            <q-select
              v-model="draft.effort"
              :options="effortOptions"
              emit-value
              map-options
              outlined
              dense
              label="Effort"
              class="col"
              :hint="draft.model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
            />
          </div>
          <McpPicker v-model="draft.mcpCatalogIds" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showCreate = false" />
          <q-btn
            unelevated
            color="primary"
            icon="send"
            label="질문 등록"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
