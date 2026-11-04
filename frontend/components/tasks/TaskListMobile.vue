<script setup lang="ts">
// 모바일 작업 리스트 (스펙 2026-09-06 §4.1, 캔버스 Main): 요약 스트립 + 필터 칩 + 그룹(확인 필요→진행 중→완료→취소됨) + FAB.
// 데이터·API 호출은 부모(pages/tasks/index.vue) 소유 — 여기선 정렬/필터/시트 상태만 가진다.
import TaskCardCompact, { type CardTask } from '~/components/tasks/TaskCardCompact.vue'
import TaskActionSheet from '~/components/tasks/TaskActionSheet.vue'
import QueueStatsBar from '~/components/QueueStatsBar.vue'
import { sortForMobile, ATTENTION_GROUPS, CHIP, type AttentionGroup, type MoveDef } from '~/composables/taskStages'
import { TASK_TYPE_FILTERS, matchesTypeFilter } from '~/composables/taskType'

// viewerId = 보는 사람의 requesterId 값 — 시트가 DB 작업 PR생성의 종료 요청 항목을 등록자에게 보일 때 쓴다(스펙 2026-11-03 §6.9)
const props = defineProps<{ tasks: CardTask[]; isAdmin: boolean; viewerId?: string }>()
const mine = defineModel<boolean>('mine', { default: true })
const emit = defineEmits<{
  (e: 'create'): void
  (e: 'open', task: CardTask): void
  (e: 'move', task: CardTask, move: MoveDef): void
  (e: 'cancel', task: CardTask): void
  (e: 'remove', task: CardTask): void
  (e: 'request-close', task: CardTask): void
}>()

const filter = ref<AttentionGroup | 'all'>('all')
const closedOpen = ref(false)
const statsOpen = ref(false)

// 상세 필터(세부 상태) — 그룹 칩보다 좁혀 볼 때. 받은 목록 안에서만 거른다(서버 조회는 그대로, 리뷰 파인딩 11).
const statusPick = ref<string | null>(null)
const statusSheetOpen = ref(false)
const STATUS_ORDER = Object.keys(CHIP)
const statusOptions = computed(() => {
  const byStatus = new Map<string, { value: string; label: string; count: number }>()
  for (const t of props.tasks) {
    const o = byStatus.get(t.status)
    if (o) o.count++
    else byStatus.set(t.status, { value: t.status, label: t.statusLabel, count: 1 })
  }
  const rank = (s: string) => (STATUS_ORDER.includes(s) ? STATUS_ORDER.indexOf(s) : STATUS_ORDER.length)
  return [...byStatus.values()].sort((a, b) => rank(a.value) - rank(b.value))
})
const statusPickLabel = computed(
  () => props.tasks.find((t) => t.status === statusPick.value)?.statusLabel ?? statusPick.value,
)
function pickStatus(s: string | null) {
  statusPick.value = s
  statusSheetOpen.value = false
}

// 타입 필터(스펙 2026-11-03 §4.3) — 상세 필터와 같은 방식: 서버 조회는 그대로 두고 받은 목록 안에서만 거른다(리뷰 파인딩 11).
// null = 전체 타입, 'NONE' = 타입 없음, 그 밖에는 타입 enum 이름.
const typePick = ref<string | null>(null)
const typeSheetOpen = ref(false)
const typeOptions = computed(() =>
  TASK_TYPE_FILTERS.map((f) => ({ ...f, key: f.value ?? 'all', count: props.tasks.filter((t) => matchesTypeFilter(t, f.value)).length })),
)
const typePickLabel = computed(() =>
  typePick.value == null ? '타입' : (TASK_TYPE_FILTERS.find((f) => f.value === typePick.value)?.label ?? typePick.value),
)
function pickType(v: string | null) {
  typePick.value = v
  typeSheetOpen.value = false
}
function resetFilters() {
  filter.value = 'all'
  statusPick.value = null
  typePick.value = null
}

const groups = computed(() => sortForMobile(props.tasks, props.isAdmin))
const counts = computed(() => Object.fromEntries(groups.value.map((g) => [g.key, g.items.length])) as Record<string, number>)
const visibleGroups = computed(() =>
  (statusPick.value || typePick.value != null
    ? sortForMobile(
        props.tasks.filter((t) => (!statusPick.value || t.status === statusPick.value) && matchesTypeFilter(t, typePick.value)),
        props.isAdmin,
      )
    : groups.value
  ).filter((g) => filter.value === 'all' || g.key === filter.value),
)

// 시트는 연 작업의 id만 들고, 내용은 매 폴링의 최신 목록에서 다시 찾는다 — 연 때의 객체를 들고 있으면
// 배포 중지 직후처럼 상태가 바뀌어도 시트 버튼(삭제 등)이 예전 상태로 남는다. 목록에서 빠지면 마지막 값을 쓴다.
const sheetTaskId = ref<number | null>(null)
const sheetSnapshot = ref<CardTask | null>(null)
const sheetTask = computed<CardTask | null>(
  () => props.tasks.find((t) => t.id === sheetTaskId.value) ?? sheetSnapshot.value,
)
const sheetOpen = ref(false)
function openSheet(task: CardTask) {
  sheetTaskId.value = task.id
  sheetSnapshot.value = task
  sheetOpen.value = true
}
</script>

<template>
  <div class="mobile-list">
    <div class="summary-strip" data-test="summary-strip">
      <span>확인 필요 <b class="c-attention">{{ counts.attention ?? 0 }}</b></span>
      <span class="dot">·</span>
      <span>진행 중 <b class="c-active">{{ counts.active ?? 0 }}</b></span>
      <span class="dot">·</span>
      <span>완료 <b class="c-done">{{ counts.done ?? 0 }}</b></span>
      <q-space />
      <q-btn
        v-if="isAdmin"
        flat
        no-caps
        color="grey-7"
        class="stats-toggle"
        :icon-right="statsOpen ? 'expand_less' : 'expand_more'"
        label="전체 통계"
        data-test="stats-toggle"
        @click="statsOpen = !statsOpen"
      />
    </div>
    <QueueStatsBar v-if="isAdmin && statsOpen" />

    <div class="chip-row">
      <q-chip
        clickable
        :color="filter === 'all' ? 'primary' : undefined"
        :text-color="filter === 'all' ? 'white' : undefined"
        :outline="filter !== 'all'"
        data-test="filter-all"
        @click="filter = 'all'"
      >전체</q-chip>
      <q-chip
        v-for="g in ATTENTION_GROUPS"
        :key="g.key"
        clickable
        :color="filter === g.key ? 'primary' : undefined"
        :text-color="filter === g.key ? 'white' : undefined"
        :outline="filter !== g.key"
        :data-test="`filter-${g.key}`"
        @click="filter = g.key"
      >
        {{ g.label }}<q-badge v-if="counts[g.key]" rounded :color="filter === g.key ? 'white' : 'grey-3'" :text-color="filter === g.key ? 'primary' : 'grey-8'" class="q-ml-xs">{{ counts[g.key] }}</q-badge>
      </q-chip>
      <q-chip
        clickable
        :icon="mine ? 'check' : undefined"
        :color="mine ? 'blue-1' : undefined"
        :text-color="mine ? 'primary' : undefined"
        :outline="!mine"
        data-test="mine-chip"
        @click="mine = !mine"
      >내 작업만</q-chip>
      <q-chip
        clickable
        icon="tune"
        :removable="!!statusPick"
        :color="statusPick ? 'blue-1' : undefined"
        :text-color="statusPick ? 'primary' : undefined"
        :outline="!statusPick"
        data-test="status-filter-chip"
        @click="statusSheetOpen = true"
        @remove="statusPick = null"
      >{{ statusPick ? statusPickLabel : '상세 필터' }}</q-chip>
      <q-chip
        clickable
        icon="category"
        :removable="typePick != null"
        :color="typePick != null ? 'blue-1' : undefined"
        :text-color="typePick != null ? 'primary' : undefined"
        :outline="typePick == null"
        data-test="mobile-type-filter"
        @click="typeSheetOpen = true"
        @remove="typePick = null"
      >{{ typePickLabel }}</q-chip>
    </div>

    <q-dialog v-model="statusSheetOpen" position="bottom">
      <q-card class="status-sheet" data-test="status-sheet">
        <div class="status-sheet__title">상태로 좁혀 보기</div>
        <q-list>
          <q-item clickable :active="!statusPick" data-test="status-option-all" @click="pickStatus(null)">
            <q-item-section>전체 상태</q-item-section>
            <q-item-section side>{{ tasks.length }}</q-item-section>
          </q-item>
          <q-item
            v-for="o in statusOptions"
            :key="o.value"
            clickable
            :active="statusPick === o.value"
            :data-test="`status-option-${o.value}`"
            @click="pickStatus(o.value)"
          >
            <q-item-section>
              <span class="status-dot-label">
                <span class="status-dot" :style="{ background: (CHIP[o.value] ?? ['#f5f5f5', '#616161'])[1] }" />{{ o.label }}
              </span>
            </q-item-section>
            <q-item-section side>{{ o.count }}</q-item-section>
          </q-item>
        </q-list>
      </q-card>
    </q-dialog>

    <q-dialog v-model="typeSheetOpen" position="bottom">
      <q-card class="status-sheet" data-test="type-sheet">
        <div class="status-sheet__title">타입으로 좁혀 보기</div>
        <q-list>
          <q-item
            v-for="o in typeOptions"
            :key="o.key"
            clickable
            :active="typePick === o.value"
            :data-test="`type-option-${o.key}`"
            @click="pickType(o.value)"
          >
            <q-item-section>{{ o.label }}</q-item-section>
            <q-item-section side>{{ o.count }}</q-item-section>
          </q-item>
        </q-list>
      </q-card>
    </q-dialog>

    <div v-if="tasks.length === 0" class="empty" data-test="empty-all">
      <q-icon name="inbox" size="28px" color="grey-5" />
      <div>등록된 작업이 없습니다 — 우하단 + 로 첫 작업을 등록하세요</div>
    </div>
    <div v-else-if="visibleGroups.length === 0" class="empty" data-test="empty-filter">
      <q-icon name="inbox" size="28px" color="grey-5" />
      <div>이 조건에 맞는 작업이 없습니다</div>
      <q-btn
        flat
        no-caps
        color="primary"
        label="전체 보기"
        data-test="empty-reset"
        @click="resetFilters"
      />
    </div>

    <section v-for="g in visibleGroups" :key="g.key" class="group" :data-test="`group-${g.key}`">
      <div v-if="g.key !== 'closed'" class="group-head" :style="{ color: g.color }">{{ g.label }}<span class="rule" /></div>
      <div v-else class="closed-head" data-test="closed-toggle" @click="closedOpen = !closedOpen">
        <span>취소됨 {{ g.items.length }}건</span>
        <q-space />
        <span>{{ closedOpen ? '접기' : '펼치기' }}</span>
        <q-icon :name="closedOpen ? 'expand_less' : 'expand_more'" size="20px" />
      </div>
      <div v-if="g.key !== 'closed' || closedOpen" class="cards">
        <TaskCardCompact
          v-for="task in g.items"
          :key="task.id"
          :task="task"
          :is-admin="isAdmin"
          @open="emit('open', task)"
          @menu="openSheet(task)"
        />
      </div>
    </section>

    <q-btn
      fab
      color="primary"
      icon="add"
      aria-label="작업 등록"
      class="fab"
      data-test="fab-create"
      @click="emit('create')"
    />

    <TaskActionSheet
      v-model="sheetOpen"
      :task="sheetTask"
      :is-admin="isAdmin"
      :viewer-id="viewerId"
      @detail="sheetTask && emit('open', sheetTask)"
      @move="(m) => sheetTask && emit('move', sheetTask, m)"
      @cancel="sheetTask && emit('cancel', sheetTask)"
      @remove="sheetTask && emit('remove', sheetTask)"
      @request-close="sheetTask && emit('request-close', sheetTask)"
    />
  </div>
</template>

<style scoped>
.mobile-list { display: flex; flex-direction: column; gap: 8px; padding-bottom: 88px; }
.summary-strip { display: flex; align-items: center; gap: 4px; min-height: 40px; font-size: 13px; color: #616161; }
.summary-strip b { font-weight: 700; }
.c-attention { color: #ef6c00; } .c-active { color: #1565c0; } .c-done { color: #00695c; }
.dot { color: #bdbdbd; margin: 0 4px; }
.chip-row { display: flex; gap: 4px; overflow-x: auto; white-space: nowrap; margin: 0 -8px; padding: 0 8px 4px; }
.chip-row::-webkit-scrollbar { display: none; }
.chip-row :deep(.q-chip) { min-height: 44px; border-radius: 22px; padding: 0 14px; font-size: 13px; }
.stats-toggle { min-height: 44px; }
.group { display: flex; flex-direction: column; gap: 8px; }
.group-head { display: flex; align-items: center; gap: 8px; padding: 10px 4px 0; font-size: 12px; font-weight: 700; letter-spacing: 0.02em; }
.rule { flex: 1; height: 1px; background: rgba(0, 0, 0, 0.12); }
.closed-head { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 8px 4px; font-size: 13px; color: #757575; cursor: pointer; }
.cards { display: flex; flex-direction: column; gap: 8px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 16px; color: #757575; font-size: 13px; text-align: center; }
.status-sheet { width: 100%; border-radius: 16px 16px 0 0; padding: 12px 0 calc(12px + env(safe-area-inset-bottom)); }
.status-sheet__title { padding: 4px 16px 8px; font-size: 13px; font-weight: 700; color: #616161; }
.status-sheet :deep(.q-item) { min-height: 48px; }
.status-dot-label { display: inline-flex; align-items: center; gap: 8px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.fab { position: fixed; right: 16px; bottom: calc(var(--bottom-nav-height, 0px) + 16px); z-index: 5; }
</style>
