<script setup lang="ts">
import { useQuasar } from 'quasar'
import { DEFAULT_MODEL, DEFAULT_EFFORT } from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'

definePageMeta({ layout: 'default' })

// 새 질문 (스펙 2026-09-05 §3 NewQuestion). 제목 입력 없음 — 서버가 질문 첫 줄로 생성한다.
// 레포/브랜치 로직은 구 질문하기 다이얼로그(스펙 2026-08-30 §7)에서 그대로 옮김 (inflightRepo 가드 포함).
const $q = useQuasar()
const refreshList = inject<() => void>('questions:refresh', () => {})
const openDrawer = inject<() => void>('questions:open-drawer', () => {})

const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  question: '',
  model: DEFAULT_MODEL,
  effort: DEFAULT_EFFORT,
  mcpCatalogIds: [] as number[],
})
const submitting = ref(false)

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
    // 늦게 도착한 이전 레포의 콜백이 현재 선택을 덮어쓰지 않게 — loadBranches 내부 가드와 동일 기준.
    if (inflightRepo !== entry.ownerRepo) return
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

onMounted(loadRepoCatalog)

const canSubmit = computed(
  () =>
    !submitting.value &&
    draft.repoCatalogId !== null &&
    !!draft.githubBranch &&
    !!draft.question.trim(),
)

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    // title 없음 — 서버가 질문 첫 줄로 생성 (QuestionService.deriveTitle)
    const created = await useApi<{ id: number }>('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        question: draft.question,
        model: draft.model,
        effort: draft.effort,
        mcpCatalogIds: draft.mcpCatalogIds,
      },
    })
    $q.notify({ type: 'positive', message: '질문이 등록되었습니다 — 답변을 준비합니다' })
    refreshList()
    await navigateTo(`/questions/${created.id}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    // 429(활성 세션 상한)는 실패가 아니라 안내 — 경고 톤.
    $q.notify({ type: st === 429 ? 'warning' : 'negative', message: e?.data?.message ?? '질문 등록 실패' })
  } finally {
    submitting.value = false
  }
}

// 테스트에서 q-select/입력창 조작 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ draft, submit, onRepoSelected })
</script>

<template>
  <div class="new-question column no-wrap">
    <div v-if="$q.screen.lt.md" class="row items-center q-px-sm q-pt-sm">
      <q-btn flat dense round icon="menu" data-test="open-drawer" @click="openDrawer" />
    </div>
    <div class="col column items-center justify-center q-px-lg new-question-body">
      <div class="hero text-center">
        <div class="text-h5">무엇이 궁금하세요?</div>
        <div class="text-body2 text-grey-8 q-mt-xs">
          선택한 레포를 읽고 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
        </div>
      </div>
      <div class="composer-wrap q-mt-lg">
        <QuestionComposer
          v-model="draft.question"
          v-model:model="draft.model"
          v-model:effort="draft.effort"
          mode="create"
          placeholder="예: 로그인 요청은 어느 컨트롤러가 처리하고 토큰은 어디서 검증하나요?"
          :can-send="canSubmit"
          :sending="submitting"
          :hint="!$q.screen.lt.md"
          @send="submit"
        >
          <template #top>
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
                    <q-item-section class="text-grey">결과 없음</q-item-section>
                  </q-item>
                </template>
              </q-select>
            </div>
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
.new-question-body {
  padding-bottom: 96px; /* 세로 중앙보다 살짝 위 — 모델 메뉴가 아래로 열릴 공간 */
}
.hero {
  max-width: 760px;
}
.composer-wrap {
  width: 100%;
  max-width: 760px;
}
.mcp-menu {
  min-width: 360px;
  max-width: 480px;
}
</style>
