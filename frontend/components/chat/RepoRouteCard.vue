<script setup lang="ts">
// 질문 레포 추천 카드 (스펙 2026-11-20 §5.3·§5.4) — 사용자 말풍선 아래 AI 칸에 뜨는 확인 카드.
// 선택 상태·브랜치·DB 선택은 카드가 갖고, 사용자가 시작을 누를 때만 start를 낸다(자동 확정 없음).
// 레포가 정해질 때마다 브랜치와 DB 접속 목록을 함께 받고, 늦게 온 이전 레포의 응답은 버린다.
// 클래스명은 Quasar 반응형 표시 헬퍼(xs/sm/md/lg/xl, gt-*, lt-*, *-hide)와 겹치지 않게 유지할 것.
import { computed, ref } from 'vue'
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import { MAX_DB_SELECTION, dbTypeLabel, fetchDbConnections, type DbConnectionList } from '~/composables/dbConnections'
import { initialBranch, fetchRepoBranches } from '~/composables/repoBranches'
import { routeCardModel, searchRepos, seedDbSelection } from '~/composables/questionRoute'
import type { QuestionRoute, RouteCatalogEntry } from '~/composables/questionRoute'

const props = defineProps<{ route: QuestionRoute; catalog: RouteCatalogEntry[]; starting: boolean }>()
const emit = defineEmits<{
  start: [payload: { repoCatalogId: number; githubBranch: string; dbConnectionIds: number[] | null }]
}>()

const $q = useQuasar()
const model = computed(() => routeCardModel(props.route, props.catalog))
const noRepos = computed(() => model.value.searchable.length === 0)
// CONFIDENT인데 추천이 그새 꺼졌으면 모델이 AMBIGUOUS처럼 내려 준다.
const viewState = computed(() => {
  if (props.route.state === 'CONFIDENT') return model.value.recommended ? 'CONFIDENT' : 'AMBIGUOUS'
  return props.route.state
})
const title = computed(() =>
  viewState.value === 'CONFIDENT'
    ? '이 레포에 대한 질문이 맞나요?'
    : viewState.value === 'NO_MATCH'
      ? '등록된 레포 중에 맞는 곳을 찾지 못했어요'
      : '어느 버전·사이트에 대한 질문인가요?',
)

const selectedId = ref<number | null>(model.value.recommended?.id ?? null)
const selected = computed(() => model.value.searchable.find((e) => e.id === selectedId.value) ?? null)
const isRecommended = computed(() => selected.value != null && selected.value.id === model.value.recommended?.id)

// 브랜치
const branchState = ref<'idle' | 'loading' | 'ok' | 'error'>('idle')
const branchError = ref('')
const branch = ref('')
const branchNames = ref<string[]>([])
const branchOptions = ref<string[]>([])
function onBranchFilter(val: string, update: (fn: () => void) => void) {
  update(() => {
    const q = val.trim().toLowerCase()
    branchOptions.value = q ? branchNames.value.filter((n) => n.toLowerCase().includes(q)) : branchNames.value
  })
}

// DB
const dbList = ref<DbConnectionList | null>(null)
const dbIds = ref<number[]>([])
const dbItems = computed(() => (dbList.value?.enabled ? dbList.value.items.filter((c) => c.enabled) : []))
const showDbRow = computed(() => {
  if (!dbList.value?.enabled) return false
  return dbItems.value.length > 0 || props.route.dbNeeded === true
})
const dialogOpen = ref(false)

function load(id: number) {
  branchState.value = 'loading'
  branchError.value = ''
  branch.value = ''
  branchNames.value = []
  branchOptions.value = []
  dbList.value = null
  dbIds.value = []
  const entry = model.value.searchable.find((e) => e.id === id)
  fetchRepoBranches(id).then(
    (res) => {
      if (selectedId.value !== id) return
      branchNames.value = res.branches.map((b) => b.name)
      branchOptions.value = branchNames.value
      branch.value = initialBranch(res, entry?.defaultBranch ?? null)
      branchState.value = 'ok'
    },
    (e: unknown) => {
      if (selectedId.value !== id) return
      branchError.value = e instanceof Error ? e.message : String(e)
      branchState.value = 'error'
    },
  )
  fetchDbConnections(id).then((list) => {
    if (selectedId.value !== id) return
    dbList.value = list
    dbIds.value = list.enabled ? seedDbSelection(list.items, props.route.dbNeeded) : []
  })
}

function pick(id: number) {
  if (props.starting || id === selectedId.value) return
  if (!model.value.searchable.some((e) => e.id === id)) return
  selectedId.value = id
  searchOpen.value = false
  searchText.value = ''
  load(id)
}
if (selectedId.value != null) load(selectedId.value)

function toggleDb(id: number) {
  if (props.starting) return
  const next = [...dbIds.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1)
  else {
    if (next.length >= MAX_DB_SELECTION) {
      $q.notify({ type: 'warning', message: `DB 연결은 최대 ${MAX_DB_SELECTION}개까지 고를 수 있습니다` })
      return
    }
    next.push(id)
  }
  dbIds.value = next
}

async function onDbSaved(view: { id: number }) {
  const id = selectedId.value
  if (id == null) return
  const list = await fetchDbConnections(id)
  if (selectedId.value !== id) return
  dbList.value = list
  if (!dbIds.value.includes(view.id) && dbIds.value.length < MAX_DB_SELECTION) dbIds.value = [...dbIds.value, view.id]
}

// 칩·검색
// 고를 칩이 하나도 없으면(키 설정 전·질문 기록이 없는 사용자) 버튼만 보이지 않도록 처음부터 검색을 연다.
const noChips =
  !model.value.recommended && !model.value.others.length && !model.value.recent.length && !model.value.similar.length
const searchOpen = ref(viewState.value === 'NO_MATCH' || noChips)
const searchText = ref('')
const showSearch = computed(() => viewState.value === 'NO_MATCH' || searchOpen.value)
const results = computed(() => searchRepos(model.value.searchable, searchText.value))
const searchLabel = computed(() =>
  viewState.value === 'CONFIDENT' ? '🔍 다른 레포' : `🔍 전체 ${model.value.searchable.length}개에서 찾기`,
)
const notSelected = (list: RouteCatalogEntry[]) => list.filter((e) => e.id !== selectedId.value)
const confidentChips = computed(() => {
  const rec = model.value.recommended
  return notSelected([...(rec ? [rec] : []), ...model.value.others])
})
const recentChips = computed(() => notSelected(model.value.recent))
const similarChips = computed(() => notSelected(model.value.similar))

const canStart = computed(
  () =>
    selectedId.value != null &&
    branchState.value === 'ok' &&
    branch.value !== '' &&
    dbList.value != null &&
    !props.starting,
)
const dbLabel = (c: { name: string; dbType: string; scope: string }) =>
  `${c.name} · ${dbTypeLabel(c.dbType)} · ${c.scope === 'REPO' ? '공용' : '내 접속'}`
function onStart() {
  if (!canStart.value || selectedId.value == null) return
  emit('start', {
    repoCatalogId: selectedId.value,
    githubBranch: branch.value,
    dbConnectionIds: dbList.value?.enabled ? [...dbIds.value] : null,
  })
}

// 테스트용 (QuestionComposer 등 선례)
defineExpose({ pick, selectedId, branch, dbIds })
</script>

<template>
  <div class="route-row" data-test="route-card">
    <div class="route-avatar" aria-hidden="true">AI</div>
    <div class="route-card">
      <div v-if="noRepos" class="route-note">등록된 레포가 없습니다 — 관리자에게 문의</div>
      <template v-else>
        <div class="route-title" data-test="route-title">{{ title }}</div>

        <div v-if="selected" class="route-hero" data-test="route-hero">
          <div class="route-hero-name">
            {{ selected.alias }}<span v-if="isRecommended" class="route-tag">추천</span>
          </div>
          <div v-if="selected.description" class="route-desc">{{ selected.description }}</div>
          <div class="route-branch-line">
            <span class="route-label">브랜치</span>
            <q-select
              v-if="branchState === 'ok'"
              v-model="branch"
              :options="branchOptions"
              :disable="starting"
              dense
              borderless
              use-input
              input-debounce="0"
              class="route-branch"
              aria-label="브랜치"
              data-test="route-branch"
              @filter="onBranchFilter"
            >
              <template #no-option>
                <q-item>
                  <q-item-section class="text-grey-7">결과 없음</q-item-section>
                </q-item>
              </template>
            </q-select>
            <span v-else-if="branchState === 'loading'" class="route-label">브랜치 확인 중…</span>
          </div>
          <div v-if="branchState === 'error'" class="route-error" role="alert" data-test="route-branch-error">
            {{ branchError }}
          </div>
        </div>

        <div v-if="viewState === 'CONFIDENT' && confidentChips.length" class="route-chips">
          <span class="route-label">다른 후보</span>
          <q-btn
            v-for="e in confidentChips"
            :key="e.id"
            rounded
            outline
            dense
            no-caps
            size="sm"
            color="primary"
            class="route-chip"
            :label="e.alias"
            :disable="starting"
            :data-test="`route-chip-${e.id}`"
            @click="pick(e.id)"
          />
        </div>
        <template v-else-if="viewState === 'AMBIGUOUS' || viewState === 'UNAVAILABLE'">
          <div v-if="recentChips.length" class="route-chips">
            <span class="route-label">최근 사용</span>
            <q-btn
              v-for="e in recentChips"
              :key="e.id"
              rounded
              outline
              dense
              no-caps
              size="sm"
              color="primary"
              class="route-chip"
              :label="e.alias"
              :disable="starting"
              :data-test="`route-chip-${e.id}`"
              @click="pick(e.id)"
            />
          </div>
          <div v-if="similarChips.length" class="route-chips">
            <span class="route-label">비슷한 후보</span>
            <q-btn
              v-for="e in similarChips"
              :key="e.id"
              rounded
              outline
              dense
              no-caps
              size="sm"
              color="primary"
              class="route-chip"
              :label="e.alias"
              :disable="starting"
              :data-test="`route-chip-${e.id}`"
              @click="pick(e.id)"
            />
          </div>
        </template>

        <div v-if="viewState !== 'NO_MATCH'" class="route-chips">
          <q-btn
            flat
            dense
            no-caps
            size="sm"
            color="grey-8"
            :label="searchLabel"
            :disable="starting"
            :aria-expanded="searchOpen ? 'true' : 'false'"
            data-test="route-search-toggle"
            @click="searchOpen = !searchOpen"
          />
        </div>

        <div v-if="showSearch" class="route-search-box">
          <q-input
            v-model="searchText"
            dense
            outlined
            clearable
            :disable="starting"
            placeholder="레포 이름·설명으로 찾기"
            aria-label="레포 검색"
            data-test="route-search"
          />
          <div class="route-results">
            <q-btn
              v-for="e in results"
              :key="e.id"
              flat
              no-caps
              align="left"
              class="route-result"
              :disable="starting"
              :data-test="`route-result-${e.id}`"
              @click="pick(e.id)"
            >
              <span class="route-result-text">
                <span class="route-result-name">{{ e.alias }}</span>
                <span v-if="e.description" class="route-desc">{{ e.description }}</span>
              </span>
            </q-btn>
            <div v-if="results.length === 0" class="route-label">일치하는 레포가 없습니다</div>
          </div>
        </div>

        <a
          v-if="viewState === 'NO_MATCH'"
          class="route-link"
          href="/requests"
          target="_blank"
          rel="noopener noreferrer"
        >찾는 레포가 없다면 사이트 추가 요청 →</a>

        <template v-if="selected && showDbRow">
          <div class="route-sep" />
          <div class="route-db-row" data-test="route-db-row">
            <template v-if="dbItems.length">
              <div v-for="c in dbItems" :key="c.id" class="route-db-item">
                <q-checkbox
                  :model-value="dbIds.includes(c.id)"
                  :disable="starting"
                  dense
                  :aria-label="dbLabel(c)"
                  :data-test="`route-db-${c.id}`"
                  @update:model-value="toggleDb(c.id)"
                />
                <div class="route-db-text">
                  <span>{{ c.name }}</span>
                  <span class="route-label"> · {{ dbTypeLabel(c.dbType) }} · {{ c.scope === 'REPO' ? '공용' : '내 접속' }}</span>
                </div>
              </div>
              <div v-if="route.dbNeeded === true" class="route-why">DB 조회가 필요한 질문으로 보여 미리 골랐어요</div>
            </template>
            <template v-else>
              <div class="route-label">이 레포에 연결할 DB 접속이 없습니다</div>
              <q-btn
                flat
                dense
                no-caps
                size="sm"
                color="primary"
                icon="add"
                label="내 DB 접속 추가"
                :disable="starting"
                data-test="route-db-add"
                @click="dialogOpen = true"
              />
            </template>
          </div>
        </template>

        <q-btn
          unelevated
          no-caps
          color="primary"
          class="route-go"
          :label="selected ? '이 레포로 질문 시작' : '레포를 골라 주세요'"
          :disable="!canStart"
          :loading="starting"
          data-test="route-start"
          @click="onStart"
        />
      </template>
      <DbConnectionDialog
        v-if="selected"
        v-model="dialogOpen"
        scope="USER"
        :repo-catalog-id="selected.id"
        :editing="null"
        @saved="onDbSaved"
      />
    </div>
  </div>
</template>

<style scoped>
.route-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.route-avatar {
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
.route-card {
  flex: 1;
  min-width: 0;
  max-width: 80%;
  border: 1px solid #d7dde6;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
  background: #fff;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12.5px;
  color: #25303f;
}
/* 폰 폭에서는 아바타 옆 남는 폭을 카드가 모두 쓴다(승인된 목업) */
@media (max-width: 599px) {
  .route-card {
    flex: 1 1 auto;
    max-width: 100%;
  }
}
.route-title {
  font-weight: 600;
}
.route-note {
  color: #5b6b7d;
}
.route-hero {
  border: 2px solid #1976d2;
  border-radius: 10px;
  padding: 8px 10px;
  background: rgba(25, 118, 210, 0.06);
}
.route-hero-name {
  font-weight: 700;
  font-size: 14px;
  word-break: break-word;
}
.route-tag {
  display: inline-block;
  font-size: 10.5px;
  border-radius: 4px;
  padding: 0 5px;
  background: #1976d2;
  color: #fff;
  margin-left: 6px;
  vertical-align: 1px;
}
.route-desc {
  font-size: 11.5px;
  color: #5b6b7d;
  margin-top: 2px;
  word-break: break-word;
}
.route-label {
  font-size: 11.5px;
  color: #5b6b7d;
}
.route-branch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.route-branch {
  min-width: 120px;
  flex: 1;
}
.route-error {
  margin-top: 4px;
  font-size: 11.5px;
  color: #c62828;
}
.route-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.route-search-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.route-results {
  display: flex;
  flex-direction: column;
  max-height: 200px;
  overflow-y: auto;
}
.route-result {
  justify-content: flex-start;
  text-align: left;
}
.route-result-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.route-result-name {
  font-weight: 600;
}
.route-link {
  color: #1976d2;
  font-size: 12px;
}
.route-sep {
  border-top: 1px solid #eceff3;
}
.route-db-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.route-db-item {
  display: flex;
  gap: 8px;
  align-items: center;
}
.route-db-text {
  min-width: 0;
  word-break: break-word;
}
.route-why {
  font-size: 11px;
  color: #00695c;
}
.route-go {
  align-self: flex-end;
}
</style>
