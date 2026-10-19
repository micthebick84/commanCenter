<script setup lang="ts">
// 요청 작성/수정 다이얼로그. initial이 없으면 새 요청(POST), 있으면 수정(PUT, 종류 잠금).
// 레포 힌트는 종류가 REPO일 때만 받고 보낸다.
import { useQuasar } from 'quasar'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import { KIND_OPTIONS, type RequestDetail, type RequestKind } from '~/composables/requestBoard'

const props = defineProps<{
  modelValue: boolean
  initial?: RequestDetail
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [item: RequestDetail]
}>()

const $q = useQuasar()
const show = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const form = reactive<{ kind: RequestKind; title: string; content: string; repoHint: string }>({
  kind: 'FEATURE',
  title: '',
  content: '',
  repoHint: '',
})
const submitting = ref(false)

// 열릴 때마다 처음부터(새 요청) 또는 수정 대상 값으로 채운다
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.kind = props.initial?.kind ?? 'FEATURE'
    form.title = props.initial?.title ?? ''
    form.content = props.initial?.content ?? ''
    form.repoHint = props.initial?.repoHint ?? ''
  },
  { immediate: true },
)

const canSubmit = computed(() => !submitting.value && !!form.title.trim() && !!form.content.trim())

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const hint = form.kind === 'REPO' ? form.repoHint.trim() || null : null
    let saved: RequestDetail
    if (props.initial) {
      saved = await useApi<RequestDetail>(`/api/requests/${props.initial.id}`, {
        method: 'PUT',
        body: { title: form.title.trim(), content: form.content.trim(), repoHint: hint },
      })
      $q.notify({ type: 'positive', message: '수정 완료' })
    } else {
      saved = await useApi<RequestDetail>('/api/requests', {
        method: 'POST',
        body: { kind: form.kind, title: form.title.trim(), content: form.content.trim(), repoHint: hint },
      })
      $q.notify({ type: 'positive', message: '요청을 등록했습니다' })
    }
    show.value = false
    emit('saved', saved)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '저장 실패' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AdminFormDialog
    v-model="show"
    :title="initial ? '요청 수정' : '새 요청'"
    :submit-label="initial ? '수정' : '등록'"
    submit-test="req-submit"
    :submitting="submitting"
    :can-submit="canSubmit"
    @submit="submit"
  >
    <template #default="{ mobile }">
      <q-btn-toggle
        v-model="form.kind"
        :options="KIND_OPTIONS"
        :disable="!!initial"
        unelevated
        no-caps
        spread
        toggle-color="primary"
        color="white"
        text-color="primary"
        data-test="req-kind"
      />
      <q-input
        v-model="form.title"
        label="제목"
        outlined
        :dense="!mobile"
        maxlength="200"
        data-test="req-title"
      />
      <q-input
        v-model="form.content"
        label="내용"
        type="textarea"
        outlined
        autogrow
        maxlength="10000"
        input-style="min-height: 140px"
        data-test="req-content"
      />
      <q-input
        v-if="form.kind === 'REPO'"
        v-model="form.repoHint"
        label="레포 주소 또는 이름 (선택)"
        hint="알면 적어 주세요. 몰라도 괜찮아요."
        outlined
        :dense="!mobile"
        :hide-bottom-space="mobile"
        maxlength="500"
        autocapitalize="off"
        autocorrect="off"
        spellcheck="false"
        data-test="req-hint"
      />
    </template>
  </AdminFormDialog>
</template>
