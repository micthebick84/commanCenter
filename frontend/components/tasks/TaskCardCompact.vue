<script setup lang="ts">
// 모바일 작업 카드 (스펙 2026-09-06 §4.1, 캔버스 Main). 제목 2줄 · 상태 칩 + 4세그먼트 · 메타 1줄 · 다음 할 일 1줄(확인 필요 그룹만).
// ⋮(44px)은 액션 시트를 여는 부모 이벤트 — 카드 탭(open)으로 전파되지 않게 stop.
import {
  CHIP,
  ageOf,
  stageSteps,
  attentionGroup,
  nextAction,
} from '~/composables/taskStages'

export interface CardTask {
  id: number
  title: string
  status: string
  statusLabel: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  createdAt: string
  updatedAt: string
  requesterId?: string
  designRequested?: boolean
  implementation?: { prUrl: string | null; prNumber: number | null } | null
  deployment?: { deployUrl: string | null } | null
}

const props = defineProps<{ task: CardTask; isAdmin: boolean }>()
const emit = defineEmits<{ (e: 'open'): void; (e: 'menu'): void }>()

const chip = computed(() => CHIP[props.task.status] ?? ['#f5f5f5', '#616161'])
const steps = computed(() => stageSteps(props.task))
const current = computed(() =>
  steps.value.find((s) => s.state === 'current' || s.state === 'failed'),
)
const next = computed(() =>
  attentionGroup(props.task, props.isAdmin) === 'attention'
    ? nextAction(props.task, props.isAdmin)
    : null,
)
const deployHost = computed(() => {
  const url = props.task.deployment?.deployUrl
  if (!url) return null
  try {
    return new URL(url).host
  } catch {
    return url
  }
})

function segStyle(state: string, color: string) {
  if (state === 'done') return { background: '#a5d6a7' }
  if (state === 'current') return { background: color }
  if (state === 'failed') return { background: '#c62828' }
  if (state === 'skipped')
    return {
      background:
        'repeating-linear-gradient(90deg, #e0e0e0 0 4px, transparent 4px 8px)',
    }
  return { background: '#eeeeee' }
}
</script>

<template>
  <div class="compact-card" data-test="task-card-compact" @click="emit('open')">
    <div class="row no-wrap items-start">
      <div class="col title-row">
        <span class="card-id">#{{ task.id }}</span>
        <span class="card-title">{{ task.title }}</span>
      </div>
      <q-btn
        flat
        round
        icon="more_vert"
        color="grey-7"
        aria-label="작업 메뉴"
        class="card-menu"
        data-test="card-menu"
        @click.stop="emit('menu')"
      />
    </div>

    <div class="row items-center no-wrap step-row">
      <span
        class="card-status"
        :style="{ background: chip[0], color: chip[1] }"
        >{{ task.statusLabel }}</span
      >
      <div class="col column seg-col">
        <div class="row no-wrap seg-bar">
          <span
            v-for="s in steps"
            :key="s.key"
            class="seg"
            data-test="card-step"
            :data-state="s.state"
            :style="segStyle(s.state, s.color)"
          />
        </div>
        <div class="seg-labels">
          <span
            v-for="s in steps"
            :key="s.key"
            :style="{
              color:
                s.state === 'current' || s.state === 'failed'
                  ? s.color
                  : s.state === 'done'
                    ? '#9e9e9e'
                    : '#bdbdbd',
              fontWeight: s === current ? 700 : 400,
            }"
            >{{ s.label }}</span
          >
        </div>
      </div>
    </div>

    <div class="card-meta">
      <q-icon name="folder" size="14px" color="grey-6" />
      <span class="ellipsis">{{ task.repoAlias ?? task.githubRepo }}</span>
      <span class="dot">·</span>
      <span class="ellipsis">{{ task.githubBranch }}</span>
      <span class="dot">·</span>
      <span>{{ ageOf(task.updatedAt) }} 전</span>
      <template v-if="task.implementation?.prUrl">
        <span class="dot">·</span>
        <a
          :href="task.implementation.prUrl"
          target="_blank"
          rel="noopener"
          class="card-link"
          @click.stop
        >
          PR #{{ task.implementation.prNumber
          }}<q-icon name="open_in_new" size="13px" />
        </a>
      </template>
      <template v-else-if="deployHost">
        <span class="dot">·</span>
        <a
          :href="task.deployment!.deployUrl!"
          target="_blank"
          rel="noopener"
          class="card-link ellipsis"
          @click.stop
        >
          {{ deployHost }}<q-icon name="open_in_new" size="13px" />
        </a>
      </template>
    </div>

    <div v-if="next" class="card-next" data-test="card-next">
      <q-icon name="play_arrow" size="18px" />
      <span class="col"
        >다음 할 일 · {{ next.primary?.label ?? next.text }}</span
      >
      <q-icon name="chevron_right" size="20px" />
    </div>
  </div>
</template>

<style scoped>
.compact-card {
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 6px;
  background: #fff;
  padding: 12px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.07);
  cursor: pointer;
}
.title-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.card-id {
  font-size: 12px;
  font-weight: 700;
  color: #9e9e9e;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.35;
  color: #212121;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-menu {
  min-width: 44px;
  min-height: 44px;
  margin: -10px -12px -10px 0;
}
.step-row {
  gap: 10px;
}
.card-status {
  font-size: 11.5px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 4px;
  white-space: nowrap;
}
.seg-col {
  gap: 3px;
  min-width: 0;
}
.seg-bar {
  gap: 4px;
}
.seg {
  flex: 1 1 0;
  height: 5px;
  border-radius: 3px;
}
.seg-labels {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  font-size: 10.5px;
  line-height: 1.2;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: #757575;
  white-space: nowrap;
  overflow: hidden;
}
.card-meta .ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
}
.dot {
  color: #bdbdbd;
}
.card-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-weight: 600;
  color: #1976d2;
  text-decoration: none;
}
.card-next {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 6px 10px;
  border-radius: 6px;
  background: #fff8e1;
  color: #ef6c00;
  font-size: 12.5px;
  font-weight: 600;
}
</style>
