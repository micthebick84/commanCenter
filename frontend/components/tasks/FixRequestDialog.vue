<script setup lang="ts">
import { failMsg } from '~/composables/failMessage'
// 수정 요청 내기 (스펙 2026-11-03 §6.1·§6.2) — 배포된 작업의 문제를 적는다. 등록자의 요청은 관리자가 승인해야 시작하고,
// 관리자가 내면 승인 없이 바로 구현 큐에 들어가므로 그때만 구현 방식(리뷰·직접)을 고른다. SDD는 수정 회차에서 쓰지 않는다.
import { useQuasar } from 'quasar'
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import type { ImplementationMode } from '~/composables/implementationMode'
import { FIX_MODES } from '~/composables/taskFix'
import { useFullscreenDialog } from '~/composables/dialogLayout'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  isAdmin: boolean
  /** 이번 요청이 승인되면 맡을 회차 번호 — 승인된 요청 수 + 1 */
  nextRound: number
  maxRounds: number
  /** 열 때 본문에 미리 채울 글(재검증 실패 항목 등) */
  initialBody?: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

/** 서버 `FixRequestCreateRequest.body` 상한 */
const BODY_MAX = 20000

const $q = useQuasar()
const text = ref('')
const mode = ref<ImplementationMode>('REVIEW')
const submitting = ref(false)

// 열 때마다 새로 시작한다 — 미리 채울 글이 있으면 그것으로, 방식은 기본 리뷰
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      text.value = props.initialBody ?? ''
      mode.value = 'REVIEW'
    }
  },
  { immediate: true },
)

const canSubmit = computed(() => !submitting.value && text.value.trim().length > 0 && text.value.length <= BODY_MAX)

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/fix-requests`, {
      method: 'POST',
      // 방식은 관리자가 낸 요청에서만 서버가 쓴다(등록자는 승인 때 관리자가 고른다)
      body: { body: text.value.trim(), implementationMode: props.isAdmin ? mode.value : undefined },
    })
    $q.notify({
      type: 'positive',
      message: props.isAdmin ? '수정 요청 — 구현 큐에 넣었습니다' : '수정 요청을 냈습니다 — 관리자 승인을 기다립니다',
    })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e) {
    $q.notify({ type: 'negative', message: (e as { data?: { message?: string } })?.data?.message ?? failMsg('수정 요청') })
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
    <q-card style="width: min(560px, 100vw)" data-test="fix-request-dialog">
      <q-card-section class="text-h6">수정 요청 ({{ nextRound }}/{{ maxRounds }})</q-card-section>
      <q-card-section class="q-pt-none text-caption text-grey-8">같은 브랜치에서 이어 고친 뒤 자동으로 다시 배포해요. 등록자 요청은 관리자 승인 뒤 시작돼요.</q-card-section>
      <q-card-section class="q-pt-none">
        <q-input
          v-model="text"
          type="textarea"
          outlined
          autogrow
          label="무엇이 문제인가요?"
          hint="어디서 어떻게 잘못됐는지, 기대한 동작을 적어 주세요 (Markdown 가능)"
          :maxlength="BODY_MAX"
          counter
          input-style="min-height: 140px; max-height: 40vh"
          data-test="fix-request-body"
        />
      </q-card-section>
      <q-card-section v-if="isAdmin" class="q-pt-none" data-test="fix-request-mode">
        <ImplementationModePicker v-model="mode" :modes="FIX_MODES" />
        <div class="text-caption text-grey-7 q-mt-xs">관리자가 내면 승인 없이 바로 구현 큐에 들어갑니다.</div>
      </q-card-section>
      <q-card-actions align="right">
        <q-btn flat label="취소" @click="emit('update:modelValue', false)" />
        <q-btn
          unelevated
          color="primary"
          label="수정 요청"
          :loading="submitting"
          :disable="!canSubmit"
          data-test="fix-request-submit"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>
