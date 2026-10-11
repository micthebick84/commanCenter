import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import LoginPage from '../pages/login.vue'
import { authStub } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
Object.assign(globalThis, {
  useRoute: () => ({ path: '/login', query: {} }),
  navigateTo: vi.fn(),
})

// q-page는 q-layout 안에서만 마운트된다.
const Host = defineComponent({
  setup: () => () =>
    h(QLayout, null, { default: () => h(QPageContainer, null, { default: () => h(LoginPage) }) }),
})

describe('pages/login — 브랜드 락업 (2026-10-11 디자인 승인: A4 · Symphony · 세로)', () => {
  beforeEach(() => {
    Object.assign(authStub, { isAuthenticated: false, restore: vi.fn(), loginRedirect: vi.fn() })
  })

  it('카드 머리에 마크(44px)가 위, "Symphony"가 아래로 쌓인다', async () => {
    const w = mount(Host)
    await flushPromises()
    const brand = w.find('[data-test="login-brand"]')
    expect(brand.exists()).toBe(true)
    expect(brand.classes()).toContain('column') // 세로 락업
    const mark = brand.find('[data-test="brand-mark"]')
    expect(mark.attributes('width')).toBe('44')
    expect(brand.element.firstElementChild).toBe(mark.element) // 마크가 먼저
    expect(brand.find('h1').text()).toBe('Symphony')
    expect(w.text()).toContain('Netis 플랫폼 작업 분석 자동화 도구')
    expect(w.text()).not.toContain('netisMaker')
    w.unmount()
  })
})
