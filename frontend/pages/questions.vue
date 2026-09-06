<script setup lang="ts">
// 질문 탭 셸 (스펙 2026-09-05 §3·§6): 데스크톱은 좌측 사이드바 고정, 좁은 화면(lt.md)은 좌측 서랍(q-dialog).
// QDrawer는 QLayout 직속이어야 해 페이지에서 못 쓴다 — q-dialog position="left"로 대체.
import { useQuasar } from 'quasar'
import QuestionSidebar from '~/components/QuestionSidebar.vue'

definePageMeta({ layout: 'default' })

const route = useRoute()
const $q = useQuasar()
const activeId = computed(() => {
  const id = Number(route.params.id)
  return Number.isFinite(id) && id > 0 ? id : null
})
const drawer = ref(false)
const sidebar = ref<InstanceType<typeof QuestionSidebar> | null>(null)
const wide = computed(() => !!$q.screen?.gt?.sm)

function select(id: number) {
  drawer.value = false
  navigateTo(`/questions/${id}`)
}
function startNew() {
  drawer.value = false
  navigateTo('/questions')
}

// 자식 페이지(새 질문 등록 직후 등)가 목록을 즉시 갱신하고 서랍을 열 수 있게 제공
provide('questions:refresh', () => sidebar.value?.refresh())
provide('questions:open-drawer', () => {
  drawer.value = true
})
</script>

<template>
  <q-page class="questions-shell row no-wrap">
    <QuestionSidebar v-if="wide" ref="sidebar" :active-id="activeId" @select="select" @new="startNew" />
    <q-dialog v-else v-model="drawer" position="left">
      <!-- QDialog는 `.q-dialog__inner > div`에만 pointer-events:all을 준다 — 사이드바(aside)를 div로 감싸야
           서랍 안 탭(새 질문·세션 행·전체 보기)이 백드롭으로 새어 서랍만 닫히지 않는다 (2026-09-06 모바일 QA). -->
      <div class="drawer-sidebar">
        <QuestionSidebar ref="sidebar" :active-id="activeId" @select="select" @new="startNew" />
      </div>
    </q-dialog>
    <div class="col column no-wrap questions-main">
      <NuxtPage />
    </div>
  </q-page>
</template>

<style scoped>
.questions-shell {
  height: calc(100vh - 50px);
  overflow: hidden;
}
.questions-main {
  min-width: 0;
  min-height: 0;
}
.drawer-sidebar {
  height: 100vh;
}
/* Quasar가 minimized 다이얼로그 콘텐츠에 거는 max-height: calc(100vh - 48px)를 풀어 서랍이 화면 높이를 다 쓰게 한다 */
.q-dialog__inner--minimized > .drawer-sidebar {
  max-height: 100vh;
}
</style>
