<script setup lang="ts">
// 구현 재시도 (스펙 2026-10-15 §5.5·§7) — 방식을 다시 골라 구현실패 작업을 구현대기로 돌린다(관리자).
// 작업에 참조 repo가 있으면 참조마다 "이 참조 빼고 재시도"를 고를 수 있다(작업 스펙 2026-10-24 §7.5b) — 기본은 해제,
// 실패 사유가 그 참조의 준비 실패(`참조 repo 준비 실패: {별칭}({브랜치}) — …`)면 미리 고른다. 고른 것이 있을 때만 바디에 싣는다.
import { useQuasar } from 'quasar'
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import type { ImplementationMode } from '~/composables/implementationMode'
import { prepFailedRefIds, refChipLabel } from '~/composables/refRepos'
// defineProps 타입이므로 상대 경로 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import type { RefRepoView } from '../../composables/refRepos'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  currentMode: string
  refRepos?: RefRepoView[]
  failureReason?: string | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

const $q = useQuasar()
const mode = ref<ImplementationMode>('DIRECT')
/** 이번 재시도에서 뺄 참조의 catalogId */
const removeIds = ref<number[]>([])
const submitting = ref(false)

// 열 때마다 지금 방식으로 시작하고, 뺄 참조는 실패 사유가 가리키는 것만 미리 고른다
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      mode.value = (['DIRECT', 'REVIEW', 'SDD'].includes(props.currentMode) ? props.currentMode : 'DIRECT') as ImplementationMode
      removeIds.value = prepFailedRefIds(props.failureReason, props.refRepos ?? [])
    }
  },
  { immediate: true },
)

async function submit() {
  if (submitting.value) return
  submitting.value = true
  // 작업 참조 순서로 — 창을 연 뒤 작업에서 사라진 참조는 싣지 않는다(서버가 작업에 없는 참조를 400으로 막는다)
  const removeRefCatalogIds = (props.refRepos ?? [])
    .filter((r) => removeIds.value.includes(r.catalogId))
    .map((r) => r.catalogId)
  try {
    await useApi(`/api/tasks/${props.taskId}/implementation/retry`, {
      method: 'POST',
      body: {
        implementationMode: mode.value,
        ...(removeRefCatalogIds.length > 0 ? { removeRefCatalogIds } : {}),
      },
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
        push되지 않은 이전 부분 작업은 재시도하면 이 PC에서 지워질 수 있습니다.
      </q-card-section>
      <q-card-section>
        <ImplementationModePicker v-model="mode" />
      </q-card-section>
      <q-card-section v-if="refRepos?.length" class="q-pt-none" data-test="impl-retry-refs">
        <div class="ref-title">참조 repo</div>
        <div v-for="r in refRepos" :key="r.catalogId" class="row items-center ref-row">
          <q-chip dense size="sm" color="deep-purple-1" text-color="deep-purple-10" class="ref-chip">
            <span class="ref-chip-label">{{ refChipLabel(r) }}</span>
          </q-chip>
          <q-checkbox
            v-model="removeIds"
            :val="r.catalogId"
            label="이 참조 빼고 재시도"
            dense
            :data-test="`impl-retry-ref-${r.catalogId}`"
          />
        </div>
        <div class="text-caption text-grey-7 q-mt-xs">뺀 참조는 이번 구현과 이후 재시도에서 쓰지 않습니다.</div>
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

<style scoped>
.ref-title {
  font-size: 12px;
  font-weight: 600;
  color: #616161;
  padding-bottom: 2px;
}
.ref-row {
  gap: 4px 8px;
}
.ref-chip {
  max-width: 100%;
}
/* 좁은 화면에서 긴 별칭·브랜치가 칩을 줄 밖으로 밀어내지 않게 — 라벨만 말줄임 (RefRepoChips와 같은 처리) */
.ref-chip-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
