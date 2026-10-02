<script setup lang="ts">
// DB 접속정보 추가/수정 (스펙 2026-10-02 §7). AdminFormDialog 셸 재사용(모바일 전체화면).
// 수정 모드에서 비밀번호는 다시 채우지 않는다 — 비워 두고 저장하면 서버가 기존 값을 유지한다.
import { useQuasar } from 'quasar'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import {
  DB_TYPES, saveDbConnection, testDbConnection, type DbConnectionView, type DbScope, type DbType,
} from '~/composables/dbConnections'

const props = defineProps<{
  modelValue: boolean
  scope: DbScope
  repoCatalogId: number
  editing: DbConnectionView | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [view: DbConnectionView]
}>()

const $q = useQuasar()
const form = reactive({
  name: '',
  dbType: 'POSTGRESQL' as DbType,
  host: '',
  port: 5432,
  databaseName: '',
  username: '',
  password: '',
})
const portTouched = ref(false)
const submitting = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

function reset() {
  const e = props.editing
  Object.assign(form, e
    ? { name: e.name, dbType: e.dbType, host: e.host, port: e.port, databaseName: e.databaseName, username: e.username, password: '' }
    : { name: '', dbType: 'POSTGRESQL', host: '', port: 5432, databaseName: '', username: '', password: '' })
  portTouched.value = !!e
  testResult.value = null
}
watch(() => props.modelValue, (open) => { if (open) reset() }, { immediate: true })
watch(() => form.dbType, (t) => {
  if (!portTouched.value) form.port = DB_TYPES.find((d) => d.value === t)?.defaultPort ?? form.port
})
function onPortInput(v: string | number | null) {
  form.port = Number(v)
  portTouched.value = true
}

const dbNameLabel = computed(() => (form.dbType === 'ORACLE' ? '서비스명' : 'DB 이름'))
const canSubmit = computed(() =>
  !!form.name.trim() && !!form.host.trim() && form.port > 0 && !!form.databaseName.trim() && !!form.username.trim()
  && (!!props.editing || !!form.password),
)

function fields() {
  return {
    dbType: form.dbType, host: form.host.trim(), port: form.port,
    databaseName: form.databaseName.trim(), username: form.username.trim(), password: form.password,
  }
}

async function runTest() {
  testing.value = true
  try {
    testResult.value = await testDbConnection(props.editing ? { id: props.editing.id, ...fields() } : fields())
  } catch (e: any) {
    testResult.value = { ok: false, message: e?.data?.message ?? '접속 테스트 요청 실패' }
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const saved = await saveDbConnection(
      { scope: props.scope, repoCatalogId: props.repoCatalogId, name: form.name.trim(), ...fields() },
      props.editing?.id,
    )
    emit('saved', saved)
    emit('update:modelValue', false)
    $q.notify({ type: 'positive', message: 'DB 접속정보를 저장했습니다' })
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? 'DB 접속정보 저장 실패' })
  } finally {
    submitting.value = false
  }
}

defineExpose({ form, save, runTest, testResult, dbNameLabel, canSubmit, onPortInput })
</script>

<template>
  <AdminFormDialog
    :model-value="modelValue"
    :title="editing ? 'DB 접속정보 수정' : 'DB 접속정보 추가'"
    submit-label="저장"
    :submitting="submitting"
    :can-submit="canSubmit"
    @update:model-value="emit('update:modelValue', $event)"
    @submit="save"
  >
    <template #default="{ mobile }">
      <div class="column q-gutter-sm">
        <q-banner dense class="bg-amber-1 text-grey-9 db-warn">
          <template #avatar><q-icon name="shield" color="amber-9" /></template>
          읽기 전용 계정 사용을 권장합니다. 쓰기 SQL은 질문 세션에서 차단되지만 완전하지 않습니다.
        </q-banner>
        <q-input v-model="form.name" label="이름" :dense="mobile" outlined data-test="db-name" />
        <q-select
          v-model="form.dbType"
          :options="DB_TYPES"
          option-value="value"
          option-label="label"
          emit-value
          map-options
          label="종류"
          :dense="mobile"
          outlined
          data-test="db-type"
        />
        <div class="row q-col-gutter-sm">
          <q-input v-model="form.host" class="col-8" label="호스트" :dense="mobile" outlined data-test="db-host" />
          <q-input
            :model-value="form.port"
            class="col-4"
            type="number"
            label="포트"
            :dense="mobile"
            outlined
            data-test="db-port"
            @update:model-value="onPortInput"
          />
        </div>
        <q-input v-model="form.databaseName" :label="dbNameLabel" :dense="mobile" outlined data-test="db-database" />
        <q-input v-model="form.username" label="사용자" :dense="mobile" outlined autocomplete="off" data-test="db-username" />
        <q-input
          v-model="form.password"
          type="password"
          label="비밀번호"
          :placeholder="editing ? '비워 두면 기존 비밀번호 유지' : ''"
          :dense="mobile"
          outlined
          autocomplete="new-password"
          data-test="db-password"
        />
        <div class="row items-center q-gutter-sm">
          <q-btn flat no-caps icon="cable" label="접속 테스트" color="primary" :loading="testing" data-test="db-test" @click="runTest" />
          <span
            v-if="testResult"
            :class="testResult.ok ? 'text-positive' : 'text-negative'"
            class="text-caption"
            data-test="db-test-result"
          >{{ testResult.message }}</span>
        </div>
      </div>
    </template>
  </AdminFormDialog>
</template>

<style scoped>
.db-warn {
  border-radius: 6px;
}
</style>
