<script setup lang="ts">
// 참조 repo 칩 줄 (스펙 2026-10-22 §7.1) — 새 질문 화면(편집)과 대화 화면(읽기 전용 표시 + 변경 창)이 같이 쓴다.
// 라벨 "참조 repo · 읽기 전용 · 최대 N개" + 칩 `📚 별칭 · 브랜치[@커밋7자]`. 상태는 세 가지(composables/refRepos.ts refState):
//  - 커밋 고정(pinned): 칩에 `@a1b2c3d`, 툴팁 "고정 커밋 a1b2c3d · 받은 시각 — 대화 끝까지 이 커밋 기준"
//  - 아직 안 받음(pending): 칩 안에 "다음 답변 때 받음" (새로 더한 참조는 다음 답변 때 처음 받는다)
//  - 실패(failed): ⚠, 툴팁에 사유 + "이 참조 없이 답했습니다 — …" (세션은 계속되고 그 참조만 읽을 수 없다)
// 위 문구는 질문 맥락(context 기본값)이다. 작업 화면은 context(task-setup·task-interview·task-fixed)를 넘겨 맥락 문구로 바꾼다
// (작업 스펙 2026-10-24 §7.1, refChipCopy) — 문구가 null인 맥락에서는 대기 안내·실패 툴팁 마지막 줄을 그리지 않는다.
// editable일 때만 칩마다 ✕와 "＋ 참조 추가" 칩이 보이고, 개수가 max에 닿으면 추가 칩은 사라진다.
// 제어 컴포넌트다 — ✕·＋는 remove/add만 올리고 목록은 부모가 바꾼다(✕를 눌러도 부모가 items에서 빼기 전에는 칩이 남는다).
// 색은 DB 칩(teal)·첨부 칩(blue-grey)과 구별되는 deep-purple. 눌러야 하는 편집 모드는 일반 크기 칩,
// 읽기 전용은 대화 헤더 아래 DB 칩 줄과 같은 dense/sm. (클래스 이름에 Quasar 반응형 헬퍼 이름을 쓰지 않는다 — CLAUDE.md)
// 타입은 상대 경로로 가져온다 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import {
  MAX_REF_REPOS,
  refChipCopy,
  refChipLabel,
  refShortSha,
  refState,
  type RefChipContext,
  type RefRepoView,
} from '../composables/refRepos'

const props = withDefaults(
  defineProps<{
    items: RefRepoView[]
    editable?: boolean
    max?: number
    context?: RefChipContext
  }>(),
  { editable: false, max: MAX_REF_REPOS, context: 'question' },
)
const emit = defineEmits<{
  remove: [catalogId: number]
  add: []
}>()

const copy = computed(() => refChipCopy(props.context))

/** 받은 시각 — 없거나 읽을 수 없으면 빈 문자열(툴팁에서 그 자리를 비운다). */
function fetchedLabel(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString()
}

/** 고정 칩 툴팁: "고정 커밋 a1b2c3d · 받은 시각 — {맥락의 기준 문구}" (받은 시각이 없으면 그 부분만 뺀다). */
function pinnedTip(r: RefRepoView): string {
  const when = fetchedLabel(r.fetchedAt)
  return `고정 커밋 ${refShortSha(r.sha ?? '')}${when ? ` · ${when}` : ''} — ${copy.value.pinnedBasis}`
}

const rows = computed(() =>
  props.items.map((r) => {
    const state = refState(r)
    return {
      r,
      state,
      label: refChipLabel(r),
      // 아직 안 받은 칩은 비워 둔 윤곽선, 받았거나 실패한 칩은 옅은 보라 바탕
      look:
        state === 'pending'
          ? { outline: true, color: 'deep-purple-7' }
          : { color: 'deep-purple-1', textColor: 'deep-purple-10' },
    }
  }),
)

const canAdd = computed(() => props.editable && props.items.length < props.max)
</script>

<template>
  <div class="ref-repo-chips" data-test="ref-repo-chips">
    <div class="ref-label">참조 repo · 읽기 전용 · 최대 {{ props.max }}개</div>
    <div class="row items-center ref-row">
      <q-chip
        v-for="row in rows"
        :key="row.r.catalogId"
        v-bind="row.look"
        :dense="!props.editable"
        :size="props.editable ? undefined : 'sm'"
        :removable="props.editable"
        :remove-aria-label="`${row.r.alias} 참조 해제`"
        class="ref-chip"
        data-test="ref-chip"
        :data-state="row.state"
        @remove="emit('remove', row.r.catalogId)"
      >
        <span v-if="row.state === 'failed'" class="ref-warn text-negative" role="img" aria-label="참조 받기 실패">⚠</span>
        <span class="ref-chip-label">{{ row.label }}</span>
        <span v-if="row.state === 'pending' && copy.pendingNote" class="ref-chip-note">{{ copy.pendingNote }}</span>
        <q-tooltip v-if="row.state === 'pinned'" max-width="360px">
          <div class="ref-tip">{{ pinnedTip(row.r) }}</div>
        </q-tooltip>
        <q-tooltip v-else-if="row.state === 'failed'" max-width="360px">
          <div class="ref-tip">
            <div class="text-weight-medium">참조 받기 실패</div>
            <div>{{ row.r.error }}</div>
            <div v-if="copy.failedNote" class="q-mt-xs">{{ copy.failedNote }}</div>
          </div>
        </q-tooltip>
      </q-chip>
      <span v-if="rows.length === 0" class="text-caption text-grey-7 ref-empty" data-test="ref-empty">참조 repo 없음</span>
      <q-chip v-if="canAdd" clickable outline color="deep-purple-7" label="＋ 참조 추가" data-test="ref-add" @click="emit('add')" />
    </div>
  </div>
</template>

<style scoped>
.ref-label {
  font-size: 12px;
  font-weight: 600;
  color: #616161;
  padding-bottom: 2px;
}
.ref-chip {
  max-width: 100%;
}
/* 좁은 화면에서 긴 별칭·브랜치가 칩을 줄 밖으로 밀어내지 않게 — 라벨만 말줄임 */
.ref-chip-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ref-chip-note {
  flex: none;
  margin-left: 6px;
  font-size: 0.85em;
  opacity: 0.75;
}
.ref-warn {
  flex: none;
  margin-right: 4px;
}
.ref-empty {
  margin: 0 8px 0 4px;
}
/* 한글은 음절마다 줄이 꺾이므로 단어 단위로 유지하고(keep-all), 공백 없는 긴 문자열(git 오류의 경로·URL)만 폭에서 꺾는다 */
.ref-tip {
  word-break: keep-all;
  overflow-wrap: anywhere;
  white-space: normal;
}
</style>
