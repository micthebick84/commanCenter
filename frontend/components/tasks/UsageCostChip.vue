<script setup lang="ts">
// 작업 목록 머리줄의 Claude 사용량 칩(관리자 전용, 스펙 2026-11-03 §7). 마운트 때 한 번, 기간을 바꿀 때 한 번 부른다.
// 자동 갱신(useTaskPolling)은 쓰지 않는다. 누르면 같은 기간으로 상세 다이얼로그가 열린다.
import { useQuasar } from 'quasar'
import UsageCostDialog from '~/components/tasks/UsageCostDialog.vue'
import { PERIOD_OPTIONS, chipText, summaryUrl, type UsagePeriodValue, type UsageSummary } from '~/composables/usageReport'

const $q = useQuasar()
const period = ref<UsagePeriodValue>('month')
const summary = ref<UsageSummary | null>(null)
const open = ref(false)
let seq = 0

async function load() {
  const token = ++seq
  try {
    const res = await useApi<UsageSummary | null>(summaryUrl(period.value))
    if (token === seq) summary.value = res ?? null
  } catch {
    if (token === seq) summary.value = null
  }
}

function setPeriod(v: UsagePeriodValue) {
  period.value = v
  load()
}

onMounted(load)
</script>

<template>
  <div class="cost-chip-wrap row items-center wrap" :class="{ 'cost-chip-tall': $q.screen.lt.md }">
    <q-chip clickable outline color="primary" data-test="cost-chip" @click="open = true">{{ chipText(summary) }}</q-chip>
    <q-btn-toggle
      :model-value="period"
      :options="PERIOD_OPTIONS"
      outline
      dense
      no-caps
      toggle-color="primary"
      aria-label="사용량 기간 단위"
      data-test="cost-chip-period"
      @update:model-value="setPeriod"
    />
    <UsageCostDialog v-if="open" v-model="open" :initial-period="period" @fee-saved="load" />
  </div>
</template>

<style scoped>
.cost-chip-wrap { gap: 4px; }
.cost-chip-wrap :deep(.q-chip) { margin: 0; }
.cost-chip-tall :deep(.q-chip) { min-height: 44px; border-radius: 22px; padding: 0 14px; font-size: 13px; }
.cost-chip-tall :deep(.q-btn) { min-height: 44px; }
</style>
