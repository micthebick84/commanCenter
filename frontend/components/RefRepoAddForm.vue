<script setup lang="ts">
// 참조 repo 추가 폼 (스펙 2026-10-22 §7.1) — RefRepoChips의 "＋ 참조 추가"가 여는 작은 창(q-menu·q-dialog) 안에 그대로 넣는다.
// 레포 선택(카탈로그에서 대상·이미 고른 레포·지원하지 않는 호스트(other)를 뺀 것만) → 브랜치 선택
// (GET /api/repos/branches — 새 질문 화면 pages/questions/index.vue loadBranches와 같은 호출) → "추가"가
// { catalogId, alias, branch }를 add로 올린다(커밋·시각·사유는 서버가 채운다). 3개 상한은 부모(RefRepoChips의 max)가 건다.
// 브랜치 기본값: 카탈로그 defaultBranch → 없으면 응답의 defaultBranch → 그것도 없으면 첫 브랜치.
// 늦은 응답 가드: 레포를 바꾸는 중에 먼저 고른 레포의 응답이 늦게 와도 브랜치·목록·상태를 덮지 않는다(새 질문 화면의
// inflightRepo와 같은 취지 — 여기서는 요청마다 번호를 매겨, 같은 레포를 다시 골랐을 때도 마지막 요청만 반영한다).
// 추가하면 폼을 비운다(같은 선택이 두 번 나가지 않게). 닫는 것은 부모가 add를 받아 정한다.
// 루트는 <div>다 — q-dialog 안에서 루트가 <aside> 등이면 Quasar CSS의 pointer-events 예외(.q-dialog__inner > div)에 안 걸려
// 다이얼로그 안 클릭이 백드롭으로 샌다(CLAUDE.md). 타입은 상대 경로로 가져온다(vitest는 .nuxt 없이 돈다).
import type { RefRepoView, RepoCatalogOption } from '../composables/refRepos'

const props = defineProps<{
  catalog: RepoCatalogOption[]
  excludeIds: number[]
}>()
const emit = defineEmits<{
  add: [ref: RefRepoView]
}>()

interface BranchEntry {
  name: string
  sha: string
}
interface BranchesResponse {
  repo?: string
  defaultBranch: string | null
  branches: BranchEntry[]
}
type BranchStatus = 'empty' | 'loading' | 'ok' | 'error'
/** 레포를 고르기 전 브랜치 칸의 안내 — 새 질문 화면과 같은 문구. */
const IDLE_HINT = '레포 선택 후 브랜치 선택 가능'

// 고를 수 있는 레포 — 대상·이미 고른 레포(excludeIds)와 지원하지 않는 호스트를 뺀다
const available = computed(() => props.catalog.filter((c) => c.host !== 'other' && !props.excludeIds.includes(c.id)))
const repoOptions = computed(() => available.value.map((c) => ({ label: c.alias, value: c.id })))

const repoId = ref<number | null>(null)
const selectedEntry = computed(() => available.value.find((c) => c.id === repoId.value))

const branch = ref('')
const branches = ref<BranchEntry[]>([])
const defaultBranch = ref<string | null>(null)
const status = ref<BranchStatus>('empty')
const statusMsg = ref(IDLE_HINT) // 브랜치 칸 hint: 안내 → 불러오는 중 → 개수 또는 실패 사유
const branchFilter = ref('')

const branchOptions = computed(() => {
  const lc = branchFilter.value.toLowerCase()
  return branches.value
    .filter((b) => !lc || b.name.toLowerCase().includes(lc))
    .map((b) => ({ label: b.name === defaultBranch.value ? `${b.name} (기본)` : b.name, value: b.name }))
})
function onBranchFilter(val: string, update: (cb: () => void) => void) {
  update(() => {
    branchFilter.value = val
  })
}

const canAdd = computed(() => !!selectedEntry.value && status.value === 'ok' && !!branch.value)

let requestSeq = 0 // 가장 최근 조회 번호 — 응답이 도착했을 때 자기 번호가 아니면 버린다

function clearBranches() {
  branch.value = ''
  branches.value = []
  defaultBranch.value = null
  branchFilter.value = ''
  status.value = 'empty'
  statusMsg.value = IDLE_HINT
}

/** 선택과 조회 중이던 요청을 모두 버린다(요청 번호를 올려 늦은 응답을 무시). */
function reset() {
  requestSeq++
  repoId.value = null
  clearBranches()
}

async function onRepoSelected(id: number | null) {
  const seq = ++requestSeq
  clearBranches() // 이전 레포의 브랜치가 새 레포에 남지 않게 먼저 비운다
  const entry = id == null ? undefined : available.value.find((c) => c.id === id)
  repoId.value = entry ? entry.id : null
  if (!entry) return
  status.value = 'loading'
  statusMsg.value = '브랜치 불러오는 중...'
  try {
    const res = await useApi<BranchesResponse>('/api/repos/branches', { params: { catalogId: entry.id } })
    if (seq !== requestSeq) return
    branches.value = res?.branches ?? []
    defaultBranch.value = res?.defaultBranch ?? null
    branch.value = entry.defaultBranch || res?.defaultBranch || branches.value[0]?.name || ''
    status.value = 'ok'
    statusMsg.value = `${branches.value.length}개 브랜치 · 방금 동기화`
  } catch (e: any) {
    if (seq !== requestSeq) return
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    status.value = 'error'
    statusMsg.value =
      e?.data?.message ?? (st === 404 ? '레포를 찾을 수 없거나 접근 권한이 없습니다' : '브랜치 동기화 실패')
  }
}

// 고른 레포가 그새 제외 목록에 들어가면(다른 경로로 같은 레포가 참조로 추가되거나 대상이 바뀜) 선택을 비운다
watch(available, () => {
  if (repoId.value !== null && !selectedEntry.value) reset()
})

function submit() {
  const entry = selectedEntry.value
  if (!entry || !canAdd.value) return
  emit('add', { catalogId: entry.id, alias: entry.alias, branch: branch.value })
  reset()
}
</script>

<template>
  <div class="ref-add-form" data-test="ref-add-form">
    <q-select
      :model-value="repoId"
      :options="repoOptions"
      label="참조 레포"
      outlined
      dense
      emit-value
      map-options
      data-test="ref-add-repo"
      @update:model-value="onRepoSelected"
    >
      <template #no-option>
        <q-item>
          <q-item-section class="text-grey">고를 수 있는 레포가 없습니다</q-item-section>
        </q-item>
      </template>
    </q-select>
    <q-select
      v-model="branch"
      :options="branchOptions"
      :disable="status !== 'ok'"
      :loading="status === 'loading'"
      label="브랜치"
      outlined
      dense
      use-input
      input-debounce="0"
      emit-value
      map-options
      class="q-mt-sm"
      :hint="statusMsg"
      data-test="ref-add-branch"
      @filter="onBranchFilter"
    >
      <template #no-option>
        <q-item>
          <q-item-section class="text-grey">결과 없음</q-item-section>
        </q-item>
      </template>
    </q-select>
    <div class="row justify-end q-mt-sm">
      <q-btn unelevated no-caps color="primary" label="추가" :disable="!canAdd" data-test="ref-add-submit" @click="submit" />
    </div>
  </div>
</template>

<style scoped>
.ref-add-form {
  box-sizing: border-box;
  width: min(340px, calc(100vw - 32px));
  padding: 16px;
  background: #fff;
}
</style>
