<script setup lang="ts">
// 인터뷰 목업 새 탭 보기 — 말풍선 카드·목록의 '새 탭에서 보기'가 연다: /interviews/{sid}/mockups/{id}. 스펙 2026-11-03 인터뷰 목업 §6 캔버스 ②.
// 목업 HTML은 에이전트가 만든 생성물이라 프롬프트 인젝션을 담을 수 있고 로그인 토큰은 localStorage에 있다 → iframe sandbox는
// allow-scripts만 둔다(allow-same-origin을 더하면 목업이 앱 출처로 돈다). srcdoc은 mockupSrcdoc(CSP meta 포함)만 쓴다.
// 데이터는 마운트 뒤에만 오므로 SSR과 첫 렌더가 같다(윗줄만) — 템플릿에 인증 분기를 두지 않는다(designs/[id].vue 참고).
// 권한은 API가 본다(/interviews는 로그인만 요구).
import { mockupLabel, mockupSrcdoc, type MockupSummary } from '~/composables/interviewMockups'

definePageMeta({ layout: 'blank' })

interface MockupView {
  id: number
  taskId: number
  n: number
  version: number
  title: string
  html: string
  createdAt?: string
}

const route = useRoute()
const sid = String(route.params.sid)
const id = String(route.params.id)

const view = ref<MockupView | null>(null)
const list = ref<MockupSummary[]>([])
const loading = ref(true)
const loadError = ref('')

const title = computed(() => (view.value ? mockupLabel(view.value) : '인터뷰 목업'))
useHead({ title })

const sorted = computed(() => list.value.slice().sort((a, b) => a.n - b.n))
const index = computed(() => sorted.value.findIndex((m) => String(m.id) === id))
const prevId = computed(() => (index.value > 0 ? sorted.value[index.value - 1].id : null))
const nextId = computed(() =>
  index.value >= 0 && index.value < sorted.value.length - 1 ? sorted.value[index.value + 1].id : null,
)

/** createdAt의 24시간 HH:mm(브라우저 시간대). 읽지 못하면 빈 문자열. */
const clock = computed(() => {
  const d = view.value?.createdAt ? new Date(view.value.createdAt) : null
  if (!d || Number.isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})
const subtitle = computed(() => {
  if (!view.value) return ''
  const parts = [`작업 #${view.value.taskId} 인터뷰`]
  if (index.value >= 0) parts.push(`${sorted.value.length}개 중 ${index.value + 1}번째`)
  if (clock.value) parts.push(clock.value)
  return parts.join(' · ')
})
const backHref = computed(() => (view.value ? `/tasks/${view.value.taskId}` : '/'))

onMounted(async () => {
  try {
    const [one, all] = await Promise.all([
      useApi<MockupView>(`/api/interviews/${sid}/mockups/${id}`),
      useApi<MockupSummary[]>(`/api/interviews/${sid}/mockups`),
    ])
    list.value = Array.isArray(all) ? all : []
    view.value = one
  } catch {
    loadError.value = '목업을 불러오지 못했습니다'
  } finally {
    loading.value = false
  }
})

// 경로 파라미터가 바뀌므로 페이지가 다시 마운트된다. 기록은 쌓지 않는다.
function go(target: number | null) {
  if (target != null) navigateTo(`/interviews/${sid}/mockups/${target}`, { replace: true })
}
</script>

<template>
  <q-page class="mockup-view" data-test="mockup-view">
    <div class="mockup-bar">
      <q-btn
        flat
        dense
        no-caps
        icon="arrow_back"
        label="인터뷰로"
        type="a"
        :href="backHref"
        class="mockup-bar__back"
        data-test="mockup-back"
      />
      <div class="mockup-bar__heading">
        <div class="mockup-bar__title ellipsis" data-test="mockup-title">{{ title }}</div>
        <div class="mockup-bar__subtitle ellipsis" data-test="mockup-subtitle">{{ subtitle }}</div>
      </div>
      <q-btn
        flat
        dense
        no-caps
        icon="chevron_left"
        label="이전"
        :disable="prevId == null"
        data-test="mockup-prev"
        @click="go(prevId)"
      />
      <q-btn
        flat
        dense
        no-caps
        icon-right="chevron_right"
        label="다음"
        :disable="nextId == null"
        data-test="mockup-next"
        @click="go(nextId)"
      />
      <div class="mockup-bar__note" data-test="mockup-isolation-note">
        에이전트가 만든 화면입니다. 격리된 영역에서 보여 주며, 이 화면은 Symphony의 로그인 정보나 데이터에 접근할 수 없습니다.
      </div>
    </div>
    <!-- sandbox는 allow-scripts만 — 다른 토큰을 더하지 말 것(위 주석) -->
    <iframe
      v-if="view"
      :srcdoc="mockupSrcdoc(view.html)"
      sandbox="allow-scripts"
      :title="title"
      class="mockup-frame"
      data-test="mockup-frame"
    />
    <div v-else-if="loadError" class="mockup-notice" data-test="mockup-error">{{ loadError }}</div>
  </q-page>
</template>

<style scoped>
/* 페이지는 스크롤하지 않고 목업이 iframe 안에서 스크롤된다 — 윗줄 + 목업 = 창 높이 */
.mockup-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}
.mockup-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  box-sizing: border-box;
  flex: none;
  padding: 4px 8px 4px 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  background: #fff;
}
.mockup-bar__back {
  flex: none;
}
.mockup-bar__heading {
  flex: 1 1 200px;
  min-width: 0;
}
.mockup-bar__title {
  font-weight: 500;
}
.mockup-bar__subtitle {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.6);
}
.mockup-bar__note {
  flex: 1 0 100%;
  font-size: 11px;
  line-height: 1.3;
  color: rgba(0, 0, 0, 0.6);
}
.mockup-frame {
  display: block;
  width: 100%;
  flex: 1 1 auto;
  min-height: 0;
  border: 0;
  background: #fff;
}
.mockup-notice {
  padding: 64px 16px;
  text-align: center;
  color: rgba(0, 0, 0, 0.6);
}
</style>
