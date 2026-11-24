<script setup lang="ts">
import { DB_TYPES } from '~/composables/dbConnections'
import {
  MAX_DB_SLOTS,
  MAX_DEPLOY_VARIABLES,
  PLACEHOLDER_HINT,
  cloneDeployConfig,
  newDbSlot,
  newDeployVariable,
  type DeployConfig,
} from '~/composables/deployConfig'

const props = defineProps<{
  modelValue: DeployConfig
  mobile?: boolean
  /** 복사 후보: 다른 레포 중 배포 설정이 있는 것 */
  copySources?: Array<{ id: number; alias: string; deployConfig: DeployConfig }>
}>()
const emit = defineEmits<{ 'update:modelValue': [DeployConfig] }>()

const copyFrom = ref<number | null>(null)
const copyOptions = computed(() => (props.copySources ?? []).map((s) => ({ label: s.alias, value: s.id })))

function update(mutator: (c: DeployConfig) => void) {
  const next = cloneDeployConfig(props.modelValue)
  mutator(next)
  emit('update:modelValue', next)
}

function onCopy(id: number | null) {
  const src = (props.copySources ?? []).find((s) => s.id === id)
  if (src) emit('update:modelValue', cloneDeployConfig(src.deployConfig))
  copyFrom.value = null
}

// 항목은 편집하는 동안 순서 있는 행 배열로 들고 있고, 내보낼 때만 객체로 바꾼다 — 키를 고치다 다른 행의 키와
// 잠깐 겹쳐도 행이 합쳐지거나 덮이지 않고, 키가 빈 행을 여러 개 둘 수 있다(빈 키 행은 내보내지 않는다).
type EntryRow = { id: number; key: string; value: string }
let rowSeq = 0
const entryRowsBySlot = ref<EntryRow[][]>([])

function rowsToEntries(rs: EntryRow[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const r of rs) if (r.key.trim() !== '') out[r.key] = r.value
  return out
}
function syncRows() {
  entryRowsBySlot.value = props.modelValue.databases.map((d, i) => {
    const cur = entryRowsBySlot.value[i]
    if (cur && JSON.stringify(rowsToEntries(cur)) === JSON.stringify(d.target.entries)) return cur
    return Object.entries(d.target.entries).map(([key, value]) => ({ id: ++rowSeq, key, value }))
  })
}
watch(() => props.modelValue, syncRows, { immediate: true, deep: true })

function entryRows(i: number): EntryRow[] {
  return entryRowsBySlot.value[i] ?? []
}
function commitEntries(i: number) {
  update((c) => {
    c.databases[i].target.entries = rowsToEntries(entryRows(i))
  })
}
function setEntryKey(i: number, row: EntryRow, key: string) {
  row.key = key
  commitEntries(i)
}
function setEntryValue(i: number, row: EntryRow, value: string) {
  row.value = value
  commitEntries(i)
}
function addEntry(i: number) {
  if (!entryRowsBySlot.value[i]) entryRowsBySlot.value[i] = []
  entryRowsBySlot.value[i].push({ id: ++rowSeq, key: '', value: '' })
}
function removeEntry(i: number, row: EntryRow) {
  entryRowsBySlot.value[i] = entryRows(i).filter((r) => r.id !== row.id)
  commitEntries(i)
}
function setKind(i: number, kind: 'ENV' | 'FILE') {
  update((c) => {
    c.databases[i].target.kind = kind
    c.databases[i].target.path = kind === 'FILE' ? (c.databases[i].target.path ?? 'WEB-INF/classes/') : null
  })
}
const str = (v: unknown) => String(v ?? '')
</script>

<template>
  <div class="column q-gutter-y-sm" data-test="deploy-config-editor">
    <div class="text-subtitle2">배포 설정 (DB 자리·변수)</div>
    <div class="text-caption text-grey-7">
      배포 때 사용자가 고른 DB 접속정보를 어느 파일·환경변수에 넣을지 정합니다. 비워 두면 배포 설정을 쓰지 않습니다.
    </div>
    <q-select
      v-if="copyOptions.length"
      :model-value="copyFrom"
      :options="copyOptions"
      emit-value
      map-options
      clearable
      outlined
      :dense="!mobile"
      label="다른 레포에서 복사"
      data-test="deploy-copy-from"
      @update:model-value="onCopy"
    />

    <div class="text-weight-medium">DB 자리 ({{ modelValue.databases.length }}/{{ MAX_DB_SLOTS }})</div>
    <div class="text-caption text-grey-7" data-test="deploy-placeholder-hint">{{ PLACEHOLDER_HINT }}</div>
    <q-card v-for="(d, i) in modelValue.databases" :key="i" flat bordered class="q-pa-sm column q-gutter-y-sm" data-test="deploy-db-slot">
      <div class="row q-col-gutter-sm">
        <q-input
          class="col-12 col-sm-5"
          :model-value="d.slot"
          label="자리 이름"
          hint="소문자·숫자·하이픈"
          outlined
          dense
          autocapitalize="off"
          spellcheck="false"
          data-test="deploy-slot-name"
          @update:model-value="(v) => update((c) => (c.databases[i].slot = str(v)))"
        />
        <q-input
          class="col-12 col-sm-7"
          :model-value="d.label"
          label="표시 이름"
          outlined
          dense
          maxlength="40"
          data-test="deploy-slot-label"
          @update:model-value="(v) => update((c) => (c.databases[i].label = str(v)))"
        />
      </div>
      <q-select
        :model-value="d.dbTypes"
        :options="DB_TYPES"
        option-value="value"
        option-label="label"
        emit-value
        map-options
        multiple
        use-chips
        outlined
        dense
        label="허용 DB 종류"
        data-test="deploy-slot-types"
        @update:model-value="(v) => update((c) => (c.databases[i].dbTypes = v ?? []))"
      />
      <q-btn-toggle
        :model-value="d.target.kind"
        :options="[
          { label: '파일(.properties)', value: 'FILE' },
          { label: '환경변수', value: 'ENV' },
        ]"
        no-caps
        dense
        unelevated
        toggle-color="primary"
        data-test="deploy-slot-kind"
        @update:model-value="(v) => setKind(i, v)"
      />
      <q-input
        v-if="d.target.kind === 'FILE'"
        :model-value="d.target.path ?? ''"
        label="파일 경로"
        hint="WEB-INF/classes/ 로 시작하고 .properties 로 끝남"
        outlined
        dense
        autocapitalize="off"
        spellcheck="false"
        data-test="deploy-slot-path"
        @update:model-value="(v) => update((c) => (c.databases[i].target.path = str(v)))"
      />
      <div v-for="row in entryRows(i)" :key="row.id" class="row q-col-gutter-xs items-center no-wrap" data-test="deploy-entry-row">
        <q-input class="col" :model-value="row.key" label="항목 키" outlined dense autocapitalize="off" spellcheck="false" data-test="deploy-entry-key" @update:model-value="(nv) => setEntryKey(i, row, str(nv))" />
        <q-input class="col" :model-value="row.value" label="값" outlined dense autocapitalize="off" spellcheck="false" data-test="deploy-entry-value" @update:model-value="(nv) => setEntryValue(i, row, str(nv))" />
        <q-btn flat dense round icon="close" aria-label="항목 삭제" @click="removeEntry(i, row)" />
      </div>
      <div class="row justify-between">
        <q-btn flat dense no-caps icon="add" label="항목 추가" data-test="deploy-add-entry" @click="addEntry(i)" />
        <q-btn flat dense no-caps color="negative" icon="delete" label="자리 삭제" data-test="deploy-remove-db" @click="update((c) => c.databases.splice(i, 1))" />
      </div>
    </q-card>
    <div>
      <q-btn
        flat
        dense
        no-caps
        icon="add"
        label="DB 자리 추가"
        :disable="modelValue.databases.length >= MAX_DB_SLOTS"
        data-test="deploy-add-db"
        @click="update((c) => c.databases.push(newDbSlot(c.databases)))"
      />
    </div>

    <div class="text-weight-medium">변수 ({{ modelValue.variables.length }}/{{ MAX_DEPLOY_VARIABLES }})</div>
    <q-card v-for="(v, i) in modelValue.variables" :key="'v' + i" flat bordered class="q-pa-sm column q-gutter-y-sm" data-test="deploy-variable">
      <q-input :model-value="v.key" label="이름" outlined dense autocapitalize="off" spellcheck="false" data-test="deploy-var-key" @update:model-value="(nv) => update((c) => (c.variables[i].key = str(nv)))" />
      <q-input :model-value="v.description" label="설명" outlined dense @update:model-value="(nv) => update((c) => (c.variables[i].description = str(nv)))" />
      <div class="row">
        <q-toggle :model-value="v.secret" label="비밀" dense @update:model-value="(nv) => update((c) => (c.variables[i].secret = !!nv))" />
        <q-toggle class="q-ml-md" :model-value="v.required" label="필수" dense @update:model-value="(nv) => update((c) => (c.variables[i].required = !!nv))" />
      </div>
      <q-input
        :model-value="v.format ?? ''"
        label="형식 (선택)"
        hint="{값} 을 한 번 포함"
        outlined
        dense
        autocapitalize="off"
        spellcheck="false"
        @update:model-value="(nv) => update((c) => (c.variables[i].format = str(nv)))"
      />
      <div class="row q-col-gutter-sm">
        <q-input class="col-12 col-sm-7" :model-value="v.filePath ?? ''" label="파일 대상 경로 (선택)" outlined dense autocapitalize="off" spellcheck="false" @update:model-value="(nv) => update((c) => (c.variables[i].filePath = str(nv)))" />
        <q-input class="col-12 col-sm-5" :model-value="v.fileEntry ?? ''" label="파일 항목 키 (선택)" outlined dense autocapitalize="off" spellcheck="false" @update:model-value="(nv) => update((c) => (c.variables[i].fileEntry = str(nv)))" />
      </div>
      <div class="row justify-end">
        <q-btn flat dense no-caps color="negative" icon="delete" label="변수 삭제" @click="update((c) => c.variables.splice(i, 1))" />
      </div>
    </q-card>
    <div>
      <q-btn
        flat
        dense
        no-caps
        icon="add"
        label="변수 추가"
        :disable="modelValue.variables.length >= MAX_DEPLOY_VARIABLES"
        data-test="deploy-add-var"
        @click="update((c) => c.variables.push(newDeployVariable()))"
      />
    </div>
  </div>
</template>
