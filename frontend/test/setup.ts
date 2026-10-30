import { config } from '@vue/test-utils'
import * as QuasarPkg from 'quasar'
import { Quasar, Notify, Dialog, Loading, ClosePopup, Ripple } from 'quasar'
import { vi, beforeEach } from 'vitest'
import * as vue from 'vue'
import { FakeEventSource } from './mocks/eventsource'
import {
  useApiMock,
  useAuthStoreMock,
  useRuntimeConfigMock,
  useCookieMock,
  resetNuxtMocks,
} from './mocks/nuxt'

// Minimal stub for useTaskPolling (composable, not a Nuxt auto-import).
// Returns a data ref that stays null and a no-op refresh — tests that need real
// polling call the underlying useApi mock directly.
function useTaskPollingStub<T>(fetcher: () => Promise<T>) {
  const data = vue.ref<T | null>(null)
  const loading = vue.ref(false)
  const error = vue.ref<unknown>(null)
  vue.onMounted(async () => {
    try {
      data.value = await fetcher()
    } catch (e) {
      error.value = e
    }
  })
  return { data, loading, error, refresh: () => fetcher().then((v) => { data.value = v }).catch(() => {}) }
}

// Install fake EventSource globally so the composable's `new EventSource(...)` resolves to it.
;(globalThis as any).EventSource = FakeEventSource

// Nuxt auto-imports are compiler magic at runtime; under Vitest we expose them as globals.
Object.assign(globalThis as any, {
  ref: vue.ref,
  computed: vue.computed,
  reactive: vue.reactive,
  watch: vue.watch,
  onMounted: vue.onMounted,
  onUnmounted: vue.onUnmounted,
  nextTick: vue.nextTick,
  provide: vue.provide,
  inject: vue.inject,
  useApi: useApiMock,
  useAuthStore: useAuthStoreMock,
  useRuntimeConfig: useRuntimeConfigMock,
  useCookie: useCookieMock,
  // Nuxt page-only macro — no-op in Vitest (pages are tested without the router layer)
  definePageMeta: vi.fn(),
  // 라우트 기본값 — 경로·파라미터·쿼리가 빈 홈. 작업 등록 화면(pages/tasks/index.vue)이 질문에서 넘어온 쿼리를 읽게 되는데
  // (스펙 2026-10-24 §7.2) 그 화면을 마운트하는 여러 spec이 useRoute를 넣지 않는다. 특정 라우트가 필요한 spec은
  // 모듈 단계에서 자기 useRoute를 넣어 이 값을 덮는다(setup 파일이 spec 모듈보다 먼저 돈다).
  useRoute: () => ({ path: '/', params: {}, query: {} }),
  // useTaskPolling is a local composable (not a Nuxt auto-import) but pages/tasks/index.vue
  // calls it at setup-time. Provide a test-safe stub that still calls the fetcher on mount.
  useTaskPolling: useTaskPollingStub,
})

// Register every Quasar component (QInput, QBtn, ...) globally so they resolve to real
// DOM nodes in mounts — the Quasar Vue plugin alone does not auto-register components.
const quasarComponents: Record<string, any> = Object.fromEntries(
  Object.entries(QuasarPkg as Record<string, unknown>).filter(
    ([name, val]) => /^Q[A-Z]/.test(name) && val != null && typeof val === 'object',
  ),
)

// Quasar components (q-input, q-btn, ...) available in mounts, plus the same plugins
// nuxt.config.ts registers (Notify/Dialog/Loading) so $q.notify(...) is callable.
config.global.plugins = [
  [
    Quasar,
    {
      components: quasarComponents,
      plugins: { Notify, Dialog, Loading },
      directives: { ClosePopup, Ripple },
    },
  ],
]
config.global.components = quasarComponents
// NuxtLink는 Nuxt 자동 임포트 컴포넌트라 vitest에서 해석되지 않는다 — 기본 스텁(주소는 href로)을 둬 페이지를 마운트하는 spec마다
// "Failed to resolve component: NuxtLink" 경고가 나지 않게 한다(작업 상세의 출처 질문 링크, 스펙 2026-10-24 §7.4). 자기 스텁을 주는 spec은 그것이 우선한다.
// 기본 스텁(transition·transition-group)은 그대로 두고 키만 더한다 — 통째로 바꾸면 트랜지션이 실제로 돌아 다른 spec의 문구 검사가 깨진다.
config.global.stubs = { ...config.global.stubs, NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } }

beforeEach(() => {
  resetNuxtMocks()
  FakeEventSource.reset()
})
