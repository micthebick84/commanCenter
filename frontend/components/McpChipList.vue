<script setup lang="ts">
// MCP 카탈로그 칩 목록 + 헬스 배지 + DOWN 경고 — McpPicker의 접힘/flat 두 모드가 공유하는 표시 전용 블록.
export interface McpCatalogEntry {
  id: number
  name: string
  displayName: string
  url: string
  transport: string
  description: string | null
  lastCheckStatus: string | null
}

const props = defineProps<{ catalog: McpCatalogEntry[]; selected: number[] }>()
const emit = defineEmits<{ (e: 'toggle', id: number): void }>()

function dotColor(s: string | null): string {
  if (!s) return 'grey-8'
  return (
    { HEALTHY: 'positive', DEGRADED: 'deep-orange-10', DOWN: 'negative' }[s] ??
    'grey-8'
  )
}
// 점 색만으로 상태를 알리지 않도록 문제 상태는 글자로도 붙인다(정상은 점만 — 2026-11-11 검토)
const HEALTH_TEXT: Record<string, string> = { DEGRADED: '느림', DOWN: '연결 안 됨' }
function healthText(s: string | null): string {
  return s ? (HEALTH_TEXT[s] ?? '') : '확인 전'
}

const selectedHasDown = computed(() =>
  props.catalog.some(
    (c) => props.selected.includes(c.id) && c.lastCheckStatus === 'DOWN',
  ),
)
</script>

<template>
  <div class="mcp-chip-list">
    <q-chip
      v-for="entry in catalog"
      :key="entry.id"
      clickable
      :color="selected.includes(entry.id) ? 'indigo-6' : 'grey-3'"
      :text-color="selected.includes(entry.id) ? 'white' : 'grey-9'"
      :icon="selected.includes(entry.id) ? 'check' : 'add'"
      @click="emit('toggle', entry.id)"
    >
      <q-badge
        rounded
        :color="dotColor(entry.lastCheckStatus)"
        class="q-mr-xs"
        style="min-height: 8px; min-width: 8px; padding: 0"
        aria-hidden="true"
      />
      {{ entry.displayName }}
      <span v-if="healthText(entry.lastCheckStatus)" class="q-ml-xs" data-test="mcp-health-text">({{ healthText(entry.lastCheckStatus) }})</span>
      <q-tooltip>
        <div>
          <strong>{{ entry.name }}</strong> ({{ entry.transport }})
        </div>
        <div style="max-width: 360px; word-break: break-all">
          {{ entry.url }}
        </div>
        <div class="q-mt-xs">
          헬스: <strong>{{ entry.lastCheckStatus ?? 'UNKNOWN' }}</strong>
        </div>
      </q-tooltip>
    </q-chip>
    <div
      v-if="selectedHasDown"
      class="text-caption text-negative q-mt-sm row items-center q-gutter-xs"
      data-test="mcp-down-warning"
    >
      <q-icon name="warning" size="14px" />
      <span
        >DOWN 상태 MCP가 포함됨 — 연결 실패해도 인터뷰는 진행되지만 해당 도구는
        사용 안 됨.</span
      >
    </div>
  </div>
</template>
