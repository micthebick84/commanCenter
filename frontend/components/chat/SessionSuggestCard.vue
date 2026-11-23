<script setup lang="ts">
// 대화 중 제안 카드 (스펙 2026-11-22 §5·§8) — 가장 최근 답변 아래, 그 답변의 PENDING 제안을 한 장에 모은다.
// 제안은 제안일 뿐: 카드는 고른 것만 accept로 알리고, 붙이는 일은 페이지가 기존 ask 바디로 한다.
// reason·targets는 Claude가 쓴 글이라 텍스트 보간으로만 그린다(v-html 금지).
// 클래스명은 Quasar 반응형 표시 헬퍼(xs/sm/md/lg/xl, gt-*, lt-*, *-hide)와 겹치지 않게 유지할 것.
import { computed, ref, watch } from 'vue'
import { dbTypeLabel } from '~/composables/dbConnections'
import { canPickMore, initialDbPicks } from '~/composables/sessionSuggest'

// defineProps·defineEmits 타입은 파일 안에 둔다 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
interface DbOption {
  id: number
  name: string
  dbType: string
}
interface Suggestion {
  id: number
  turnSeq: number
  kind: 'DB' | 'REF_REPO'
  status: 'PENDING' | 'ACCEPTED' | 'DISMISSED' | 'SUPERSEDED'
  reason: string
  targets: string[]
  dbConnectionId: number | null
  repoCatalogId: number | null
  repoAlias: string | null
  defaultBranch: string | null
  dbOptions: DbOption[]
  dbSlotsLeft: number
  refSlotsLeft: number
}
interface Picks {
  dbConnectionIds: number[]
  refs: Array<{ repoCatalogId: number; branch: string }>
  suggestionIds: number[]
}

const props = defineProps<{ suggestions: Suggestion[]; disabled: boolean; busy: boolean }>()
const emit = defineEmits<{ accept: [picks: Picks]; dismiss: [ids: number[]] }>()

const dbSuggestions = computed(() => props.suggestions.filter((s) => s.kind === 'DB'))
const refSuggestions = computed(() => props.suggestions.filter((s) => s.kind === 'REF_REPO' && s.repoCatalogId != null))
const locked = computed(() => props.disabled || props.busy)

// 제안 id → 체크한 연결 id. SSE가 제안을 하나씩, 같은 id로 다시 보내므로 처음 본 id만 미리 체크하고
// 이미 본 id는 사용자가 바꾼 체크를 그대로 둔다. 참조 제안도 Claude가 그 레포를 골랐으므로 목록 순서대로 남은 자리만큼 미리 체크한다
// (버튼 한 번으로 붙게) — 기본 브랜치가 없는 것은 붙일 수 없으니 건너뛰고 자리도 쓰지 않는다.
const dbPicks = ref<Record<number, number[]>>({})
const refPicked = ref<Record<number, boolean>>({})
const seen = new Set<number>()
watch(
  () => props.suggestions,
  (list) => {
    for (const s of list) {
      if (seen.has(s.id)) continue
      seen.add(s.id)
      if (s.kind === 'DB') {
        dbPicks.value = { ...dbPicks.value, [s.id]: initialDbPicks(s) }
      } else if (s.kind === 'REF_REPO' && s.repoCatalogId != null && s.defaultBranch) {
        const checked = list.filter((x) => x.kind === 'REF_REPO' && refPicked.value[x.id]).length
        if (canPickMore(checked, s.refSlotsLeft)) refPicked.value = { ...refPicked.value, [s.id]: true }
      }
    }
  },
  { immediate: true },
)

const pickedOf = (s: Suggestion) => dbPicks.value[s.id] ?? []
const isDbPicked = (s: Suggestion, optId: number) => pickedOf(s).includes(optId)
const dbHasRoom = (s: Suggestion) => canPickMore(pickedOf(s).length, s.dbSlotsLeft)
const dbOptDisabled = (s: Suggestion, optId: number) => locked.value || (!isDbPicked(s, optId) && !dbHasRoom(s))
const dbFull = (s: Suggestion) => !dbHasRoom(s) && s.dbOptions.some((o) => !isDbPicked(s, o.id))
function toggleDb(s: Suggestion, optId: number) {
  if (dbOptDisabled(s, optId)) return
  const cur = pickedOf(s)
  dbPicks.value = {
    ...dbPicks.value,
    [s.id]: cur.includes(optId) ? cur.filter((x) => x !== optId) : [...cur, optId],
  }
}

// 참조 자리는 세션 하나에 같은 값이지만, 제안마다 실려 오므로 가장 작은 값을 쓴다.
const refSlots = computed(() => Math.min(...refSuggestions.value.map((s) => s.refSlotsLeft)))
const refPickedCount = computed(() => refSuggestions.value.filter((s) => refPicked.value[s.id]).length)
const refHasRoom = computed(() => canPickMore(refPickedCount.value, refSlots.value))
const refPickable = (s: Suggestion) => !!s.defaultBranch
const refDisabled = (s: Suggestion) =>
  locked.value || !refPickable(s) || (!refPicked.value[s.id] && !refHasRoom.value)
const refFull = computed(
  () => !refHasRoom.value && refSuggestions.value.some((s) => refPickable(s) && !refPicked.value[s.id]),
)
function toggleRef(s: Suggestion) {
  if (refDisabled(s)) return
  refPicked.value = { ...refPicked.value, [s.id]: !refPicked.value[s.id] }
}

const picks = computed<Picks>(() => {
  const dbConnectionIds: number[] = []
  const refs: Picks['refs'] = []
  const suggestionIds: number[] = []
  for (const s of props.suggestions) {
    if (s.kind === 'DB') {
      const ids = pickedOf(s).filter((id) => s.dbOptions.some((o) => o.id === id))
      if (ids.length === 0) continue
      dbConnectionIds.push(...ids.filter((id) => !dbConnectionIds.includes(id)))
      suggestionIds.push(s.id)
    } else if (s.repoCatalogId != null && s.defaultBranch && refPicked.value[s.id]) {
      refs.push({ repoCatalogId: s.repoCatalogId, branch: s.defaultBranch })
      suggestionIds.push(s.id)
    }
  }
  return { dbConnectionIds, refs, suggestionIds }
})
const canAccept = computed(() => !locked.value && picks.value.suggestionIds.length > 0)

function onAccept() {
  if (!canAccept.value) return
  emit('accept', picks.value)
}
function onDismiss() {
  if (locked.value) return
  emit('dismiss', props.suggestions.map((s) => s.id))
}
</script>

<template>
  <div class="suggest-card" data-test="suggest-card">
    <div v-for="s in dbSuggestions" :key="s.id" class="suggest-block" data-test="suggest-db">
      <div class="suggest-title">DB 연결이 있으면 더 정확히 답할 수 있습니다</div>
      <div class="suggest-reason">{{ s.reason }}</div>
      <div v-if="s.targets.length" class="suggest-label">보고 싶은 대상: {{ s.targets.join(', ') }}</div>
      <div v-if="s.dbOptions.length === 0" class="suggest-label" data-test="suggest-db-none">붙일 수 있는 연결이 없습니다</div>
      <template v-else>
        <q-checkbox
          v-for="o in s.dbOptions"
          :key="o.id"
          :model-value="isDbPicked(s, o.id)"
          :disable="dbOptDisabled(s, o.id)"
          dense
          :label="`${o.name} · ${dbTypeLabel(o.dbType)}`"
          class="suggest-check"
          :data-test="`suggest-db-opt-${o.id}`"
          @update:model-value="toggleDb(s, o.id)"
        />
        <div v-if="dbFull(s)" class="suggest-label" data-test="suggest-db-full">DB 연결은 3개까지 붙일 수 있습니다</div>
      </template>
    </div>

    <div
      v-for="s in refSuggestions"
      :key="s.id"
      class="suggest-block"
      :data-test="`suggest-ref-${s.repoCatalogId}`"
    >
      <div class="suggest-title">이 레포도 함께 보면 좋습니다: {{ s.repoAlias }}</div>
      <div class="suggest-reason">{{ s.reason }}</div>
      <q-checkbox
        :model-value="!!refPicked[s.id]"
        :disable="refDisabled(s)"
        dense
        :label="`${s.repoAlias} 참조로 추가`"
        class="suggest-check"
        @update:model-value="toggleRef(s)"
      />
    </div>
    <div v-if="refFull" class="suggest-label" data-test="suggest-ref-full">참조 레포는 3개까지 붙일 수 있습니다</div>

    <div class="suggest-actions">
      <q-btn
        flat
        dense
        no-caps
        color="grey-8"
        label="닫기"
        :disable="locked"
        data-test="suggest-dismiss"
        @click="onDismiss"
      />
      <q-btn
        unelevated
        no-caps
        color="primary"
        label="연결하고 다시 묻기"
        :disable="!canAccept"
        :loading="busy"
        data-test="suggest-accept"
        @click="onAccept"
      />
    </div>
  </div>
</template>

<style scoped>
/* 프레임·글자·색은 RepoRouteCard(.route-card)와 같은 값 */
.suggest-card {
  width: 100%;
  min-width: 0;
  border: 1px solid #d7dde6;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
  background: #fff;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12.5px;
  color: #25303f;
}
@media (max-width: 599px) {
  .suggest-card {
    flex: 1 1 auto;
    max-width: 100%;
  }
}
.suggest-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.suggest-block + .suggest-block {
  border-top: 1px solid #eceff3;
  padding-top: 8px;
}
.suggest-title {
  font-weight: 600;
}
.suggest-reason {
  word-break: break-word;
}
.suggest-label {
  font-size: 11.5px;
  color: #5b6b7d;
  word-break: break-word;
}
.suggest-check {
  font-size: 12px;
}
.suggest-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
