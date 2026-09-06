<script setup lang="ts">
// MCP 카탈로그 선택. 기본은 접힘(q-expansion-item, 질문 탭 메뉴 안), flat=true면 승인 다이얼로그처럼
// 접힘 없이 라벨 + 칩을 바로 노출하고 빈 카탈로그는 안내 한 줄 + 카탈로그 링크로 축소한다(2026-09-06).
import McpChipList, { type McpCatalogEntry as CatalogEntry } from '~/components/McpChipList.vue'

const props = withDefaults(defineProps<{ flat?: boolean }>(), { flat: false })
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

onMounted(load)
defineExpose({ toggle })   // 테스트에서 칩 클릭 대신 직접 호출 (기본 접힘 상태라 DOM에 없음)
</script>

<template>
  <div v-if="props.flat" class="mcp-flat" data-test="mcp-flat">
    <div class="field-label">MCP 도구 <span class="text-grey-6 text-weight-regular">(선택)</span></div>
    <div
      v-if="catalog.length === 0"
      class="row items-center no-wrap text-caption text-grey-7"
      data-test="mcp-empty"
    >
      <span>등록된 MCP 도구가 없습니다.</span>
      <q-btn
        flat
        dense
        no-caps
        size="sm"
        color="primary"
        label="MCP 카탈로그"
        icon-right="open_in_new"
        to="/admin/mcp-catalog"
        class="q-ml-xs"
        data-test="mcp-catalog-link"
      />
    </div>
    <McpChipList v-else :catalog="catalog" :selected="model" @toggle="toggle" />
  </div>

  <q-expansion-item
    v-else
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
      <McpChipList :catalog="catalog" :selected="model" @toggle="toggle" />
    </div>
  </q-expansion-item>
</template>

<style scoped>
.field-label {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #616161;
  margin-bottom: 6px;
}
</style>
