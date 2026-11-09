<script setup lang="ts">
// 월 구독료 이력과 입력 폼 (스펙 2026-11-03 §6·§7). 상세 다이얼로그가 `구독료 설정`을 열 때만 마운트되고,
// 마운트 때 이력을 한 번 부른다. 저장하면 이력을 다시 부르고 saved를 낸다(다이얼로그가 요약을 다시 부른다).
import { useQuasar } from 'quasar'
import { apiErrorMessage, formatUsd, formatStamp, type SubscriptionFee } from '~/composables/usageReport'

const props = defineProps<{ defaultFrom: string }>()
const emit = defineEmits<{ (e: 'saved'): void }>()

const $q = useQuasar()
const fees = ref<SubscriptionFee[]>([])
const from = ref(props.defaultFrom)
const amount = ref('')
const saving = ref(false)

const sorted = computed(() =>
  [...fees.value].sort((a, b) =>
    a.effectiveFrom === b.effectiveFrom ? b.id - a.id : a.effectiveFrom < b.effectiveFrom ? 1 : -1,
  ),
)

function validDate(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return false
  const d = new Date(`${s}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s
}
const amountNumber = computed(() => (String(amount.value).trim() === '' ? NaN : Number(amount.value)))
const canSave = computed(
  () => !saving.value && validDate(from.value) && Number.isFinite(amountNumber.value) && amountNumber.value > 0,
)

async function loadFees() {
  try {
    fees.value = (await useApi<SubscriptionFee[]>('/api/usage/subscription-fees')) ?? []
  } catch (e: unknown) {
    $q.notify({ type: 'negative', message: apiErrorMessage(e, '구독료 이력을 불러오지 못했습니다') })
  }
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    await useApi('/api/usage/subscription-fees', {
      method: 'POST',
      body: { effectiveFrom: from.value, monthlyFee: amountNumber.value },
    })
    amount.value = ''
    await loadFees()
    emit('saved')
  } catch (e: unknown) {
    $q.notify({ type: 'negative', message: apiErrorMessage(e, '구독료를 저장하지 못했습니다') })
  } finally {
    saving.value = false
  }
}

onMounted(loadFees)
</script>

<template>
  <div class="fee-settings">
    <div class="fee-form">
      <q-input
        v-model="from"
        label="적용 시작일 (YYYY-MM-DD)"
        mask="####-##-##"
        outlined
        dense
        class="fee-field"
        data-test="fee-from"
      />
      <q-input
        v-model="amount"
        label="월 구독료 (USD)"
        type="number"
        inputmode="decimal"
        outlined
        dense
        class="fee-field"
        data-test="fee-amount"
      />
      <q-btn
        unelevated
        color="primary"
        label="저장"
        :loading="saving"
        :disable="!canSave"
        data-test="fee-save"
        @click="save"
      />
    </div>
    <div class="fee-table-wrap">
      <table class="fee-table">
        <thead>
          <tr>
            <th>적용 시작일</th>
            <th>월 구독료</th>
            <th>입력</th>
            <th>입력 시각</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in sorted" :key="f.id" data-test="fee-row">
            <td>{{ f.effectiveFrom }}</td>
            <td>{{ formatUsd(f.monthlyFee) }}</td>
            <td>{{ f.createdBy ?? '' }}</td>
            <td>{{ formatStamp(f.createdAt) }}</td>
          </tr>
          <tr v-if="!sorted.length">
            <td colspan="4" class="text-grey-7">입력된 구독료가 없습니다</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.fee-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 12px;
}
.fee-field {
  flex: 1 1 160px;
}
.fee-table-wrap {
  overflow-x: auto;
}
.fee-table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
}
.fee-table th,
.fee-table td {
  text-align: left;
  padding: 4px 10px 4px 0;
  white-space: nowrap;
}
.fee-table th {
  color: #757575;
  font-weight: 500;
}
@media (max-width: 599px) {
  .fee-form :deep(.q-field__native) {
    font-size: 16px; /* iOS Safari 입력 확대 방지 */
  }
}
</style>
