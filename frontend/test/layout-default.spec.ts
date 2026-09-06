import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import DefaultLayout from '../layouts/default.vue'
import { authStub } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// useRoute는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입(관리 탭 활성 판정용).
Object.assign(globalThis, { useRoute: () => ({ path: '/tasks' }) })

// Nuxt 전용 컴포넌트/라우터 의존 탭은 렌더 함수 스텁으로 대체 (문자열 템플릿은 런타임 컴파일러가 없어 못 쓴다).
const NuxtLinkStub = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('a', { 'data-test': 'brand-link' }, slots.default?.()),
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

describe('layouts/default — 상단 툴바 반응형 (2026-09-06 모바일 QA)', () => {
  beforeEach(() => {
    // 레이아웃이 읽는 스토어 게터/필드 — authStub은 accessToken/isAdmin만 있어 여기서 보강.
    Object.assign(authStub, {
      isAuthenticated: true,
      isAdmin: true,
      me: { username: 'admin', email: null },
    })
  })

  it('넓은 화면에서는 사용자명과 ADMIN 칩을 함께 보여준다', async () => {
    const w = mountLayout()
    await flushPromises()
    const user = w.find('[data-test="toolbar-user"]')
    expect(user.text()).toContain('admin')
    expect(user.text()).toContain('ADMIN')
    expect(w.find('.q-toolbar__title').classes()).not.toContain('toolbar-brand--fixed') // 데스크톱 배치 불변
    expect(w.find('[data-test="page"]').exists()).toBe(true)
    w.unmount()
  })

  it('좁은 화면(lt.md)에서는 브랜드가 줄어들지 않고(toolbar-brand--fixed) 사용자명은 숨기되 ADMIN 칩은 남긴다', async () => {
    // 390px에서 브랜드가 "n"으로 잘리고 사용자 블록이 두 줄로 꺾이던 결함.
    await setViewportWidth(390)
    try {
      const w = mountLayout()
      await flushPromises()
      // Quasar `col-shrink`는 flex: 0 1 auto라 여전히 줄어든다 — flex-shrink: 0을 주는 자체 클래스가 있어야 브랜드가 "net…"으로 잘리지 않는다.
      expect(w.find('.q-toolbar__title').classes()).toContain('toolbar-brand--fixed')
      const user = w.find('[data-test="toolbar-user"]')
      expect(user.exists()).toBe(true)
      expect(user.text()).not.toContain('admin')
      expect(user.text()).toContain('ADMIN')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})

describe('layouts/default — 하단 내비 (스펙 2026-09-06 §4.4, 결정 4)', () => {
  beforeEach(() => {
    Object.assign(authStub, { isAuthenticated: true, isAdmin: true, me: { username: 'admin', email: null } })
  })

  it('lt.md: 헤더 탭은 사라지고 하단 내비에 작업·질문·관리 3탭', async () => {
    await setViewportWidth(390)
    try {
      const w = mountLayout()
      await flushPromises()
      expect(w.find('.q-header .q-tab-stub').exists()).toBe(false)
      const nav = w.find('[data-test="bottom-nav"]')
      expect(nav.exists()).toBe(true)
      expect(nav.findAll('.q-tab-stub').map((t) => t.text())).toEqual(['작업', '질문', '관리'])
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('lt.md + 일반 사용자: 관리 탭 없음', async () => {
    authStub.isAdmin = false
    await setViewportWidth(390)
    try {
      const w = mountLayout()
      await flushPromises()
      expect(w.find('[data-test="bottom-nav"]').findAll('.q-tab-stub').map((t) => t.text())).toEqual(['작업', '질문'])
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('데스크톱: 헤더 탭 5개, 하단 내비 없음', async () => {
    const w = mountLayout()
    await flushPromises()
    expect(w.findAll('.q-header .q-tab-stub')).toHaveLength(5)
    expect(w.find('[data-test="bottom-nav"]').exists()).toBe(false)
    w.unmount()
  })
})
