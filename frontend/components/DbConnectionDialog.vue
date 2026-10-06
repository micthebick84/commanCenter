<script setup lang="ts">
// DB 접속정보 추가/수정 (스펙 2026-10-02 §7). AdminFormDialog 셸 재사용(모바일 전체화면).
// 수정 모드에서 비밀번호는 다시 채우지 않는다 — 비워 두고 저장하면 서버가 기존 값을 유지한다.
// 화면(2026-10-06): 데스크톱·모바일이 같은 DOM 한 벌 — 종류 카드는 grid auto-fit으로 4열↔2열만 바뀐다.
// 접속 테스트는 저장 조건이 아니라 유도 — 실패 상태면 저장 버튼이 "그래도 저장"으로 바뀐다.
import { useQuasar } from 'quasar'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import {
  DB_TYPES, saveDbConnection, testDbConnection, type DbConnectionView, type DbScope, type DbType,
} from '~/composables/dbConnections'
import {
  connectionKey, hostError, portError, requiredError, splitHostPort, testState,
} from '~/composables/dbConnectionForm'

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
const portCustomized = ref(false)
const submitting = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)
const testedKey = ref<string | null>(null)
const testedAt = ref<Date | null>(null)
const showPassword = ref(false)
type FieldKey = 'name' | 'host' | 'port' | 'databaseName' | 'username' | 'password'
const touched = reactive<Record<FieldKey, boolean>>({ name: false, host: false, port: false, databaseName: false, username: false, password: false })

function reset() {
  const e = props.editing
  Object.assign(form, e
    ? { name: e.name, dbType: e.dbType, host: e.host, port: e.port, databaseName: e.databaseName, username: e.username, password: '' }
    : { name: '', dbType: 'POSTGRESQL', host: '', port: 5432, databaseName: '', username: '', password: '' })
  portCustomized.value = !!e
  testResult.value = null
  testedKey.value = null
  testedAt.value = null
  showPassword.value = false
  for (const k of Object.keys(touched) as FieldKey[]) touched[k] = false
}
watch(() => props.modelValue, (open) => { if (open) reset() }, { immediate: true })
watch(() => form.dbType, (t) => {
  if (!portCustomized.value) form.port = DB_TYPES.find((d) => d.value === t)?.defaultPort ?? form.port
})
function onPortInput(v: string | number | null) {
  form.port = Number(v)
  portCustomized.value = true
}
/** 'host:port'를 붙여 넣었으면 칸을 벗어날 때 나눈다 — 나눈 포트는 사용자가 고른 값으로 본다. */
function onHostBlur() {
  const { host, port } = splitHostPort(form.host)
  form.host = host
  touched.host = true
  if (port != null) onPortInput(port)
}

const dbNameLabel = computed(() => (form.dbType === 'ORACLE' ? '서비스명' : 'DB 이름'))
const passwordRequired = computed(() => !props.editing)
const errors = computed(() => ({
  name: requiredError(form.name),
  host: hostError(form.host),
  port: portError(form.port),
  databaseName: requiredError(form.databaseName),
  username: requiredError(form.username),
  password: passwordRequired.value ? requiredError(form.password) : null,
}))
const canSubmit = computed(() => Object.values(errors.value).every((e) => e == null))
/**
 * 칸을 한 번 벗어난 뒤부터 오류를 보인다. Quasar rules(lazy-rules)는 값이 코드로 바뀔 때(host:port 분리 등)
 * 다시 검증하지 않아 오류가 남는다 — touched + computed로 직접 묶는다.
 */
const fieldError = (key: FieldKey) => (touched[key] ? errors.value[key] : null)

function fields() {
  return {
    dbType: form.dbType, host: form.host.trim(), port: form.port,
    databaseName: form.databaseName.trim(), username: form.username.trim(), password: form.password,
  }
}

const testStatus = computed(() => testState({
  testing: testing.value,
  result: testResult.value,
  testedKey: testedKey.value,
  currentKey: connectionKey(form),
}))
const testedTime = computed(() => testedAt.value
  ? `${String(testedAt.value.getHours()).padStart(2, '0')}:${String(testedAt.value.getMinutes()).padStart(2, '0')}`
  : '')
const failedSave = computed(() => testStatus.value === 'fail')

async function runTest() {
  const key = connectionKey(form)
  testing.value = true
  try {
    testResult.value = await testDbConnection(props.editing ? { id: props.editing.id, ...fields() } : fields())
  } catch (e: any) {
    testResult.value = { ok: false, message: e?.data?.message ?? '접속 테스트 요청 실패' }
  } finally {
    testedKey.value = key
    testedAt.value = new Date()
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

defineExpose({ form, save, runTest, testResult, testStatus, dbNameLabel, canSubmit, onPortInput })
</script>

<template>
  <AdminFormDialog
    :model-value="modelValue"
    :title="editing ? 'DB 접속정보 수정' : 'DB 접속정보 추가'"
    :submit-label="failedSave ? '그래도 저장' : '저장'"
    :submit-color="failedSave ? 'warning' : undefined"
    :submitting="submitting"
    :can-submit="canSubmit"
    @update:model-value="emit('update:modelValue', $event)"
    @submit="save"
  >
    <div class="column q-gutter-y-sm db-form">
      <div class="row items-start no-wrap text-caption text-grey-8 db-hint">
        <q-icon name="shield" color="amber-9" size="16px" class="q-mr-xs q-mt-xs" />
        <span>읽기 전용 계정을 권장합니다. 쓰기 SQL은 질문 세션에서 차단되지만 완전하지 않습니다.</span>
      </div>

      <q-input
        v-model="form.name"
        label="이름 *"
        outlined
        :error="!!fieldError('name')"
        :error-message="fieldError('name') ?? undefined"
        @blur="touched.name = true"
        hint="질문 화면에 보일 이름 (예: 개발 DB)"
        data-test="db-name"
      />

      <div class="full-width">
        <div class="db-label">DB 종류 *</div>
        <div class="db-type-grid" role="group" aria-label="DB 종류">
          <q-btn
            v-for="t in DB_TYPES"
            :key="t.value"
            no-caps
            :unelevated="form.dbType === t.value"
            :outline="form.dbType !== t.value"
            :color="form.dbType === t.value ? 'primary' : 'grey-7'"
            :label="t.label"
            :aria-pressed="form.dbType === t.value ? 'true' : 'false'"
            :data-test="`db-type-${t.value}`"
            class="db-type-btn"
            @click="form.dbType = t.value"
          />
        </div>
      </div>

      <div class="db-section-title">서버</div>
      <div class="row no-wrap items-start q-gutter-x-sm db-host-row">
        <q-input
          v-model="form.host"
          class="col"
          label="호스트 *"
          outlined
          :error="!!fieldError('host')"
          :error-message="fieldError('host') ?? undefined"
          autocomplete="off"
          data-test="db-host"
          @blur="onHostBlur"
        />
        <q-input
          :model-value="form.port"
          class="db-port"
          type="number"
          label="포트 *"
          outlined
          no-error-icon
          :error="!!fieldError('port')"
          :error-message="fieldError('port') ?? undefined"
          data-test="db-port"
          @blur="touched.port = true"
          @update:model-value="onPortInput"
        />
      </div>
      <q-input
        v-model="form.databaseName"
        :label="`${dbNameLabel} *`"
        outlined
        :error="!!fieldError('databaseName')"
        :error-message="fieldError('databaseName') ?? undefined"
        @blur="touched.databaseName = true"
        data-test="db-database"
      />

      <div class="db-section-title">인증</div>
      <q-input
        v-model="form.username"
        label="사용자 *"
        outlined
        :error="!!fieldError('username')"
        :error-message="fieldError('username') ?? undefined"
        @blur="touched.username = true"
        autocomplete="off"
        data-test="db-username"
      />
      <q-input
        v-model="form.password"
        :type="showPassword ? 'text' : 'password'"
        :label="passwordRequired ? '비밀번호 *' : '비밀번호'"
        :hint="editing ? '비워 두면 기존 비밀번호를 유지합니다' : undefined"
        outlined
        :error="!!fieldError('password')"
        :error-message="fieldError('password') ?? undefined"
        @blur="touched.password = true"
        autocomplete="new-password"
        data-test="db-password"
      >
        <template #append>
          <q-btn
            flat
            round
            dense
            :icon="showPassword ? 'visibility_off' : 'visibility'"
            :aria-label="showPassword ? '비밀번호 가리기' : '비밀번호 보기'"
            data-test="db-password-toggle"
            @click="showPassword = !showPassword"
          />
        </template>
      </q-input>

      <div class="db-test-box" :class="`db-test-box--${testStatus}`" :data-state="testStatus" data-test="db-test-status">
        <div class="row items-center no-wrap q-gutter-x-sm">
          <q-spinner v-if="testStatus === 'testing'" size="18px" color="primary" />
          <q-icon v-else :name="{ idle: 'info', ok: 'check_circle', fail: 'error', stale: 'refresh' }[testStatus]" size="18px" />
          <div class="col db-test-text" data-test="db-test-result">
            <template v-if="testStatus === 'idle'">저장 전에 접속 테스트를 권장합니다</template>
            <template v-else-if="testStatus === 'testing'">접속 중… (최대 10초)</template>
            <template v-else-if="testStatus === 'ok'">{{ testResult?.message }} · {{ testedTime }}</template>
            <template v-else-if="testStatus === 'fail'">{{ testResult?.message }}</template>
            <template v-else>입력이 바뀌었습니다 — 다시 테스트하세요</template>
          </div>
          <q-btn
            unelevated
            no-caps
            dense
            class="q-px-sm"
            icon="cable"
            label="접속 테스트"
            :color="testStatus === 'ok' ? 'grey-3' : 'primary'"
            :text-color="testStatus === 'ok' ? 'grey-9' : 'white'"
            :loading="testing"
            data-test="db-test"
            @click="runTest"
          />
        </div>
      </div>
    </div>
  </AdminFormDialog>
</template>

<style scoped>
.db-hint {
  line-height: 1.4;
}
.db-label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.6);
  margin-bottom: 6px;
}
/* 폭에 따라 4열(데스크톱 카드 560px) ↔ 2열(360~390px)로만 바뀐다 — 브레이크포인트 분기 없음 */
.db-type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 8px;
}
.db-type-btn {
  min-height: 40px;
}
.db-section-title {
  font-size: 12px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.6);
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  padding: 8px 0 4px;
}
.db-port {
  width: 120px;
  flex: none;
}
.db-test-box {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 6px;
  padding: 8px 10px;
  background: #fafafa;
  color: rgba(0, 0, 0, 0.7);
}
.db-test-box--ok {
  border-color: #a5d6a7;
  background: #f1f8e9;
  color: #2e7d32;
}
.db-test-box--fail {
  border-color: #ef9a9a;
  background: #ffebee;
  color: #c62828;
}
.db-test-text {
  font-size: 13px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
</style>
