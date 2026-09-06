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
  if (!s) return 'grey-5'
  return (
    { HEALTHY: 'positive', DEGRADED: 'warning', DOWN: 'negative' }[s] ??
    'grey-5'
  )
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
      />
      {{ entry.displayName }}
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
