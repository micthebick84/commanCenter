<script setup lang="ts">
// DB 연결 선택 (스펙 2026-10-02 §7) — q-menu 안에서 쓴다. 공용(REPO)·내 접속정보(USER) 체크 목록 + "새 접속 추가".
// 상한 MAX_DB_SELECTION. 비활성 항목(관리자에게만 보임)은 고를 수 없다. 새로 저장하면 reload를 올리고 바로 선택한다.
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import { MAX_DB_SELECTION, dbTypeLabel, type DbConnectionView } from '~/composables/dbConnections'

const props = defineProps<{ items: DbConnectionView[]; repoCatalogId: number }>()
const model = defineModel<number[]>({ default: () => [] })
const emit = defineEmits<{ reload: [] }>()

const $q = useQuasar()
const dialogOpen = ref(false)
const repoItems = computed(() => props.items.filter((i) => i.scope === 'REPO'))
const userItems = computed(() => props.items.filter((i) => i.scope === 'USER'))

function toggle(id: number) {
  const it = props.items.find((i) => i.id === id)
  if (!it || !it.enabled) return
  const next = [...model.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1)
  else {
    if (next.length >= MAX_DB_SELECTION) {
      $q.notify({ type: 'warning', message: `DB 연결은 최대 ${MAX_DB_SELECTION}개까지 고를 수 있습니다` })
      return
    }
    next.push(id)
  }
  model.value = next
}

function onSaved(v: DbConnectionView) {
  emit('reload')
  if (!model.value.includes(v.id) && model.value.length < MAX_DB_SELECTION) model.value = [...model.value, v.id]
}

defineExpose({ toggle })
</script>

<template>
  <div class="db-picker">
    <div class="picker-label">공용</div>
    <div data-test="db-section-repo">
      <div v-if="repoItems.length === 0" class="text-caption text-grey-6 q-px-sm">등록된 공용 접속정보가 없습니다</div>
      <q-item v-for="i in repoItems" :key="i.id" dense tag="label" :disable="!i.enabled">
        <q-item-section side>
          <q-checkbox :model-value="model.includes(i.id)" :disable="!i.enabled" dense @update:model-value="toggle(i.id)" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ i.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(i.dbType)" /></q-item-label>
          <q-item-label caption>{{ i.host }}:{{ i.port }}/{{ i.databaseName }}</q-item-label>
        </q-item-section>
      </q-item>
    </div>
    <div class="picker-label q-mt-sm">내 접속정보</div>
    <div data-test="db-section-user">
      <div v-if="userItems.length === 0" class="text-caption text-grey-6 q-px-sm">아직 없습니다</div>
      <q-item v-for="i in userItems" :key="i.id" dense tag="label">
        <q-item-section side>
          <q-checkbox :model-value="model.includes(i.id)" dense @update:model-value="toggle(i.id)" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ i.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(i.dbType)" /></q-item-label>
          <q-item-label caption>{{ i.host }}:{{ i.port }}/{{ i.databaseName }}</q-item-label>
        </q-item-section>
      </q-item>
    </div>
    <q-btn flat dense no-caps icon="add" label="새 접속 추가" color="primary" class="q-mt-sm" data-test="db-add" @click="dialogOpen = true" />
    <DbConnectionDialog v-model="dialogOpen" scope="USER" :repo-catalog-id="repoCatalogId" :editing="null" @saved="onSaved" />
  </div>
</template>

<style scoped>
.db-picker {
  min-width: 280px;
  padding: 8px;
}
.picker-label {
  font-size: 12px;
  font-weight: 600;
  color: #616161;
  padding: 0 8px 4px;
}
</style>
