<script setup lang="ts">
// 레포 공용(REPO) DB 접속정보 관리 (스펙 2026-10-02 §7) — 관리 > 레포 카탈로그에서 연다.
// 목록 API는 관리자에게 비활성 REPO도 돌려준다. USER 행은 여기서 다루지 않는다(본인만 관리).
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import {
  deleteDbConnection, dbTypeLabel, fetchDbConnections, saveDbConnection, type DbConnectionView,
} from '~/composables/dbConnections'

const props = defineProps<{ modelValue: boolean; repo: { id: number; alias: string } | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const $q = useQuasar()
const rows = ref<DbConnectionView[]>([])
const featureEnabled = ref(true)
const editOpen = ref(false)
const editing = ref<DbConnectionView | null>(null)

async function load() {
  if (!props.repo) return
  const r = await fetchDbConnections(props.repo.id)
  featureEnabled.value = r.enabled
  rows.value = r.items.filter((i) => i.scope === 'REPO')
}
watch(() => [props.modelValue, props.repo?.id], ([open]) => { if (open) load() }, { immediate: true })

function openAdd() {
  editing.value = null
  editOpen.value = true
}
function openEdit(r: DbConnectionView) {
  editing.value = r
  editOpen.value = true
}

async function toggleEnabled(r: DbConnectionView) {
  try {
    await saveDbConnection({
      scope: 'REPO', repoCatalogId: r.repoCatalogId, name: r.name, dbType: r.dbType, host: r.host, port: r.port,
      databaseName: r.databaseName, username: r.username, password: '', enabled: !r.enabled,
    }, r.id)
    await load()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '변경 실패' })
  }
}

function remove(r: DbConnectionView) {
  $q.dialog({ title: '삭제', message: `'${r.name}' 접속정보를 삭제할까요? 이 연결을 고른 질문 세션은 다음 질문부터 DB 도구 없이 답합니다.`, cancel: true })
    .onOk(async () => {
      try {
        await deleteDbConnection(r.id)
        await load()
      } catch (e: any) {
        $q.notify({ type: 'negative', message: e?.data?.message ?? '삭제 실패' })
      }
    })
}

defineExpose({ rows, featureEnabled, toggleEnabled, load })
</script>

<template>
  <q-dialog :model-value="modelValue" :maximized="$q.screen.lt.md" @update:model-value="emit('update:modelValue', $event)">
    <div class="db-admin-card">
      <q-card flat>
        <q-card-section class="row items-center no-wrap">
          <div class="text-subtitle1 col">DB 접속(공용) — {{ repo?.alias }}</div>
          <q-btn flat round dense icon="close" aria-label="닫기" @click="emit('update:modelValue', false)" />
        </q-card-section>
        <q-card-section v-if="!featureEnabled" class="text-grey-8" data-test="db-admin-disabled">
          DB 접속정보 기능이 꺼져 있습니다 (NETISMAKER_DB_SECRET_KEY / NETISMAKER_DB_SECRET_SALT 미설정).
        </q-card-section>
        <template v-else>
          <q-list separator>
            <q-item v-if="rows.length === 0"><q-item-section class="text-grey-6">등록된 공용 접속정보가 없습니다</q-item-section></q-item>
            <q-item v-for="r in rows" :key="r.id" :class="{ 'text-grey-6': !r.enabled }">
              <q-item-section>
                <q-item-label>{{ r.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(r.dbType)" /></q-item-label>
                <q-item-label caption>{{ r.host }}:{{ r.port }}/{{ r.databaseName }} · {{ r.username }}</q-item-label>
              </q-item-section>
              <q-item-section side class="row no-wrap items-center">
                <q-toggle :model-value="r.enabled" dense @update:model-value="toggleEnabled(r)" />
                <q-btn flat dense icon="edit" color="primary" aria-label="수정" @click="openEdit(r)" />
                <q-btn flat dense icon="delete" color="negative" aria-label="삭제" @click="remove(r)" />
              </q-item-section>
            </q-item>
          </q-list>
          <q-card-actions align="right">
            <q-btn flat no-caps icon="add" label="공용 접속 추가" color="primary" data-test="db-admin-add" @click="openAdd" />
          </q-card-actions>
        </template>
      </q-card>
      <DbConnectionDialog
        v-if="repo"
        v-model="editOpen"
        scope="REPO"
        :repo-catalog-id="repo.id"
        :editing="editing"
        @saved="load"
      />
    </div>
  </q-dialog>
</template>

<style scoped>
.db-admin-card {
  width: min(640px, 100vw);
  background: white;
}
</style>
