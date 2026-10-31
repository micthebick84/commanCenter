<script setup lang="ts">
// 디자인 목업 크게 보기 — 상세 카드의 '크게 보기'가 새 탭으로 연다: /designs/{id}?screen=N(N은 1부터). 스펙 2026-10-31 §4-5.
// 목업 HTML은 워커가 만든 생성물이라 프롬프트 인젝션을 담을 수 있고 로그인 토큰은 localStorage에 있다 → iframe sandbox는
// allow-scripts만 둔다(allow-same-origin을 더하면 목업이 앱 출처로 돈다). 같은 이유로 blob URL·window.open+document.write 금지.
// 데이터는 마운트 뒤에만 오므로 SSR과 첫 렌더가 같다(윗줄만) — 템플릿에 인증·관리자 분기를 두지 않는다(CLAUDE.md "SSR 하이드레이션").
// 자동 갱신 없음: 반려·재생성 뒤에는 새로고침으로 새 목업을 본다(승인·반려는 원래 탭의 카드에서).
definePageMeta({ layout: 'blank' })

interface MockupFile {
  path: string
  title: string
  html: string
}

interface TaskDesignView {
  id: number
  title: string
  design: { mockupFilesJson: string } | null
}

const route = useRoute()
const id = String(route.params.id)
useHead({ title: `#${id} 디자인 — Symphony` })

const task = ref<TaskDesignView | null>(null)
const loading = ref(true)
const loadError = ref('')
const selected = ref(0)

// 카드(DesignReviewCard)와 같은 규칙 — 읽지 못하면 목업 없음
const mockups = computed<MockupFile[]>(() => {
  const json = task.value?.design?.mockupFilesJson
  if (!json) return []
  try {
    const parsed = JSON.parse(json)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})
const current = computed<MockupFile | null>(() => mockups.value[selected.value] ?? null)
const heading = computed(() => `#${id}${task.value?.title ? ` ${task.value.title}` : ''} · 디자인`)

/** ?screen=N이 1..count 정수면 N-1, 없거나 범위를 벗어나면 첫 화면. */
function screenIndex(raw: unknown, count: number): number {
  const n = Number(raw)
  return Number.isInteger(n) && n >= 1 && n <= count ? n - 1 : 0
}

onMounted(async () => {
  try {
    task.value = await useApi<TaskDesignView>(`/api/tasks/${id}`)
    selected.value = screenIndex(route.query.screen, mockups.value.length)
  } catch (e) {
    loadError.value = (e as { data?: { message?: string } } | null)?.data?.message ?? '작업을 불러오지 못했습니다'
  } finally {
    loading.value = false
  }
})

// 새로고침해도 같은 화면이 열리게 쿼리를 바꾼다(기록은 쌓지 않음)
function selectScreen(i: number) {
  selected.value = i
  navigateTo({ query: { screen: String(i + 1) } }, { replace: true })
}
</script>

<template>
  <q-page class="design-view" data-test="design-view">
    <div class="design-bar">
      <div class="design-bar__heading ellipsis">{{ heading }}</div>
      <q-tabs
        v-if="mockups.length"
        :model-value="selected"
        dense
        no-caps
        align="left"
        class="design-bar__tabs"
        @update:model-value="selectScreen"
      >
        <q-tab v-for="(m, i) in mockups" :key="m.path" :name="i" :label="m.title" />
      </q-tabs>
      <q-space />
      <q-btn
        flat
        dense
        no-caps
        icon="arrow_back"
        label="작업으로 돌아가기"
        type="a"
        :href="`/tasks/${id}`"
        class="design-bar__back"
        data-test="design-back"
      />
    </div>
    <!-- sandbox는 allow-scripts만 — 다른 토큰을 더하지 말 것(위 주석) -->
    <iframe
      v-if="current"
      :srcdoc="current.html"
      sandbox="allow-scripts"
      :title="current.title"
      class="design-frame"
      data-test="design-frame"
    />
    <div v-else-if="loadError" class="design-notice" data-test="design-error">{{ loadError }}</div>
    <div v-else-if="!loading" class="design-notice" data-test="design-empty">이 작업에는 아직 디자인 목업이 없습니다</div>
  </q-page>
</template>

<style scoped>
/* 페이지는 스크롤하지 않고 목업이 iframe 안에서 스크롤된다 — 윗줄 48px + 목업 = 창 높이 */
.design-view {
  height: 100vh;
  overflow: hidden;
}
.design-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  padding: 0 8px 0 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  background: #fff;
}
.design-bar__heading {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 40%;
  font-weight: 500;
}
.design-bar__tabs {
  flex: 0 1 auto;
  min-width: 0;
}
.design-bar__back {
  flex: none;
}
.design-frame {
  display: block;
  width: 100%;
  height: calc(100vh - 48px);
  border: 0;
  background: #fff;
}
.design-notice {
  padding: 64px 16px;
  text-align: center;
  color: rgba(0, 0, 0, 0.6);
}
</style>
