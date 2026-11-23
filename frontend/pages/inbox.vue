<script setup lang="ts">
// 처리함 (스펙 2026-11-23) — 관리자가 확인할 작업을 묶음 순서로 세우고(왼쪽 목록), 고른 건을 그 자리에서 처리한다(오른쪽 처리 영역).
// 고른 건은 주소 ?task= 에 둔다(replace — 건 전환·자동 이동·j/k가 방문 기록을 쌓지 않는다).
// 다음 건으로 넘어가는 계기는 처리 영역에서 누른 동작의 성공(done)뿐이다 — 폴링·SSE로 본 상태 변화로는 넘어가지 않는다.
// 폰(lt.md)은 목록만 보이고, 고른 건은 전체 화면 창에서 처리한다(창을 닫으면 쿼리를 지운다).
import { onMounted, onUnmounted } from 'vue'
import { useQuasar } from 'quasar'
import InboxTaskPane from '~/components/inbox/InboxTaskPane.vue'
import { CHIP, ageOf } from '~/composables/taskStages'
import { taskTypeLabel } from '~/composables/taskType'
import {
  buildInbox,
  flattenInbox,
  inboxListParams,
  isInboxCandidate,
  isTruncated,
  neighborId,
  pickNext,
  pruneHandled,
  readAutoNext,
  shouldIgnoreShortcut,
  writeAutoNext,
  type HandledMark,
  type InboxRow,
} from '~/composables/taskInbox'
import type { RefDraftEntry } from '~/composables/useInterviewRefDraft'

definePageMeta({ layout: 'default' })

interface InboxListPage {
  content: InboxRow[]
  totalElements?: number
}

const route = useRoute()
const $q = useQuasar()
const isPhone = computed(() => $q.screen.lt.md)

const { data: page, refresh } = useTaskPolling<InboxListPage>(() => useApi('/api/tasks', { params: inboxListParams() }))

// 처리한 건 — 목록 행이 같은 status·updatedAt인 동안만 숨긴다. 목록이 올 때마다 바뀐(또는 사라진) 행의 기록을 지운다.
const handled = ref<HandledMark[]>([])
watch(page, (p) => {
  if (p) handled.value = pruneHandled(handled.value, p.content)
})

const rows = computed(() => page.value?.content ?? [])
const groups = computed(() => buildInbox(rows.value, handled.value))
const flat = computed(() => flattenInbox(groups.value))
const truncated = computed(() => isTruncated(page.value))

const selectedId = computed<number | null>(() => {
  const raw = route.query.task
  const v = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isInteger(v) && v > 0 ? v : null
})
const selectedRow = computed(() => rows.value.find((r) => r.id === selectedId.value) ?? null)

// navigateTo는 템플릿에서 부르지 않는다(`_ctx.navigateTo`로 컴파일된다) — 쿼리는 이 두 함수로만 바꾼다.
function select(id: number) {
  navigateTo({ query: { task: String(id) } }, { replace: true })
}
function clearSelection() {
  navigateTo({ query: {} }, { replace: true })
}

// 고르기 규칙(스펙 §5.5) — 쿼리의 작업은 조회되면 목록에 없어도 그대로 둔다. 404면 그 건 말고 맨 위, 없으면 쿼리를 지운다.
// 쿼리가 없으면 넓은 화면만 맨 위를 고른다 — 폰은 고르면 창이 열리므로 닫았을 때 다시 열리지 않게 목록에 둔다.
// 404는 목록보다 먼저 올 수 있어(처리 영역이 먼저 마운트된다) 기억해 두었다가 목록이 오면 고른다.
const missingId = ref<number | null>(null)
function onMissing() {
  missingId.value = selectedId.value
}
watch(selectedId, () => {
  missingId.value = null
})
watch(
  [page, selectedId, isPhone, missingId],
  () => {
    if (!page.value) return
    const sel = selectedId.value
    if (sel != null && sel === missingId.value) {
      missingId.value = null
      const next = pickNext(flat.value, sel)
      if (next != null) select(next)
      else clearSelection()
      return
    }
    if (sel == null && !isPhone.value) {
      // 처리 기록이 남은 건(목록이 다시 읽혀 지워지기 전)은 저절로 다시 고르지 않는다 — 방금 처리하고 쿼리를 지운 건이 되살아나지 않게
      const top = flat.value.find((r) => !handled.value.some((h) => h.id === r.id))
      if (top) select(top.id)
    }
  },
  { immediate: true },
)

// 처리 후 다음 건으로 — 기본 켬, 브라우저에 기억. SSR과 첫 그림이 같도록 마운트 뒤에 읽는다.
const autoNext = ref(true)
function setAutoNext(on: boolean) {
  autoNext.value = on
  writeAutoNext(on)
}

function onDone(paneMark: HandledMark) {
  // 같은 처리의 알림이 두 번 오면(연타·알림 겹침) 한 번만 넘어간다
  if (handled.value.some((h) => h.id === paneMark.id)) return
  // 숨기는 기준은 그때 목록의 status·updatedAt이다(스펙 §5.4) — 목록 폴링이 상세보다 늦으면 처리 영역이 본 값으로는 행이 숨지 않는다.
  // 목록에 그 건의 확인 필요 행이 없을 때만 처리 영역의 값을 쓴다.
  const row = rows.value.find((r) => r.id === paneMark.id && isInboxCandidate(r))
  const mark = row ? { id: row.id, status: row.status, updatedAt: row.updatedAt } : paneMark
  handled.value = [...handled.value, mark]
  refresh()
  if (!autoNext.value) return
  const next = pickNext(flat.value, mark.id)
  if (next != null) {
    select(next)
    return
  }
  clearSelection()
  if (isPhone.value) $q.notify({ type: 'positive', message: '모두 처리했습니다' })
}

const canPrev = computed(() => {
  const id = neighborId(flat.value, selectedId.value, -1)
  return id != null && id !== selectedId.value
})
const canNext = computed(() => {
  const id = neighborId(flat.value, selectedId.value, 1)
  return id != null && id !== selectedId.value
})
function move(dir: 1 | -1) {
  const id = neighborId(flat.value, selectedId.value, dir)
  if (id != null && id !== selectedId.value) select(id)
}

// j 다음 / k 이전 — 입력칸 안이나 다이얼로그(폰 처리 창·확인창)가 열려 있으면 무시한다
function onKeydown(e: KeyboardEvent) {
  if (shouldIgnoreShortcut(e, !!document.querySelector('.q-dialog'))) return
  move(e.key === 'j' ? 1 : -1)
}
onMounted(() => {
  autoNext.value = readAutoNext()
  window.addEventListener('keydown', onKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

// 폰: 고른 건이 있으면 전체 화면 창이 열려 있다. 닫으면 쿼리를 지운다.
const sheetOpen = computed({
  get: () => isPhone.value && selectedId.value != null,
  set: (open: boolean) => {
    if (!open) clearSelection()
  },
})

// 작업을 오가도 남는 것(처리함을 떠나면 사라진다) — 세션 id별 쓰던 답변, 보내지 않은 참조 변경
const answerDrafts = reactive(new Map<number, string>())
const refDraftStore = reactive(new Map<number, RefDraftEntry>())

function chipStyle(status: string) {
  const c = CHIP[status] ?? ['#f5f5f5', '#616161']
  return { background: c[0], color: c[1] }
}
</script>

<template>
  <q-page :class="isPhone ? 'inbox-page' : 'inbox-page inbox-page--wide row no-wrap'">
    <div class="inbox-list" :class="{ 'inbox-list--side': !isPhone }" data-test="inbox-list">
      <div class="row items-center no-wrap inbox-list__head">
        <span class="text-subtitle2" data-test="inbox-total">확인 필요 {{ flat.length }}건</span>
        <q-space />
        <q-toggle
          :model-value="autoNext"
          dense
          size="sm"
          label="처리 후 다음 건으로"
          data-test="inbox-auto-next"
          @update:model-value="setAutoNext"
        />
      </div>
      <q-banner v-if="truncated" dense class="bg-orange-1 text-orange-10 inbox-truncated" data-test="inbox-truncated">
        최근 변경된 200건만 봤습니다 — 오래된 작업은 작업 목록에서 확인하세요
      </q-banner>

      <div v-if="!page" class="flex flex-center q-pa-lg">
        <q-spinner-dots color="primary" size="2em" />
      </div>
      <div v-else-if="!flat.length" class="inbox-empty" data-test="inbox-empty">
        <div>확인할 작업이 없습니다</div>
        <NuxtLink to="/tasks">작업 목록</NuxtLink>
      </div>
      <template v-else>
        <div v-for="g in groups" :key="g.key" class="inbox-group" data-test="inbox-group">
          <div class="row items-center inbox-group__head">
            <span data-test="inbox-group-label">{{ g.label }}</span>
            <span class="inbox-group__count" data-test="inbox-group-count">{{ g.items.length }}</span>
          </div>
          <q-list>
            <q-item
              v-for="t in g.items"
              :key="t.id"
              clickable
              :active="t.id === selectedId"
              active-class="inbox-row--active"
              class="inbox-row"
              data-test="inbox-row"
              :data-id="t.id"
              @click="select(t.id)"
            >
              <q-item-section>
                <q-item-label class="ellipsis inbox-row__title"><span class="inbox-row__id">#{{ t.id }}</span> {{ t.title }}</q-item-label>
                <q-item-label caption class="row items-center no-wrap inbox-row__meta">
                  <span class="inbox-chip" :style="chipStyle(t.status)">{{ t.statusLabel }}</span>
                  <span v-if="t.taskType" class="inbox-chip inbox-chip--type">{{ taskTypeLabel(t.taskType) }}</span>
                  <span class="ellipsis">{{ t.repoAlias ?? t.githubRepo }}</span>
                </q-item-label>
              </q-item-section>
              <q-item-section side top class="inbox-row__age">{{ ageOf(t.updatedAt) }}</q-item-section>
            </q-item>
          </q-list>
        </div>
      </template>
    </div>

    <div v-if="!isPhone" class="col inbox-main">
      <InboxTaskPane
        v-if="selectedId != null"
        :key="selectedId"
        :task-id="selectedId"
        :answer-drafts="answerDrafts"
        :ref-draft-store="refDraftStore"
        :can-prev="canPrev"
        :can-next="canNext"
        @done="onDone"
        @navigate="move"
        @missing="onMissing"
      />
    </div>

    <!-- no-route-dismiss: 고른 건이 주소(?task=)라 자동 이동·이전/다음·404 → 맨 위가 모두 주소를 바꾼다. QDialog의 기본값(주소가 바뀌면 닫기)이면
         그때마다 창이 닫히고(update:modelValue false) 쿼리까지 지워진다 — 창은 내용만 바꾼다(스펙 §5.6). -->
    <q-dialog v-if="isPhone" v-model="sheetOpen" maximized no-route-dismiss transition-show="slide-up" transition-hide="slide-down">
      <div class="inbox-sheet column no-wrap bg-white" data-test="inbox-sheet">
        <q-toolbar class="inbox-sheet__bar">
          <q-btn flat round dense icon="close" aria-label="닫기" data-test="inbox-sheet-close" @click="sheetOpen = false" />
          <q-toolbar-title class="text-subtitle1">
            <template v-if="selectedId != null">#{{ selectedId }} {{ selectedRow?.title ?? '' }}</template>
          </q-toolbar-title>
          <q-btn flat round dense icon="chevron_left" aria-label="이전" :disable="!canPrev" data-test="inbox-sheet-prev" @click="move(-1)" />
          <q-btn flat round dense icon="chevron_right" aria-label="다음" :disable="!canNext" data-test="inbox-sheet-next" @click="move(1)" />
        </q-toolbar>
        <q-separator />
        <InboxTaskPane
          v-if="selectedId != null"
          :key="selectedId"
          class="col"
          fill
          :task-id="selectedId"
          :answer-drafts="answerDrafts"
          :ref-draft-store="refDraftStore"
          :can-prev="canPrev"
          :can-next="canNext"
          @done="onDone"
          @navigate="move"
          @missing="onMissing"
        />
      </div>
    </q-dialog>
  </q-page>
</template>

<style scoped>
/* 넓은 화면: 목록과 처리 영역이 따로 세로 스크롤한다(위 바 50px) */
.inbox-page--wide {
  height: calc(100vh - 50px);
  overflow: hidden;
}
.inbox-list--side {
  width: 320px;
  flex: none;
  overflow-y: auto;
  border-right: 1px solid rgba(0, 0, 0, 0.12);
}
.inbox-main {
  min-width: 0;
  overflow-y: auto;
}
.inbox-list__head {
  padding: 10px 12px;
  gap: 8px;
}
.inbox-truncated {
  margin: 0 12px 8px;
  border-radius: 4px;
}
.inbox-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 16px;
  color: #616161;
}
.inbox-group__head {
  padding: 8px 12px 4px;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: #616161;
}
.inbox-group__count {
  font-weight: 600;
  color: #9e9e9e;
}
.inbox-row {
  padding: 8px 12px;
  min-height: 56px;
}
.inbox-row--active {
  background: #e3f2fd;
  color: inherit;
}
.inbox-row__title {
  font-size: 14px;
  font-weight: 600;
}
.inbox-row__id {
  color: #757575;
}
.inbox-row__meta {
  gap: 6px;
  margin-top: 4px;
  min-width: 0;
}
.inbox-row__age {
  font-size: 12px;
  color: #757575;
}
.inbox-chip {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
}
.inbox-chip--type {
  color: #455a64;
  background: rgba(69, 90, 100, 0.1);
}
.inbox-sheet {
  height: 100%;
  padding-bottom: env(safe-area-inset-bottom);
}
.inbox-sheet__bar {
  flex: none;
  min-height: 52px;
  padding-top: env(safe-area-inset-top);
}
</style>
