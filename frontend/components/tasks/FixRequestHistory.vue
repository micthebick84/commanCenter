<script setup lang="ts">
// 수정 요청 기록 카드 (스펙 2026-11-03 §6·§8) — GET /api/tasks/{id}/fix-requests(최신순). 요청이 하나도 없으면 카드 자체를 그리지 않는다.
// 목록은 로컬 ref에 보관 — 5초 폴링이 task를 갈아끼워도 펼침 상태가 유지되고, 다시 읽을 때만 refreshKey를 올린다.
import { CHIP } from '~/composables/taskStages'
import { fixOutcomeLabel, fixRequestStatusLabel, type FixRequestView } from '~/composables/taskFix'
import { implementationModeLabel } from '~/composables/implementationMode'
import { formatKoDateTime } from '~/components/tasks/TaskHistoryTimeline.vue'

const props = defineProps<{ taskId: string; refreshKey: number }>()

const items = ref<FixRequestView[]>([])
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  try {
    const res = await useApi<FixRequestView[]>(`/api/tasks/${props.taskId}/fix-requests`)
    // 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지. 더 새로 시작한 조회가 있으면 이 응답은 버린다.
    if (seq === loadSeq) items.value = Array.isArray(res) ? res : []
  } catch {
    // 403/404 등은 조용히 빈 목록 처리 — 카드만 안 보인다
    if (seq === loadSeq) items.value = []
  }
}

onMounted(load)
watch(() => [props.taskId, props.refreshKey], load)

const STATUS_COLOR: Record<string, string> = {
  PENDING: 'orange-8',
  APPROVED: 'positive',
  REJECTED: 'negative',
  WITHDRAWN: 'grey-7',
}
const OUTCOME_COLOR: Record<string, string> = {
  DEPLOYED: CHIP.DEPLOYED?.[1] ?? '#00695c',
  DEPLOY_FAILED: CHIP.DEPLOY_FAILED?.[1] ?? '#ad1457',
  ABORTED: '#616161',
}

function modeText(r: FixRequestView): string | null {
  return r.implementationMode ? implementationModeLabel(r.implementationMode) : null
}
</script>

<template>
  <q-card v-if="items.length" flat bordered class="q-mb-md" data-test="fix-history">
    <q-card-section class="row items-center q-gutter-sm">
      <q-icon name="build" color="primary" size="sm" />
      <div class="text-h6">수정 요청 기록</div>
      <q-chip dense size="sm" outline :label="`${items.length}건`" />
    </q-card-section>
    <q-separator />
    <div v-for="(r, i) in items" :key="r.id" data-test="fix-history-item">
      <q-separator v-if="i > 0" />
      <q-card-section>
        <div class="row items-center" style="gap: 6px">
          <span v-if="r.round != null" class="text-subtitle2 text-weight-bold">{{ r.round }}회차</span>
          <q-chip dense size="sm" square :color="STATUS_COLOR[r.status] ?? 'grey-7'" text-color="white" :label="fixRequestStatusLabel(r.status)" />
          <q-chip v-if="modeText(r)" dense size="sm" outline icon="account_tree" :label="modeText(r) ?? undefined" />
          <q-chip
            v-if="fixOutcomeLabel(r.outcome)"
            dense
            size="sm"
            outline
            :style="{ color: OUTCOME_COLOR[r.outcome ?? ''] ?? '#616161' }"
            :label="fixOutcomeLabel(r.outcome) ?? undefined"
          />
          <span v-if="r.attempt >= 2" class="text-caption text-grey-7">{{ r.attempt }}회째 시도</span>
          <q-space />
          <span class="text-caption text-grey-7">{{ r.requestedBy }} · {{ formatKoDateTime(r.requestedAt) }}</span>
        </div>
        <div class="md-scroll q-mt-xs">
          <pre style="white-space: pre-wrap; margin: 0; font-family: inherit" data-test="fix-history-body">{{ r.body }}</pre>
        </div>
        <div v-if="r.status === 'REJECTED' && r.decisionReason" class="q-mt-xs text-caption text-negative" data-test="fix-history-reason">
          반려 사유: {{ r.decisionReason }}<template v-if="r.decidedBy"> · {{ r.decidedBy }}</template>
        </div>
        <div v-else-if="r.decidedBy && r.decidedAt" class="q-mt-xs text-caption text-grey-7">
          {{ fixRequestStatusLabel(r.status) }} · {{ r.decidedBy }} · {{ formatKoDateTime(r.decidedAt) }}
        </div>
      </q-card-section>
    </div>
  </q-card>
</template>
