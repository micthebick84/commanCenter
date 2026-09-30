import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, type Ref } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import DefaultLayout from '../layouts/default.vue'
import TasksIndex from '../pages/tasks/index.vue'
import QuestionSidebar from '../components/QuestionSidebar.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// SSR 하이드레이션 게이트 (2026-09-30, TODOS "전역 레이아웃 SSR 하이드레이션 불일치").
// 토큰은 localStorage에만 있어 SSR은 항상 비로그인으로 그리는데, 클라이언트는 미들웨어가 하이드레이션 전에 auth를
// 복원해 로그인 상태로 하이드레이션했다 → 로그인된 모든 페이지에서 `Hydration completed but contains mismatches`.
// 하이드레이션 전 렌더가 SSR(비로그인)과 같은지를 Quasar useHydration을 조작해 확인한다. vitest의 Quasar 클라이언트
// 빌드는 SSR 모드가 아니라 useHydration이 항상 true라 실제 하이드레이션 전 상태는 목으로 만든다.
const hydration = vi.hoisted(() => ({
  isHydrated: null as unknown as Ref<boolean>,
}))
vi.mock('quasar', async (importOriginal) => {
  const mod = await importOriginal<typeof import('quasar')>()
  const { ref } = await import('vue')
  hydration.isHydrated = ref(true)
  return { ...mod, useHydration: () => ({ isHydrated: hydration.isHydrated }) }
})

const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ path: '/tasks', params: {} }),
})

const NuxtLinkStub = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('a', slots.default?.()),
})
const QRouteTabStub = defineComponent({
  props: { label: String, to: String, icon: String },
  setup: (props) => () => h('div', { class: 'q-tab-stub' }, props.label),
})

function mountLayout() {
  return mount(DefaultLayout, {
    global: { stubs: { NuxtLink: NuxtLinkStub, QRouteTab: QRouteTabStub } },
    slots: { default: () => h('div', { 'data-test': 'page' }, 'page') },
  })
}

beforeEach(() => {
  // 스토어는 이미 복원된 상태(미들웨어가 하이드레이션 전에 restore) — 게이트가 없으면 이 값이 그대로 그려진다
  Object.assign(authStub, {
    isAuthenticated: true,
    isAdmin: true,
    me: { username: 'admin', email: null },
  })
  hydration.isHydrated.value = false
})
afterEach(async () => {
  hydration.isHydrated.value = true
  await setViewportWidth(1024)
  document.body.innerHTML = ''
})

describe('layouts/default — 하이드레이션 전에는 SSR(비로그인)과 같은 마크업', () => {
  it('좁은 화면: 사용자 블록·로그아웃·하단 내비와 그 여백이 없다가, 하이드레이션이 끝나면 나타난다', async () => {
    await setViewportWidth(390)
    const w = mountLayout()
    await flushPromises()
    expect(w.find('[data-test="toolbar-user"]').exists()).toBe(false)
    expect(w.find('.q-header .q-btn').exists()).toBe(false) // 로그아웃
    expect(w.find('[data-test="bottom-nav"]').exists()).toBe(false)
    // 푸터가 있으면 QLayout이 page-container에 padding-bottom을 준다 — SSR과 어긋났던 스타일
    expect(w.find('.q-page-container').attributes('style') ?? '').not.toContain(
      'padding-bottom',
    )

    hydration.isHydrated.value = true
    await flushPromises()
    expect(w.find('[data-test="toolbar-user"]').text()).toContain('ADMIN')
    expect(w.find('.q-header .q-btn').exists()).toBe(true)
    expect(
      w
        .find('[data-test="bottom-nav"]')
        .findAll('.q-tab-stub')
        .map((t) => t.text()),
    ).toEqual(['작업', '질문', '관리'])
    w.unmount()
  })

  it('데스크톱: 헤더 탭·사용자명은 하이드레이션이 끝난 뒤에 그린다', async () => {
    const w = mountLayout()
    await flushPromises()
    expect(w.findAll('.q-header .q-tab-stub')).toHaveLength(0)
    expect(w.find('[data-test="toolbar-user"]').exists()).toBe(false)

    hydration.isHydrated.value = true
    await flushPromises()
    expect(w.findAll('.q-header .q-tab-stub')).toHaveLength(5)
    expect(w.find('[data-test="toolbar-user"]').text()).toContain('admin')
    w.unmount()
  })
})

describe('관리자 전용 요소도 하이드레이션 뒤에 그린다', () => {
  it('작업 목록(모바일): 관리자 "전체 통계" 토글', async () => {
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.startsWith('/api/tasks')
          ? { content: [], totalElements: 0, totalPages: 0 }
          : null,
      ),
    )
    await setViewportWidth(390)
    const w = mount(
      defineComponent({
        setup: () => () =>
          h(
            QLayout,
            { view: 'hHh lpR fFf' },
            {
              default: () =>
                h(QPageContainer, {}, { default: () => h(TasksIndex) }),
            },
          ),
      }),
      { attachTo: document.body },
    )
    await flushPromises()
    expect(w.find('[data-test="summary-strip"]').exists()).toBe(true)
    expect(w.find('[data-test="stats-toggle"]').exists()).toBe(false)

    hydration.isHydrated.value = true
    await flushPromises()
    expect(w.find('[data-test="stats-toggle"]').exists()).toBe(true)
    w.unmount()
  })

  it('질문 사이드바(데스크톱은 SSR로 그려짐): 관리자 "전체 보기" 토글', async () => {
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(url === '/api/questions' ? [] : { limits: [] }),
    )
    const w = mount(QuestionSidebar, { props: { activeId: null } })
    await flushPromises()
    expect(w.find('[data-test="new-question"]').exists()).toBe(true)
    expect(w.find('[data-test="all-toggle"]').exists()).toBe(false)

    hydration.isHydrated.value = true
    await flushPromises()
    expect(w.find('[data-test="all-toggle"]').exists()).toBe(true)
    w.unmount()
  })
})
