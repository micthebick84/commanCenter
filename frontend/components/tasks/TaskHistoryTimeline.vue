<script lang="ts">
/** "8월 24일 14:02" — Intl 한국어 시간 포맷의 Node 24 ICU 편차("AM 7시")를 피해 직접 조립한다. */
export function formatKoDateTime(iso: string): string {
  const d = new Date(iso)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${hh}:${mm}`
}
</script>

<script setup lang="ts">
// 진행 이력 타임라인 (스펙 2026-09-06 §6) — GET /api/tasks/{id}/history (최신순, 최대 200).
// 아코디언 안에서 펼쳐질 때 마운트되므로 마운트 시 1회만 조회한다.
import { CHIP } from '~/composables/taskStages'

interface HistoryRow {
  fromStatus: string | null
  toStatus: string | null
  fromLabel: string | null
  toLabel: string
  actorType: string
  actorId: string | null
  reason: string | null
  at: string
}

const props = defineProps<{ taskId: number }>()
const emit = defineEmits<{ (e: 'loaded', count: number): void }>()

const rows = ref<HistoryRow[]>([])
const loading = ref(true)
const error = ref(false)
const PAGE = 6
const shown = ref(PAGE)

onMounted(async () => {
  try {
    const res = await useApi<HistoryRow[]>(`/api/tasks/${props.taskId}/history`)
    // 배열이 아닌 응답(데스크톱 q-expansion-item은 always-mount이므로, 이 히스토리 호출과
    // 무관한 목적의 useApi 스텁을 쓰는 호출부와 한 화면에서 만나면 방어가 필요 — InterviewHistoryCard와 동일 패턴)도
    // 빈 목록으로 처리 — 렌더 중 rows.slice throw 방지
    rows.value = Array.isArray(res) ? res : []
    emit('loaded', rows.value.length)
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
})

function colorOf(status: string | null) {
  return status ? (CHIP[status]?.[1] ?? '#757575') : '#757575'
}
</script>

<template>
  <div class="timeline q-px-md q-pb-sm">
    <div v-if="loading" class="text-caption text-grey-6 q-py-sm">불러오는 중…</div>
    <div v-else-if="error" class="text-caption text-negative q-py-sm">이력을 불러오지 못했습니다</div>
    <div v-else-if="rows.length === 0" class="text-caption text-grey-6 q-py-sm">이력이 없습니다</div>
    <template v-else>
      <div v-for="(r, i) in rows.slice(0, shown)" :key="r.at + i" class="entry" data-test="history-entry">
        <div class="rail"><span class="dot" :style="{ background: i === 0 ? colorOf(r.toStatus) : '#bdbdbd' }" /><span v-if="i < Math.min(shown, rows.length) - 1" class="line" /></div>
        <div class="body">
          <div class="text-caption text-grey-6">{{ formatKoDateTime(r.at) }}</div>
          <div class="text-body2 text-weight-medium">
            <template v-if="r.fromLabel">{{ r.fromLabel }} → </template><span :style="{ color: colorOf(r.toStatus) }">{{ r.toLabel }}</span>
          </div>
          <div class="text-caption text-grey-8">{{ r.reason }}<template v-if="r.actorId"> · {{ r.actorId }}</template></div>
        </div>
      </div>
      <q-btn v-if="rows.length > shown" flat no-caps color="primary" class="full-width" :label="`이전 ${rows.length - shown}건 더 보기`" data-test="history-more" @click="shown += PAGE" />
    </template>
  </div>
</template>

<style scoped>
.entry { display: flex; gap: 12px; }
.rail { display: flex; flex-direction: column; align-items: center; width: 12px; }
.dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 5px; }
.line { flex: 1; width: 2px; background: #e0e0e0; margin-top: 4px; }
.body { flex: 1; display: flex; flex-direction: column; gap: 1px; padding-bottom: 12px; }
</style>
