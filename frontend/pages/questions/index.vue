<script setup lang="ts">
import { failMsg } from '~/composables/failMessage'
import type { Ref } from 'vue'
import { useQuasar } from 'quasar'
import { DEFAULT_MODEL, DEFAULT_EFFORT } from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import { MAX_DB_SELECTION, fetchDbConnections, type DbConnectionView } from '~/composables/dbConnections'
import RefRepoChips from '~/components/RefRepoChips.vue'
import RefRepoAddForm from '~/components/RefRepoAddForm.vue'
import { toInputs, type RefRepoView } from '~/composables/refRepos'
import ChatBubble from '~/components/chat/ChatBubble.vue'
import RepoRouteCard from '~/components/chat/RepoRouteCard.vue'
import { fetchQuestionRoute, type QuestionRoute } from '~/composables/questionRoute'
import type { TurnAttachment } from '~/composables/useInterviewStream'

definePageMeta({ layout: 'default' })

// 새 질문 (스펙 2026-09-05 §3 NewQuestion). 제목 입력 없음 — 서버가 질문 첫 줄로 생성한다.
// 레포/브랜치 로직은 구 질문하기 다이얼로그(스펙 2026-08-30 §7)에서 그대로 옮김 (inflightRepo 가드 포함).
const $q = useQuasar()
const refreshList = inject<() => void>('questions:refresh', () => {})
const openDrawer = inject<() => void>('questions:open-drawer', () => {})
// 넓은 화면에서 셸 사이드바를 접었으면 좁은 화면처럼 목록 버튼으로 다시 연다(셸 pages/questions.vue)
const listHidden = inject<Ref<boolean>>('questions:list-hidden', ref(false))

const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  question: '',
  model: DEFAULT_MODEL,
  effort: DEFAULT_EFFORT,
  mcpCatalogIds: [] as number[],
  dbConnectionIds: [] as number[],
  // 참조 repo(스펙 2026-10-22 §7.2): 대상과 별개로 읽기만 하는 다른 레포 — 상한 3은 칩 줄(RefRepoChips)이 건다
  refRepos: [] as RefRepoView[],
})
// 첨부(스펙 2026-09-13 §7): 대기 파일은 QuestionComposer가 검증·누적하고(v-model:files) 등록 성공 시 비운다 — 실패면 유지.
const files = ref<File[]>([])
const submitting = ref(false)

// DB 연결(스펙 2026-10-02 §7): 레포를 고를 때마다 목록을 새로 받고 공용(REPO) 활성 항목을 기본 선택한다.
// 기능이 꺼져 있으면(enabled=false) 버튼을 숨기고 등록 바디에도 싣지 않는다.
const db = reactive({ enabled: false, items: [] as DbConnectionView[] })
async function loadDb(repoId: number, seedDefaults: boolean) {
  const r = await fetchDbConnections(repoId)
  if (draft.repoCatalogId !== repoId) return // 늦게 온 이전 레포 응답
  db.enabled = r.enabled
  db.items = r.items
  const ids = new Set(r.items.map((i) => i.id))
  draft.dbConnectionIds = seedDefaults
    ? r.items.filter((i) => i.scope === 'REPO' && i.enabled).slice(0, MAX_DB_SELECTION).map((i) => i.id)
    : draft.dbConnectionIds.filter((id) => ids.has(id))
}
function reloadDb() {
  if (draft.repoCatalogId !== null) loadDb(draft.repoCatalogId, false)
}

interface RepoCatalogEntry {
  id: number
  alias: string
  host: string
  ownerRepo: string | null
  defaultBranch: string | null
  description: string | null
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
let inflightRepo = 0 // 응답 도착 시 최신 선택(catalogId)과 일치하는지 가드

function resetBranchState() {
  repoStatus.value = 'empty'
  repoStatusMsg.value = ''
  branches.value = []
  defaultBranch.value = null
  filteredBranchOptions.value = []
  draft.githubBranch = ''
}

async function loadBranches(repo: number) {
  inflightRepo = repo
  repoStatus.value = 'loading'
  repoStatusMsg.value = '브랜치 불러오는 중...'
  try {
    const res = await useApi<{
      repo: string
      defaultBranch: string | null
      branches: BranchEntry[]
    }>('/api/repos/branches', { params: { catalogId: repo } })
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
      e?.data?.message ?? (status === 404 ? '레포를 찾을 수 없거나 접근 권한이 없습니다' : failMsg('브랜치 동기화'))
  }
}

function onRepoSelected(catalogId: number | null) {
  resetBranchState()
  db.enabled = false
  db.items = []
  draft.dbConnectionIds = []
  removeRef(catalogId) // 새 대상과 같은 레포를 참조로 두지 않는다 — 그 참조만 빠지고 나머지는 유지 (스펙 2026-10-22 §7.2)
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry) return
  if (entry.host === 'other') {
    repoStatus.value = 'error'
    repoStatusMsg.value = '이 레포는 지원하지 않는 호스트입니다 (GitHub/사내 GitLab만 가능)'
    return
  }
  loadDb(entry.id, true)
  loadBranches(entry.id).then(() => {
    // 늦게 도착한 이전 레포의 콜백이 현재 선택을 덮어쓰지 않게 — loadBranches 내부 가드와 동일 기준.
    if (inflightRepo !== entry.id) return
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

// 참조 repo (스펙 2026-10-22 §7.2, B안): 대상 레포·브랜치 선택 바로 아래 편집 칩 줄 + "＋ 참조 추가"가 그 아래에 펼치는 추가 폼.
// 추가 폼 후보에서는 대상과 이미 고른 참조를 뺀다(지원하지 않는 호스트는 폼이 스스로 뺀다) — 서버도 등록 때 같은 규칙으로 검증한다.
const refAddOpen = ref(false)
const refExcludeIds = computed(() => [
  ...(draft.repoCatalogId !== null ? [draft.repoCatalogId] : []),
  ...draft.refRepos.map((r) => r.catalogId),
])
function toggleRefAdd() {
  refAddOpen.value = !refAddOpen.value
}
function addRef(picked: RefRepoView) {
  draft.refRepos.push(picked)
  refAddOpen.value = false // 추가하면 접는다
}
function removeRef(catalogId: number | null) {
  draft.refRepos = draft.refRepos.filter((r) => r.catalogId !== catalogId)
}

// 레포 정하는 방식 (스펙 2026-11-20 §5.1): auto = 질문을 보고 추천 카드로 확인받기(기본), manual = 지금의 레포·브랜치 선택.
// 기억한 값은 마운트 뒤에만 읽는다 — SSR과 첫 클라이언트 렌더는 항상 auto라 하이드레이션이 어긋나지 않는다.
type RepoMode = 'auto' | 'manual'
const MODE_STORAGE_KEY = 'netis-maker-question-repo-mode'
const mode = ref<RepoMode>('auto')
// compose = 입력 중, routing = 추천 호출 중, card = 추천 카드 확인 중 (auto에서만 compose 밖으로 간다)
const phase = ref<'compose' | 'routing' | 'card'>('compose')
const route = ref<QuestionRoute | null>(null)
let routeSeq = 0 // 질문 고치기 뒤에 늦게 온 추천 응답을 버리는 가드

function readStoredMode(): RepoMode {
  try {
    return localStorage.getItem(MODE_STORAGE_KEY) === 'manual' ? 'manual' : 'auto'
  } catch {
    return 'auto'
  }
}
function storeMode(next: RepoMode) {
  try {
    localStorage.setItem(MODE_STORAGE_KEY, next)
  } catch {
    // 저장 못 해도 이번 화면에서는 바뀐 모드로 동작한다
  }
}
function setMode(next: RepoMode) {
  if (mode.value === next) return
  if (next === 'auto') {
    // 직접 모드에서 고른 것을 모두 비운다 — 자동 모드의 등록 바디에는 레포·참조 선택이 섞이지 않는다
    inflightRepo = 0 // 진행 중이던 브랜치 응답이 비운 값을 다시 채우지 않게
    draft.repoCatalogId = null
    onRepoSelected(null)
    draft.refRepos = []
    refAddOpen.value = false
  }
  mode.value = next
  storeMode(next)
}

onMounted(() => {
  mode.value = readStoredMode()
  loadRepoCatalog()
})

const canSubmit = computed(() =>
  mode.value === 'auto'
    ? !submitting.value && !!draft.question.trim()
    : !submitting.value && draft.repoCatalogId !== null && !!draft.githubBranch && !!draft.question.trim(),
)

async function submit() {
  if (!canSubmit.value) return
  if (mode.value === 'auto') {
    await startRouting()
    return
  }
  // title 없음 — 서버가 질문 첫 줄로 생성 (QuestionService.deriveTitle)
  await createQuestion({
    repoCatalogId: draft.repoCatalogId,
    githubBranch: draft.githubBranch,
    question: draft.question,
    model: draft.model,
    effort: draft.effort,
    mcpCatalogIds: draft.mcpCatalogIds,
    ...(db.enabled ? { dbConnectionIds: draft.dbConnectionIds } : {}),
    // 참조는 하나라도 골랐을 때만 싣는다 — 없으면 필드를 생략해 등록 바디가 예전과 같다 (스펙 2026-10-22 §7.2)
    ...(draft.refRepos.length > 0 ? { refRepos: toInputs(draft.refRepos) } : {}),
  })
}

// 자동 모드: 질문을 보내면 레포를 추천받고 카드로 확인받는다. 세션은 카드에서 시작을 눌러야 만들어진다(자동 확정 없음).
async function startRouting() {
  if (phase.value !== 'compose') return
  const seq = ++routeSeq
  route.value = null
  phase.value = 'routing'
  const res = await fetchQuestionRoute(draft.question) // 던지지 않는다 — 실패는 UNAVAILABLE 카드
  if (seq !== routeSeq || phase.value !== 'routing') return // 그 사이 질문 고치기를 눌렀다
  route.value = res
  phase.value = 'card'
}

function editQuestion() {
  if (submitting.value) return
  routeSeq++
  route.value = null
  phase.value = 'compose'
}

function startFromCard(payload: { repoCatalogId: number; githubBranch: string; dbConnectionIds: number[] | null }) {
  const current = route.value
  if (submitting.value || phase.value !== 'card' || !current) return
  // 참조 repo는 싣지 않는다(자동 모드에는 참조 선택이 없다)
  return createQuestion({
    repoCatalogId: payload.repoCatalogId,
    githubBranch: payload.githubBranch,
    question: draft.question,
    model: draft.model,
    effort: draft.effort,
    mcpCatalogIds: draft.mcpCatalogIds,
    ...(payload.dbConnectionIds !== null ? { dbConnectionIds: payload.dbConnectionIds } : {}),
    ...(current.routeId !== null ? { routeId: current.routeId } : {}),
  })
}

// 대화 모양일 때 내 말풍선 아래에 붙는 첨부 칩(이름·크기만, 내려받기 없음)
const bubbleAttachments = computed<TurnAttachment[]>(() =>
  files.value.map((f) => ({ fileName: f.name, sizeBytes: f.size })),
)

// 등록 요청 한 곳 — 직접 모드의 submit()과 카드의 startFromCard()가 같이 쓴다 (성공·실패 처리 동일)
async function createQuestion(meta: Record<string, unknown>) {
  submitting.value = true
  try {
    let created: { id: number }
    if (files.value.length > 0) {
      // 파일이 있으면 multipart(meta = 위 JSON 그대로 + files). Content-Type은 $fetch가 boundary와 함께 설정 (스펙 2026-09-13 §5.1)
      const form = new FormData()
      form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
      for (const f of files.value) form.append('files', f, f.name)
      created = await useApi<{ id: number }>('/api/questions', { method: 'POST', body: form })
    } else {
      created = await useApi<{ id: number }>('/api/questions', { method: 'POST', body: meta })
    }
    files.value = []
    $q.notify({ type: 'positive', message: '질문이 등록되었습니다 — 답변을 준비합니다' })
    refreshList()
    await navigateTo(`/questions/${created.id}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    // 429(활성 세션 상한)는 실패가 아니라 안내 — 경고 톤.
    $q.notify({ type: st === 429 ? 'warning' : 'negative', message: e?.data?.message ?? failMsg('질문 등록') })
  } finally {
    submitting.value = false
  }
}

// 테스트에서 q-select/입력창 조작 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ draft, submit, onRepoSelected, db, mode, phase, route, startFromCard })
</script>

<template>
  <div class="new-question column no-wrap">
    <div v-if="$q.screen.lt.md || listHidden" class="row items-center q-px-sm q-pt-sm">
      <q-btn flat dense round icon="menu" aria-label="질문 목록 열기" data-test="open-drawer" @click="openDrawer" />
    </div>
    <div
      class="col column no-wrap items-center q-px-lg new-question-body"
      :class="{ 'is-conversation': phase !== 'compose' }"
    >
      <div v-if="phase === 'compose'" class="hero text-center">
        <div class="text-h5">무엇이 궁금하세요?</div>
        <div v-if="mode === 'auto'" class="text-body2 text-grey-8 q-mt-xs">
          질문을 보내면 맞는 레포를 찾아 확인받은 뒤 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
        </div>
        <div v-else class="text-body2 text-grey-8 q-mt-xs">
          선택한 레포를 읽고 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
        </div>
      </div>
      <!-- 대화 모양(자동 모드에서 질문을 보낸 뒤): 내 말풍선 → 질문 고치기 → 대기 표시 또는 추천 카드 -->
      <div v-else class="conversation-wrap" data-test="route-conversation">
        <ChatBubble role="user" :content="draft.question" :attachments="bubbleAttachments" />
        <div class="route-edit-row">
          <q-btn
            flat
            dense
            no-caps
            size="sm"
            color="grey-8"
            label="✎ 질문 고치기"
            :disable="submitting"
            data-test="route-edit"
            @click="editQuestion"
          />
        </div>
        <div v-if="phase === 'routing'" class="route-waiting" role="status" aria-label="레포를 찾는 중" data-test="route-waiting">
          <div class="route-waiting-avatar" aria-hidden="true">AI</div>
          <div class="route-waiting-bubble">
            <span class="route-dots" aria-hidden="true"><span class="route-dot" /><span class="route-dot" /><span class="route-dot" /></span>
          </div>
        </div>
        <RepoRouteCard
          v-else-if="route"
          :route="route"
          :catalog="repoCatalog"
          :starting="submitting"
          @start="startFromCard"
        />
      </div>
      <div class="composer-wrap" :class="phase === 'compose' ? 'q-mt-lg' : 'q-mt-sm'">
        <QuestionComposer
          v-model="draft.question"
          v-model:model="draft.model"
          v-model:effort="draft.effort"
          v-model:files="files"
          :placeholder="
            phase === 'compose'
              ? '예: 로그인 요청은 어느 컨트롤러가 처리하고 토큰은 어디서 검증하나요?'
              : '레포를 확정하면 답변이 시작됩니다'
          "
          :can-send="canSubmit"
          :sending="submitting"
          :disabled="phase !== 'compose'"
          :hint="!$q.screen.lt.md"
          @send="submit"
        >
          <template v-if="phase === 'compose'" #top>
            <div v-if="mode === 'auto'" class="row items-center no-wrap q-pa-sm" data-test="repo-auto-line">
              <span class="text-body2 text-grey-8">레포: 질문을 보면 찾아 드려요</span>
              <q-space />
              <q-btn
                flat
                dense
                no-caps
                size="sm"
                color="primary"
                label="직접 고르기"
                data-test="repo-mode-manual"
                @click="setMode('manual')"
              />
            </div>
            <template v-else>
            <div class="row justify-end q-px-sm q-pt-sm">
              <q-btn
                flat
                dense
                no-caps
                size="sm"
                color="primary"
                label="자동으로 찾기"
                data-test="repo-mode-auto"
                @click="setMode('auto')"
              />
            </div>
            <div class="row q-col-gutter-md q-pa-sm">
              <q-select
                v-model="draft.repoCatalogId"
                :options="repoOptions"
                :loading="repoCatalogLoading"
                label="레포 (별칭 선택)"
                outlined
                dense
                emit-value
                map-options
                class="col-12 col-md-6"
                data-test="repo-select"
                :hint="repoCatalog.length === 0 ? '등록된 레포 없음 — 관리자에게 문의' : '관리자가 등록한 레포 중 선택'"
                @update:model-value="onRepoSelected"
              >
                <template #no-option>
                  <q-item>
                    <q-item-section class="text-grey-7">등록된 레포가 없습니다</q-item-section>
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
                class="col-12 col-md-6"
                :hint="
                  repoStatus === 'ok'
                    ? repoStatusMsg
                    : repoStatus === 'notfound' || repoStatus === 'error'
                      ? repoStatusMsg
                      : '레포 선택 후 브랜치 선택 가능'
                "
                @filter="onBranchFilter"
              >
                <template #no-option>
                  <q-item>
                    <q-item-section class="text-grey-7">결과 없음</q-item-section>
                  </q-item>
                </template>
              </q-select>
            </div>
            <!-- 참조 repo 줄 (스펙 2026-10-22 §7.2) — 선택 줄 아래. "＋ 참조 추가"를 누르면 추가 폼이 그 아래에 펼쳐진다.
                 입력창 툴바는 390px에서 이미 꽉 차 있어 건드리지 않는다. -->
            <div class="q-px-sm q-pb-sm">
              <RefRepoChips :items="draft.refRepos" editable @remove="removeRef" @add="toggleRefAdd" />
              <div v-if="refAddOpen" class="ref-add-panel">
                <RefRepoAddForm :catalog="repoCatalog" :exclude-ids="refExcludeIds" @add="addRef" />
              </div>
            </div>
            </template>
          </template>
          <template #tools>
            <!-- xs(<600px)에서는 라벨이 두 줄로 꺾이므로 아이콘만 — 이름은 aria-label로 유지 (2026-09-06 모바일 QA) -->
            <q-btn
              flat
              dense
              no-caps
              icon="extension"
              :label="$q.screen.xs ? undefined : 'MCP 도구'"
              aria-label="MCP 도구"
              :disable="phase !== 'compose'"
              data-test="mcp-button"
            >
              <q-badge v-if="draft.mcpCatalogIds.length" color="primary" floating>
                {{ draft.mcpCatalogIds.length }}
              </q-badge>
              <q-menu>
                <div class="mcp-menu">
                  <McpPicker v-model="draft.mcpCatalogIds" />
                </div>
              </q-menu>
            </q-btn>
            <q-btn
              v-if="db.enabled && draft.repoCatalogId !== null"
              flat
              dense
              no-caps
              icon="storage"
              :label="$q.screen.xs ? undefined : 'DB'"
              aria-label="DB 연결"
              data-test="db-button"
            >
              <q-badge v-if="draft.dbConnectionIds.length" color="primary" floating>
                {{ draft.dbConnectionIds.length }}
              </q-badge>
              <q-menu>
                <DbConnectionPicker
                  v-model="draft.dbConnectionIds"
                  :items="db.items"
                  :repo-catalog-id="draft.repoCatalogId"
                  @reload="reloadDb"
                />
              </q-menu>
            </q-btn>
          </template>
        </QuestionComposer>
        <ClaudeUsagePanel v-if="$q.screen.lt.md" variant="strip" class="q-mt-sm" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.new-question {
  height: 100%;
}
/* 본문은 줄바꿈하지 않고(no-wrap) 내용이 영역보다 커지면 스크롤한다. Quasar `.column`은 기본이 flex-wrap이라, 참조 칩 줄·추가 폼으로
   입력창이 높아져 hero와 함께 남은 높이를 넘으면 입력창이 오른쪽 새 열로 밀려 나가 셸(overflow:hidden)에 가려졌다(390px 프리뷰 실측).
   세로 중앙은 justify-center 대신 자동 마진으로 — justify-center는 넘칠 때 위쪽이 잘려 스크롤로도 못 닿는다. */
.new-question-body {
  overflow-y: auto;
  padding-bottom: 96px; /* 세로 중앙보다 살짝 위 — 모델 메뉴가 아래로 열릴 공간 */
}
.new-question-body > :first-child {
  margin-top: auto;
}
.new-question-body > :last-child {
  margin-bottom: auto;
}
.hero {
  max-width: 760px;
}
/* 대화 모양: 위에서부터 쌓이고, 대화만 스크롤하며 입력창은 그 아래에 둔다 */
.new-question-body.is-conversation {
  overflow-y: hidden;
  padding-bottom: 24px;
}
.new-question-body.is-conversation > :first-child {
  margin-top: 0;
}
.new-question-body.is-conversation > :last-child {
  margin-bottom: 0;
}
.conversation-wrap {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  max-width: 760px;
  overflow-y: auto;
  padding-top: 8px;
}
.route-edit-row {
  display: flex;
  justify-content: flex-end;
  margin: -4px 0 8px;
}
.route-waiting {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.route-waiting-avatar {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  background: #e3f2fd;
  color: #1565c0;
}
.route-waiting-bubble {
  background: #eef2f8;
  border-radius: 14px;
  border-bottom-left-radius: 4px;
  padding: 11px 14px;
}
.route-dots {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.route-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7f8ea3;
  animation: route-bounce 1.2s infinite ease-in-out;
}
.route-dot:nth-child(2) {
  animation-delay: 0.18s;
}
.route-dot:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes route-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .route-dot {
    animation: none;
    opacity: 0.6;
  }
}
.composer-wrap {
  width: 100%;
  max-width: 760px;
}
.mcp-menu {
  min-width: 360px;
  max-width: 480px;
}
/* 추가 폼은 칩 줄 아래에 인라인으로 펼친다. 폼 루트는 q-menu에 넣는 용도라 폭이 340px·여백 16px로 고정돼 있어,
   입력창 안쪽(390px 화면에서 약 300px)에서는 줄 폭에 맞추고 윤곽선으로 입력창과 구분한다. */
.ref-add-panel {
  box-sizing: border-box;
  max-width: 360px;
  margin-top: 8px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
}
.ref-add-panel :deep(.ref-add-form) {
  width: auto;
  padding: 12px;
}
</style>
