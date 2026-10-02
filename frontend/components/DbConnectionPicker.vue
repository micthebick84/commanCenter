<script setup lang="ts">
// DB 연결 선택 (스펙 2026-10-02 §7) — q-menu 안에서 쓴다. 공용(REPO)·내 접속정보(USER) 체크 목록 + "새 접속 추가".
// 상한 MAX_DB_SELECTION. 비활성 항목(관리자에게만 보임)은 새로 고를 수 없다. 새로 저장하면 reload를 올리고 바로 선택한다.
// 이미 선택돼 있는데 목록에 없거나(삭제·다른 사용자 것) 비활성이 된 id는 "사용할 수 없는 연결"로 보여 주고 해제만 허용한다
// (해제할 길이 없으면 대화가 그 id에 갇히고, 서버도 이미 있던 id는 검증하지 않는다 — 스펙 §5.3).
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import { MAX_DB_SELECTION, dbTypeLabel, type DbConnectionChip, type DbConnectionView } from '~/composables/dbConnections'

const props = defineProps<{ items: DbConnectionView[]; repoCatalogId: number; knownChips?: DbConnectionChip[] }>()
const model = defineModel<number[]>({ default: () => [] })
const emit = defineEmits<{ reload: [] }>()

const $q = useQuasar()
const dialogOpen = ref(false)
const repoItems = computed(() => props.items.filter((i) => i.scope === 'REPO'))
const userItems = computed(() => props.items.filter((i) => i.scope === 'USER'))

// 목록에 없는 선택 id — 해제 전용 행
const unavailableIds = computed(() => model.value.filter((id) => !props.items.some((i) => i.id === id)))
function unavailableLabel(id: number) {
  const chip = props.knownChips?.find((c) => c.id === id)
  return `${chip ? chip.name : `연결 #${id}`} (삭제·비활성)`
}

function toggle(id: number) {
  const next = [...model.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1) // 해제는 항상 가능
  else {
    const it = props.items.find((i) => i.id === id)
    if (!it || !it.enabled) return
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
      <q-item v-for="i in repoItems" :key="i.id" dense tag="label" :disable="!i.enabled && !model.includes(i.id)">
        <q-item-section side>
          <q-checkbox :model-value="model.includes(i.id)" :disable="!i.enabled && !model.includes(i.id)" dense @update:model-value="toggle(i.id)" />
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
    <div v-if="unavailableIds.length" data-test="db-section-unavailable">
      <div class="picker-label q-mt-sm">사용할 수 없는 연결</div>
      <q-item v-for="id in unavailableIds" :key="id" dense tag="label">
        <q-item-section side>
          <q-checkbox :model-value="true" dense :data-test="`db-unavailable-${id}`" @update:model-value="toggle(id)" />
        </q-item-section>
        <q-item-section>
          <q-item-label class="text-grey-7">{{ unavailableLabel(id) }}</q-item-label>
          <q-item-label caption>해제하면 다시 고를 수 없습니다</q-item-label>
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
