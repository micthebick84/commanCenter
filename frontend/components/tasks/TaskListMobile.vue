<script setup lang="ts">
// 모바일 작업 리스트 (스펙 2026-09-06 §4.1, 캔버스 Main): 요약 스트립 + 필터 칩 + 그룹(확인 필요→진행 중→완료→취소됨) + FAB.
// 데이터·API 호출은 부모(pages/tasks/index.vue) 소유 — 여기선 정렬/필터/시트 상태만 가진다.
import TaskCardCompact, { type CardTask } from '~/components/tasks/TaskCardCompact.vue'
import TaskActionSheet from '~/components/tasks/TaskActionSheet.vue'
import QueueStatsBar from '~/components/QueueStatsBar.vue'
import { sortForMobile, ATTENTION_GROUPS, type AttentionGroup, type MoveDef } from '~/composables/taskStages'

const props = defineProps<{ tasks: CardTask[]; isAdmin: boolean }>()
const mine = defineModel<boolean>('mine', { default: true })
const emit = defineEmits<{
  (e: 'create'): void
  (e: 'open', task: CardTask): void
  (e: 'move', task: CardTask, move: MoveDef): void
  (e: 'cancel', task: CardTask): void
  (e: 'remove', task: CardTask): void
}>()

const filter = ref<AttentionGroup | 'all'>('all')
const closedOpen = ref(false)
const statsOpen = ref(false)

const groups = computed(() => sortForMobile(props.tasks, props.isAdmin))
const counts = computed(() => Object.fromEntries(groups.value.map((g) => [g.key, g.items.length])) as Record<string, number>)
const visibleGroups = computed(() =>
  groups.value.filter((g) => filter.value === 'all' || g.key === filter.value),
)

const sheetTask = ref<CardTask | null>(null)
const sheetOpen = ref(false)
function openSheet(task: CardTask) {
  sheetTask.value = task
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
        dense
        no-caps
        size="sm"
        color="grey-7"
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
    </div>

    <div v-if="visibleGroups.length === 0" class="empty">
      <q-icon name="inbox" size="28px" color="grey-5" />
      <div>등록된 작업이 없습니다 — 우하단 + 로 첫 작업을 등록하세요</div>
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
      @detail="sheetTask && emit('open', sheetTask)"
      @move="(m) => sheetTask && emit('move', sheetTask, m)"
      @cancel="sheetTask && emit('cancel', sheetTask)"
      @remove="sheetTask && emit('remove', sheetTask)"
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
.group { display: flex; flex-direction: column; gap: 8px; }
.group-head { display: flex; align-items: center; gap: 8px; padding: 10px 4px 0; font-size: 12px; font-weight: 700; letter-spacing: 0.02em; }
.rule { flex: 1; height: 1px; background: rgba(0, 0, 0, 0.12); }
.closed-head { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 8px 4px; font-size: 13px; color: #757575; cursor: pointer; }
.cards { display: flex; flex-direction: column; gap: 8px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 16px; color: #757575; font-size: 13px; text-align: center; }
.fab { position: fixed; right: 16px; bottom: calc(var(--bottom-nav-height, 0px) + 16px); z-index: 5; }
</style>
