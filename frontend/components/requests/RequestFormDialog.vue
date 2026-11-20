<script setup lang="ts">
import { failMsg } from '~/composables/failMessage'
// 요청 작성/수정 다이얼로그. initial이 없으면 새 요청(POST), 있으면 수정(PUT, 종류 잠금).
// 개발 요청(DEV)은 유형·대상 레포·우선순위·마감일·첨부를 받고 항상 multipart로 보낸다.
// 사이트 추가(REPO)는 레포 힌트만 받고 JSON으로 보낸다. 예전 종류(FEATURE·MCP) 글의 수정은 제목·내용만.
import { useQuasar } from 'quasar'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import { validateFiles } from '~/composables/attachmentLimits'
import {
  ISSUE_TYPE_OPTIONS,
  KIND_OPTIONS,
  MAX_REQUEST_FILES,
  PRIORITY_OPTIONS,
  requestFormData,
  type RequestAttachmentView,
  type RequestDetail,
  type RequestIssueType,
  type RequestKind,
  type RequestListItem,
  type RequestPriority,
} from '~/composables/requestBoard'

const props = defineProps<{
  modelValue: boolean
  initial?: RequestDetail
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  // 새 요청(POST)은 목록 항목, 수정(PUT)은 상세를 돌려준다 — 응답 객체를 그대로 내보낸다
  saved: [item: RequestListItem | RequestDetail]
}>()

const $q = useQuasar()
const show = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const form = reactive<{
  kind: RequestKind
  title: string
  content: string
  repoHint: string
  issueType: RequestIssueType | null
  priority: RequestPriority
  dueDate: string
  targetRepoCatalogId: number | null
}>({
  kind: 'DEV',
  title: '',
  content: '',
  repoHint: '',
  issueType: null,
  priority: 'NORMAL',
  dueDate: '',
  targetRepoCatalogId: null,
})
const submitting = ref(false)
// 첨부: 수정 때 이미 올라간 것(지울 수 있음)과 이번에 새로 고른 파일
const existingAttachments = ref<RequestAttachmentView[]>([])
const removedIds = ref<number[]>([])
const newFiles = ref<File[]>([])

const isDev = computed(() => form.kind === 'DEV')
// 예전 종류 글의 수정은 종류 토글 없이 제목·내용만
const kindToggleVisible = computed(() => KIND_OPTIONS.some((o) => o.value === form.kind))

// 대상 레포 후보 — 작업 등록이 받아 주는 활성 항목만(host가 other인 레포는 등록할 수 없다)
interface RepoCatalogEntry {
  id: number
  alias: string
  host: string
}
const repoCatalog = ref<RepoCatalogEntry[]>([])
const repoOptions = computed(() => [
  { label: '선택 안 함', value: null as number | null },
  ...repoCatalog.value.filter((r) => r.host !== 'other').map((r) => ({ label: r.alias, value: r.id as number | null })),
])
let catalogLoaded = false
async function loadRepoCatalog() {
  if (catalogLoaded) return
  catalogLoaded = true
  try {
    const list = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
    repoCatalog.value = Array.isArray(list) ? list : []
  } catch {
    repoCatalog.value = []
  }
}

// 열릴 때마다 처음부터(새 요청) 또는 수정 대상 값으로 채운다
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.kind = props.initial?.kind ?? 'DEV'
    form.title = props.initial?.title ?? ''
    form.content = props.initial?.content ?? ''
    form.repoHint = props.initial?.repoHint ?? ''
    form.issueType = props.initial?.issueType ?? null
    form.priority = props.initial?.priority ?? 'NORMAL'
    form.dueDate = props.initial?.dueDate ?? ''
    form.targetRepoCatalogId = props.initial?.targetRepoCatalogId ?? null
    existingAttachments.value = props.initial?.attachments ?? []
    removedIds.value = []
    newFiles.value = []
    if (isDev.value) loadRepoCatalog()
  },
  { immediate: true },
)
// 새 요청에서 종류를 개발 요청으로 바꿨을 때 후보를 처음 한 번 읽는다
watch(isDev, (dev) => {
  if (dev && props.modelValue) loadRepoCatalog()
})

const keptAttachments = computed(() => existingAttachments.value.filter((a) => !removedIds.value.includes(a.id)))
const fileCount = computed(() => keptAttachments.value.length + newFiles.value.length)
// 개수 위반은 남은 첨부까지 합쳐 세고, 크기·빈 파일 같은 위반은 새 파일만 본다(한도 값은 composables/attachmentLimits.ts)
const fileProblem = computed(() => {
  if (fileCount.value > MAX_REQUEST_FILES) return `첨부는 최대 ${MAX_REQUEST_FILES}개입니다`
  return validateFiles(newFiles.value)
})

const canSubmit = computed(
  () =>
    !submitting.value &&
    !!form.title.trim() &&
    !!form.content.trim() &&
    (!isDev.value || (!!form.issueType && !fileProblem.value)),
)

function removeExisting(id: number) {
  if (!removedIds.value.includes(id)) removedIds.value = [...removedIds.value, id]
}

function devMeta() {
  return {
    title: form.title.trim(),
    content: form.content.trim(),
    issueType: form.issueType,
    priority: form.priority,
    dueDate: form.dueDate || null,
    targetRepoCatalogId: form.targetRepoCatalogId,
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const hint = form.kind === 'REPO' ? form.repoHint.trim() || null : null
    let saved: RequestListItem | RequestDetail
    if (props.initial) {
      // 개발 요청은 파일이 없어도 multipart, 그 밖의 종류는 JSON
      const body = isDev.value
        ? requestFormData({ ...devMeta(), removeAttachmentIds: removedIds.value }, newFiles.value)
        : { title: form.title.trim(), content: form.content.trim(), repoHint: hint }
      saved = await useApi<RequestDetail>(`/api/requests/${props.initial.id}`, { method: 'PUT', body })
      $q.notify({ type: 'positive', message: '수정 완료' })
    } else {
      const body = isDev.value
        ? requestFormData({ kind: form.kind, ...devMeta() }, newFiles.value)
        : { kind: form.kind, title: form.title.trim(), content: form.content.trim(), repoHint: hint }
      saved = await useApi<RequestListItem>('/api/requests', { method: 'POST', body })
      $q.notify({ type: 'positive', message: '요청을 등록했습니다' })
    }
    show.value = false
    emit('saved', saved)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg('저장') })
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
        v-if="kindToggleVisible"
        v-model="form.kind"
        :options="KIND_OPTIONS"
        :disable="!!initial"
        unelevated
        no-caps
        spread
        toggle-color="primary"
        color="white"
        text-color="primary"
        data-test="request-kind"
      />
      <q-select
        v-if="isDev"
        v-model="form.issueType"
        :options="ISSUE_TYPE_OPTIONS"
        label="유형"
        emit-value
        map-options
        outlined
        :dense="!mobile"
        data-test="request-issue-type"
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
      <template v-if="isDev">
        <q-select
          v-model="form.targetRepoCatalogId"
          :options="repoOptions"
          label="대상 레포 (선택)"
          hint="모르면 비워 두세요"
          emit-value
          map-options
          outlined
          :dense="!mobile"
          data-test="request-target-repo"
        />
        <q-btn-toggle
          v-model="form.priority"
          :options="PRIORITY_OPTIONS"
          unelevated
          no-caps
          spread
          toggle-color="primary"
          color="white"
          text-color="primary"
          data-test="request-priority"
        />
        <q-input
          v-model="form.dueDate"
          type="date"
          label="마감일 (선택)"
          stack-label
          outlined
          :dense="!mobile"
          data-test="request-due-date"
        />
        <div v-if="keptAttachments.length > 0" class="column q-gutter-xs" data-test="request-existing-attachments">
          <div v-for="a in keptAttachments" :key="a.id" class="row items-center no-wrap">
            <q-icon name="attach_file" class="q-mr-xs" />
            <span class="ellipsis">{{ a.name }}</span>
            <span v-if="a.expired" class="text-grey-7 q-ml-sm">(보관 기간이 지남)</span>
            <q-space />
            <q-btn
              flat
              dense
              round
              icon="close"
              :aria-label="`첨부 삭제: ${a.name}`"
              :data-test="`request-attachment-remove-${a.id}`"
              @click="removeExisting(a.id)"
            />
          </div>
        </div>
        <q-file
          v-model="newFiles"
          label="첨부파일 (선택)"
          outlined
          :dense="!mobile"
          multiple
          use-chips
          counter
          append
          :error="!!fileProblem"
          :error-message="fileProblem ?? ''"
          data-test="request-files"
        >
          <template #prepend>
            <q-icon name="attach_file" />
          </template>
        </q-file>
      </template>
      <q-input
        v-else-if="form.kind === 'REPO'"
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
