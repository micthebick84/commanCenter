<script setup lang="ts">
import { useQuasar } from 'quasar'
import {
  MODEL_OPTIONS,
  DEFAULT_MODEL,
  DEFAULT_EFFORT,
  effortsForModel,
  coerceEffort,
} from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'

const props = defineProps<{ taskId: number }>()
const show = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'approved'): void }>()

const $q = useQuasar()
const model = ref(DEFAULT_MODEL)
const effort = ref(DEFAULT_EFFORT)
const mcpCatalogIds = ref<number[]>([])
const approving = ref(false)

const effortOptions = computed(() => effortsForModel(model.value))
watch(model, (m) => {
  effort.value = coerceEffort(m, effort.value)
})

async function approve() {
  approving.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/approve`, {
      method: 'POST',
      body: {
        model: model.value,
        effort: effort.value,
        mcpCatalogIds: mcpCatalogIds.value,
      },
    })
    $q.notify({ type: 'positive', message: '승인 완료 — 인터뷰가 시작됩니다' })
    show.value = false
    emit('approved')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  } finally {
    approving.value = false
  }
}

defineExpose({ approve })
</script>

<template>
  <q-dialog v-model="show" :maximized="$q.screen.lt.md">
    <q-card style="width: min(480px, 100vw)">
      <q-card-section class="text-h6">승인 — 인터뷰 시작</q-card-section>
      <q-card-section class="q-pt-none text-grey-8">
        승인하면 관리자와의 대화형 분석이 시작됩니다. 여기서 고른 모델·도구로 인터뷰가 진행됩니다.
      </q-card-section>
      <q-card-section class="q-gutter-md">
        <div class="row q-col-gutter-md">
          <q-select
            v-model="model"
            :options="MODEL_OPTIONS"
            emit-value
            map-options
            outlined
            dense
            label="Claude 모델"
            class="col"
          />
          <q-select
            v-model="effort"
            :options="effortOptions"
            emit-value
            map-options
            outlined
            dense
            label="Effort"
            class="col"
            :hint="model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
          />
        </div>
        <McpPicker v-model="mcpCatalogIds" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn v-close-popup flat label="취소" />
        <q-btn
          data-test="approve-submit"
          unelevated
          color="primary"
          icon="forum"
          label="승인하고 인터뷰 시작"
          :loading="approving"
          @click="approve"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>
