<script setup lang="ts">
// 작업 상세 — 인터뷰 중 참조 변경 메뉴 (작업 스펙 2026-10-24 §7.4). 질문 대화 화면(pages/questions/[id].vue)의 "✎ 참조 변경"과 같은 동작:
//  - 메뉴가 고치는 것은 서버 값이 아니라 "다음 답변에 쓸 초안"(v-model). 페이지가 인터뷰 세션마다 한 번 시드하고, 바뀌었을 때만 답변 바디에 싣는다.
//  - 메뉴 칩 = 초안의 구성(어느 레포·브랜치)에 서버가 아는 상태(커밋·받은 시각·사유)를 입힌 것 — 초안은 시드 때 상태로 굳으므로,
//    첫 답변이 참조를 고정한 뒤에도 메뉴에 "다음 답변 때 받음"이 남지 않게 한다. 서버에 없는 짝(새로 더한 참조)은 초안 그대로라 대기로 보인다.
//  - 추가 폼 후보에서는 대상 레포와 이미 고른 참조를 뺀다(useRefDraft — 서버도 같은 규칙으로 검증). 3개 상한은 칩 줄(RefRepoChips max)이 건다.
//  - 후보 카탈로그는 메뉴를 처음 열 때(@show) 한 번만 받는다(useRefCatalog) — 페이지를 여는 시점의 API 호출은 늘리지 않는다. 실패하면 알리고 다음에 열 때 다시.
//  - 메뉴를 닫으면(@hide) 추가 폼을 접는다.
// 버튼은 입력 대기가 아닐 때 잠근다(disable — 페이지가 패널의 상태로 정한다). 루트·메뉴 내용은 <div>(CLAUDE.md q-dialog 규칙과 같은 이유).
// defineProps 타입은 상대 경로로 가져온다 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import { useQuasar } from 'quasar'
import RefRepoChips from '~/components/RefRepoChips.vue'
import RefRepoAddForm from '~/components/RefRepoAddForm.vue'
import { useRefCatalog, useRefDraft, type RefRepoView } from '../../composables/refRepos'

const props = withDefaults(
  defineProps<{
    modelValue: RefRepoView[]
    serverRefs: RefRepoView[]
    targetCatalogId: number | null
    disable?: boolean
  }>(),
  { disable: false },
)
const emit = defineEmits<{
  'update:modelValue': [refs: RefRepoView[]]
}>()

const $q = useQuasar()

// 초안은 부모 소유 — useRefDraft는 늘 새 배열을 대입하므로 props 배열을 직접 고치지 않고 update:modelValue로 올라간다
const draft = computed<RefRepoView[]>({
  get: () => props.modelValue,
  set: (refs) => emit('update:modelValue', refs),
})
const { addOpen, excludeIds, toggleAdd, add, remove } = useRefDraft(draft, () => props.targetCatalogId)
const { catalog, loadOnce } = useRefCatalog((message) => $q.notify({ type: 'negative', message }))

// 메뉴에 그릴 칩 — 초안의 짝마다 서버가 아는 상태를 입힌다(표시뿐, 초안은 그대로)
const draftChips = computed<RefRepoView[]>(() =>
  props.modelValue.map((p) => props.serverRefs.find((s) => s.catalogId === p.catalogId && s.branch === p.branch) ?? p),
)
</script>

<template>
  <div class="task-ref-change">
    <q-btn
      flat
      dense
      no-caps
      size="sm"
      color="deep-purple-7"
      label="✎ 참조 변경"
      aria-label="참조 변경"
      class="task-ref-change-btn"
      :disable="props.disable"
      data-test="ref-change"
    >
      <q-tooltip>인터뷰 중 변경 — 다음 답변부터 적용</q-tooltip>
      <q-menu @show="loadOnce" @hide="addOpen = false">
        <div class="ref-menu">
          <div class="ref-menu-chips">
            <RefRepoChips :items="draftChips" editable context="task-interview" @remove="remove" @add="toggleAdd" />
          </div>
          <RefRepoAddForm v-if="addOpen" :catalog="catalog" :exclude-ids="excludeIds" @add="add" />
        </div>
      </q-menu>
    </q-btn>
  </div>
</template>

<style scoped>
/* 버튼은 줄어들지 않는다 — 좁은 화면에서 줄어드는 쪽은 옆의 칩 줄뿐이고, 라벨이 세로로 꺾이지 않는다 */
.task-ref-change {
  flex: none;
}
.task-ref-change-btn {
  white-space: nowrap;
}
/* 메뉴 폭 — 추가 폼 루트(RefRepoAddForm)와 같은 값. 390px 화면에서도 좌우 16px 여백 안에 든다 */
.ref-menu {
  box-sizing: border-box;
  width: min(340px, calc(100vw - 32px));
}
.ref-menu-chips {
  padding: 12px 16px 4px;
}
</style>
