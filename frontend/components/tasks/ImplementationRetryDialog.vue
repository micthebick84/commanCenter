<script setup lang="ts">
// 구현 재시도 (스펙 2026-10-15 §5.5·§7) — 방식을 다시 골라 구현실패 작업을 구현대기로 돌린다(관리자).
import { useQuasar } from 'quasar'
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import type { ImplementationMode } from '~/composables/implementationMode'

const props = defineProps<{ modelValue: boolean; taskId: string; currentMode: string }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

const $q = useQuasar()
const mode = ref<ImplementationMode>('DIRECT')
const submitting = ref(false)

// 열 때마다 지금 방식으로 시작
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      mode.value = (['DIRECT', 'REVIEW', 'SDD'].includes(props.currentMode) ? props.currentMode : 'DIRECT') as ImplementationMode
    }
  },
  { immediate: true },
)

async function submit() {
  if (submitting.value) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/implementation/retry`, {
      method: 'POST',
      body: { implementationMode: mode.value },
    })
    $q.notify({ type: 'positive', message: '구현 재시도 — 구현 큐에 다시 넣었습니다' })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '구현 재시도 실패' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-dialog
    :model-value="modelValue"
    :maximized="$q.screen.lt.md"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <q-card style="width: min(480px, 100vw)" data-test="impl-retry-dialog">
      <q-card-section class="text-h6">구현 재시도</q-card-section>
      <q-card-section class="q-pt-none text-caption text-grey-8">
        베이스에서 처음부터 새 브랜치(시도 번호 접미사)로 다시 구현합니다. 이전 실패 사유와 브랜치는 진행 이력에 남아 있습니다.
      </q-card-section>
      <q-card-section>
        <ImplementationModePicker v-model="mode" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn flat label="취소" @click="emit('update:modelValue', false)" />
        <q-btn
          unelevated
          color="primary"
          label="구현 재시도"
          :loading="submitting"
          data-test="impl-retry-submit"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>
