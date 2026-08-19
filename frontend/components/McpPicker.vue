<script setup lang="ts">
interface CatalogEntry {
  id: number
  name: string
  displayName: string
  url: string
  transport: string
  description: string | null
  lastCheckStatus: string | null
}

const model = defineModel<number[]>({ default: () => [] })

const catalog = ref<CatalogEntry[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    catalog.value = await useApi<CatalogEntry[]>('/api/mcp-catalog')
  } catch {
    catalog.value = []
  } finally {
    loading.value = false
  }
}

function toggle(id: number) {
  const next = [...model.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1)
  else next.push(id)
  model.value = next
}

function dotColor(s: string | null): string {
  if (!s) return 'grey-5'
  return { HEALTHY: 'positive', DEGRADED: 'warning', DOWN: 'negative' }[s] ?? 'grey-5'
}

const selectedHasDown = computed(() =>
  catalog.value.some((c) => model.value.includes(c.id) && c.lastCheckStatus === 'DOWN'),
)

onMounted(load)
defineExpose({ toggle })   // 테스트에서 칩 클릭 대신 직접 호출 (기본 접힘 상태라 DOM에 없음)
</script>

<template>
  <q-expansion-item
    icon="extension"
    label="이 인터뷰에 추가할 MCP 도구"
    :caption="
      catalog.length === 0
        ? '관리자 카탈로그 비어있음'
        : `${model.length}개 선택 · 활성 ${catalog.length}개 중`
    "
    header-class="text-grey-9 bg-grey-2"
    dense
  >
    <q-banner v-if="catalog.length === 0" class="bg-grey-1 text-grey-8 q-mt-sm" dense>
      <template #avatar><q-icon name="info" /></template>
      관리자가 등록한 SSE MCP 카탈로그가 없습니다.
    </q-banner>
    <div v-else class="q-pa-sm">
      <q-chip
        v-for="entry in catalog"
        :key="entry.id"
        clickable
        :color="model.includes(entry.id) ? 'indigo-6' : 'grey-3'"
        :text-color="model.includes(entry.id) ? 'white' : 'grey-9'"
        :icon="model.includes(entry.id) ? 'check' : 'add'"
        @click="toggle(entry.id)"
      >
        <q-badge
          rounded
          :color="dotColor(entry.lastCheckStatus)"
          class="q-mr-xs"
          style="min-height: 8px; min-width: 8px; padding: 0"
        />
        {{ entry.displayName }}
        <q-tooltip>
          <div><strong>{{ entry.name }}</strong> ({{ entry.transport }})</div>
          <div style="max-width: 360px; word-break: break-all">{{ entry.url }}</div>
          <div class="q-mt-xs">헬스: <strong>{{ entry.lastCheckStatus ?? 'UNKNOWN' }}</strong></div>
        </q-tooltip>
      </q-chip>
      <div
        v-if="selectedHasDown"
        class="text-caption text-negative q-mt-sm row items-center q-gutter-xs"
      >
        <q-icon name="warning" size="14px" />
        <span>DOWN 상태 MCP가 포함됨 — 연결 실패해도 인터뷰는 진행되지만 해당 도구는 사용 안 됨.</span>
      </div>
    </div>
  </q-expansion-item>
</template>
