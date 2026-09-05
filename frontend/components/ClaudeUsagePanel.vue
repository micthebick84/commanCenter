<script setup lang="ts">
// Claude Code 구독 사용량 (스펙 2026-09-05 §3·§4.1). panel = 좌측 목록 하단 카드, strip = 모바일 입력창 아래 한 줄.
// 5초 폴링(useTaskPolling) — 값은 SDK 턴이 돌 때만 갱신되므로 "N분 전 갱신"을 항상 함께 보여준다.
import {
  LIMIT_LABELS,
  LIMIT_ORDER,
  usageColor,
  usagePercent,
  formatReset,
  formatAgo,
  type ClaudeUsageResponse,
  type ClaudeLimit,
} from '~/composables/claudeUsage'

const props = withDefaults(
  defineProps<{
    variant?: 'panel' | 'strip'
    /** strip 전용: 현재 대화의 컨텍스트 %. null이면 항목 생략. */
    contextPct?: number | null
  }>(),
  { variant: 'panel', contextPct: null },
)

const { data } = useTaskPolling<ClaudeUsageResponse>(() => useApi('/api/usage/claude'))

// "N분 전"·"초기화됨" 판정용 시계 — 폴링과 별개로 30초마다 재계산
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  clock = setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})
onUnmounted(() => {
  if (clock) clearInterval(clock)
})

const limits = computed<ClaudeLimit[]>(() =>
  Array.isArray(data.value?.limits) ? (data.value!.limits as ClaudeLimit[]) : [],
)

const rows = computed(() =>
  LIMIT_ORDER.flatMap((type) => {
    const l = limits.value.find((x) => x.limitType === type)
    if (!l) return []
    const pct = usagePercent(l, now.value)
    return [
      {
        type,
        label: LIMIT_LABELS[type] ?? type,
        shortLabel: type === 'five_hour' ? '세션' : '이번 주',
        pct,
        color: usageColor(pct),
        reset: formatReset(l.resetsAt, now.value),
      },
    ]
  }),
)

const updatedLabel = computed(() => {
  const latest = limits.value
    .map((l) => new Date(l.updatedAt).getTime())
    .filter((t) => Number.isFinite(t))
    .sort((a, b) => b - a)[0]
  return latest ? formatAgo(new Date(latest).toISOString(), now.value) : ''
})
</script>

<template>
  <div
    v-if="props.variant === 'strip'"
    class="usage-strip row items-center no-wrap text-caption text-grey-7"
    data-test="usage-strip"
  >
    <div v-for="r in rows.slice(0, 2)" :key="r.type" class="row items-center no-wrap strip-item">
      <span>{{ r.shortLabel }}</span>
      <q-linear-progress
        :value="r.pct / 100"
        :color="r.color"
        track-color="grey-4"
        rounded
        size="3px"
        class="strip-bar"
      />
      <span class="strip-pct">{{ r.pct }}%</span>
    </div>
    <div v-if="props.contextPct != null" class="row items-center no-wrap strip-item">
      <span>컨텍스트</span>
      <q-linear-progress
        :value="props.contextPct / 100"
        :color="usageColor(props.contextPct)"
        track-color="grey-4"
        rounded
        size="3px"
        class="strip-bar"
      />
      <span class="strip-pct">{{ props.contextPct }}%</span>
    </div>
    <span v-if="rows.length === 0 && props.contextPct == null">사용량 미수집</span>
  </div>

  <div v-else class="usage-panel" data-test="usage-panel">
    <div class="row items-center justify-between">
      <span class="panel-title text-caption text-weight-medium text-grey-7">Claude Code 사용량</span>
      <span class="text-caption text-grey-7">{{ updatedLabel }}</span>
    </div>
    <div v-if="rows.length === 0" class="text-caption text-grey-7 q-mt-sm" data-test="usage-empty">
      아직 수집된 사용량이 없습니다 — 첫 질문 후 표시됩니다.
    </div>
    <div v-for="r in rows" :key="r.type" class="q-mt-sm" :data-test="`usage-row-${r.type}`">
      <div class="row items-center justify-between usage-row-head">
        <span>{{ r.label }}</span>
        <span class="text-weight-medium">{{ r.pct }}%</span>
      </div>
      <q-linear-progress
        :value="r.pct / 100"
        :color="r.color"
        track-color="grey-4"
        rounded
        size="4px"
        class="q-mt-xs"
      />
      <div class="text-caption text-grey-7 reset-label">{{ r.reset }}</div>
    </div>
  </div>
</template>

<style scoped>
.usage-panel {
  border-top: 1px solid rgba(0, 0, 0, 0.12);
  margin-top: 8px;
  padding: 12px 6px 4px;
}
.usage-row-head {
  font-size: 12px;
  line-height: 16px;
}
.reset-label {
  margin-top: 3px;
  line-height: 14px;
}
.usage-strip {
  gap: 12px;
  padding: 6px 6px 0;
  white-space: nowrap;
}
.strip-item {
  gap: 5px;
}
.strip-bar {
  width: 40px;
}
.strip-pct {
  color: rgba(0, 0, 0, 0.87);
  font-weight: 500;
}
</style>
