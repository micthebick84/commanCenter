<script setup lang="ts">
// 배포/재배포 환경변수 다이얼로그 (스펙 2026-10-07 §8). 열 때마다 서버 추천(구현 시 추출한 이름 + 같은 레포
// 직전 배포 값)을 새로 불러오고, 실패하면 저장된 env로 채운다. 판정은 composables/deployEnvForm.ts 순수 함수.
import { useQuasar } from 'quasar'
import {
  blankRow,
  isSuggestion,
  missingRequired,
  rowsFromEnvVars,
  rowsFromSuggestion,
  suggestionCaption,
  toWireEnvVars,
  type EnvRow,
  type EnvVar,
} from '~/composables/deployEnvForm'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  mode: 'deploy' | 'redeploy'
  savedEnvVars: EnvVar[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

const $q = useQuasar()
const rows = ref<EnvRow[]>([])
const caption = ref<string | null>(null)
const loading = ref(false)
const submitting = ref(false)
let rowSeq = 0
const nextId = () => rowSeq++
let loadSeq = 0

const actionLabel = computed(() => (props.mode === 'deploy' ? '배포' : '재배포'))
const missing = computed(() => missingRequired(rows.value))
const submitLabel = computed(() =>
  missing.value.length ? `그래도 ${actionLabel.value}` : actionLabel.value,
)

async function load() {
  const token = ++loadSeq
  loading.value = true
  rows.value = []
  caption.value = null
  let next: EnvRow[] = []
  let nextCaption: string | null = null
  let failed = false
  try {
    const res = await useApi(`/api/tasks/${props.taskId}/deploy-env`)
    if (!isSuggestion(res)) throw new Error('unexpected deploy-env response')
    next = rowsFromSuggestion(res, nextId)
    nextCaption = suggestionCaption(res)
  } catch {
    failed = true
  }
  if (token !== loadSeq) return // 닫았다 다시 연 뒤 늦게 도착한 이전 응답 — 새 결과를 덮지 않는다
  if (failed) {
    next = rowsFromEnvVars(props.savedEnvVars, nextId)
    $q.notify({ type: 'warning', message: '추천 값을 불러오지 못해 저장된 값으로 채웠습니다' })
  }
  rows.value = next
  caption.value = nextCaption
  loading.value = false
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void load()
  },
  { immediate: true },
)

function addRow() {
  rows.value.push(blankRow(nextId))
}

function removeRow(id: number) {
  rows.value = rows.value.filter((r) => r.id !== id)
}

async function submit() {
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/${props.mode}`, {
      method: 'POST',
      body: { envVars: toWireEnvVars(rows.value) },
    })
    $q.notify({ type: 'positive', message: `${actionLabel.value} 큐 등록` })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? `${actionLabel.value} 실패` })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-dialog
    :model-value="modelValue"
    :maximized="$q.screen.lt.md"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <q-card style="width: min(560px, 100vw)" data-test="deploy-env-dialog">
      <div class="dialog-body">
        <q-card-section class="row items-center no-wrap">
          <div class="text-h6">{{ actionLabel }} — 환경변수</div>
          <q-space />
          <q-btn v-close-popup flat round dense icon="close" aria-label="닫기" />
        </q-card-section>
        <q-card-section class="q-pt-none text-caption text-grey-7">
          <div v-if="caption" class="text-primary q-mb-xs" data-test="deploy-env-caption">{{ caption }}</div>
          <div>
            컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. 값이 빈 변수는 보내지 않습니다.
            비밀 값은 마스킹 표시되지만 평문 저장됩니다.
          </div>
        </q-card-section>
        <q-card-section v-if="loading" class="row justify-center">
          <q-spinner size="28px" color="primary" />
        </q-card-section>
        <q-card-section v-else class="q-gutter-y-md">
          <div v-for="row in rows" :key="row.id" data-test="env-row">
            <div v-if="row.fixedKey" class="row items-center q-gutter-x-sm">
              <code class="env-key" data-test="env-key-label">{{ row.key }}</code>
              <q-badge v-if="row.required" color="orange-8" label="필수" />
              <q-badge v-if="row.valueFromPrevious" outline color="grey-7" label="직전 배포 값" />
            </div>
            <div
              v-if="row.fixedKey && row.description"
              class="text-caption text-grey-7"
              data-test="env-description"
            >
              {{ row.description }}
            </div>
            <div :class="$q.screen.lt.md ? 'column q-gutter-y-xs' : 'row items-center q-gutter-xs no-wrap'">
              <q-input
                v-if="!row.fixedKey"
                v-model="row.key"
                :dense="!$q.screen.lt.md"
                outlined
                placeholder="KEY"
                style="flex: 1"
                data-test="env-key-input"
              />
              <q-input
                v-model="row.value"
                :dense="!$q.screen.lt.md"
                outlined
                :placeholder="row.fixedKey ? '값' : 'value'"
                style="flex: 2"
                :type="row.secret && !row.reveal ? 'password' : 'text'"
                data-test="env-value-input"
              >
                <template v-if="row.secret" #append>
                  <q-icon
                    :name="row.reveal ? 'visibility_off' : 'visibility'"
                    class="cursor-pointer"
                    @click="row.reveal = !row.reveal"
                  />
                </template>
              </q-input>
              <div class="row items-center no-wrap">
                <q-toggle v-model="row.secret" label="비밀" dense />
                <q-btn flat round dense icon="delete" color="grey" aria-label="변수 삭제" @click="removeRow(row.id)" />
              </div>
            </div>
          </div>
          <q-btn flat dense icon="add" label="변수 추가" data-test="env-add" @click="addRow" />
        </q-card-section>
      </div>
      <q-card-section
        v-if="missing.length"
        class="q-py-xs text-caption text-warning"
        data-test="env-missing-required"
      >
        필수 변수 {{ missing.length }}개가 비어 있습니다: {{ missing.join(', ') }}
      </q-card-section>
      <q-card-actions align="right">
        <q-btn v-close-popup flat label="취소" />
        <q-btn
          unelevated
          :color="missing.length ? 'warning' : 'primary'"
          :label="submitLabel"
          :loading="submitting"
          :disable="loading"
          data-test="env-submit"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.env-key {
  font-weight: 600;
  word-break: break-all;
}
</style>
