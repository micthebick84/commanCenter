<script setup lang="ts">
import { failMsg } from '~/composables/failMessage'
// 구현 재시도 (스펙 2026-10-15 §5.5·§7) — 방식을 다시 골라 구현실패 작업을 구현대기로 돌린다(관리자).
// 작업에 참조 repo가 있으면 참조마다 "이 참조 빼고 재시도"를 고를 수 있다(작업 스펙 2026-10-24 §7.5b) — 기본은 해제,
// 실패 사유가 그 참조의 준비 실패(`참조 repo 준비 실패: {별칭}({브랜치}) — …`)면 미리 고른다. 고른 것이 있을 때만 바디에 싣는다.
// 열린 수정 회차(fixRound, 스펙 2026-11-03 §7)의 구현실패면 처음부터가 아니라 같은 회차를 같은 브랜치에서 다시 돈다 — 제목·안내를 바꾸고
// 방식은 리뷰·직접만(서버가 SDD를 400으로 막는다), 시작 값은 작업이 아니라 그 회차의 방식(currentMode)이다.
import { useQuasar } from 'quasar'
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import type { ImplementationMode } from '~/composables/implementationMode'
import { useFullscreenDialog } from '~/composables/dialogLayout'
import { prepFailedRefIds, refChipLabel } from '~/composables/refRepos'
import { FIX_MODES } from '~/composables/taskFix'
// defineProps 타입이므로 상대 경로 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import type { RefRepoView } from '../../composables/refRepos'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  currentMode: string
  refRepos?: RefRepoView[]
  failureReason?: string | null
  /** 열린 수정 회차 번호 — 없거나 null이면 첫 구현의 재시도 */
  fixRound?: number | null
  /** 열린 회차의 종류 — BASE_SYNC면 방식 선택 없이 같은 브랜치에서 반영을 다시 시도(서버가 DIRECT로 고정) */
  fixKind?: 'FIX' | 'BASE_SYNC' | null
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
const inFixRound = computed(() => props.fixRound != null)
const isBaseSync = computed(() => inFixRound.value && props.fixKind === 'BASE_SYNC')

// 열 때마다 지금 방식으로 시작하고, 뺄 참조는 실패 사유가 가리키는 것만 미리 고른다
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      const allowed: string[] = inFixRound.value ? FIX_MODES : ['DIRECT', 'REVIEW', 'SDD']
      mode.value = (allowed.includes(props.currentMode) ? props.currentMode : inFixRound.value ? 'REVIEW' : 'DIRECT') as ImplementationMode
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
        ...(isBaseSync.value ? {} : { implementationMode: mode.value }),
        ...(removeRefCatalogIds.length > 0 ? { removeRefCatalogIds } : {}),
      },
    })
    $q.notify({
      type: 'positive',
      message: isBaseSync.value
        ? `기준 브랜치 반영 ${props.fixRound}회차 재시도 — 구현 큐에 다시 넣었습니다`
        : inFixRound.value
          ? `수정 ${props.fixRound}회차 구현 재시도 — 구현 큐에 다시 넣었습니다`
          : '구현 재시도 — 구현 큐에 다시 넣었습니다',
    })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg('구현 재시도') })
  } finally {
    submitting.value = false
  }
}
// 폰에서만 전체화면 — 태블릿은 일반 너비 (composables/dialogLayout.ts)
const fullscreen = useFullscreenDialog()
</script>

<template>
  <q-dialog
    :model-value="modelValue"
    :maximized="fullscreen"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <q-card style="width: min(480px, 100vw)" data-test="impl-retry-dialog">
      <q-card-section class="text-h6">{{ isBaseSync ? `기준 브랜치 반영 ${fixRound}회차 재시도` : inFixRound ? `수정 ${fixRound}회차 구현 재시도 (같은 브랜치)` : '구현 재시도' }}</q-card-section>
      <q-card-section v-if="isBaseSync" class="q-pt-none text-caption text-grey-8" data-test="impl-retry-base-sync-note">
        기준 브랜치 반영을 다시 시도합니다 (같은 브랜치)
      </q-card-section>
      <q-card-section v-else-if="inFixRound" class="q-pt-none text-caption text-grey-8" data-test="impl-retry-fix-note">
        새 브랜치를 따지 않고, 이 회차를 같은 브랜치에서 이어서 다시 구현합니다. 브랜치에 이미 있는 커밋(이전 배포분과 이전 시도의 push분)은 그대로 두고 그 위에 고칩니다.
        이전 실패 사유는 진행 이력에 남아 있습니다.
      </q-card-section>
      <q-card-section v-else class="q-pt-none text-caption text-grey-8">
        베이스에서 처음부터 새 브랜치(시도 번호 접미사)로 다시 구현합니다. 이전 실패 사유와 브랜치는 진행 이력에 남아 있습니다.
        push되지 않은 이전 부분 작업은 재시도하면 이 PC에서 지워질 수 있습니다.
      </q-card-section>
      <q-card-section v-if="!isBaseSync">
        <ImplementationModePicker v-model="mode" :modes="inFixRound ? FIX_MODES : undefined" />
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
