import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import LoginPage from '../pages/login.vue'
import { authStub } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — navigateTo는 setup.ts에 없어 전역 주입, useRoute는 setup.ts 기본값을 덮는다.
Object.assign(globalThis, {
  useRoute: () => ({ path: '/login', query: {} }),
  navigateTo: vi.fn(),
})

// q-page는 q-layout 안에서만 마운트된다 (실제로는 layouts/blank.vue가 이 역할).
const Host = defineComponent({
  setup: () => () =>
    h(QLayout, null, { default: () => h(QPageContainer, null, { default: () => h(LoginPage) }) }),
})

describe('pages/login — 2026-10-11 디자인 승인 C안: 전용 화면 + 브랜드 배경', () => {
  let loginRedirect: ReturnType<typeof vi.fn>
  beforeEach(() => {
    loginRedirect = vi.fn()
    Object.assign(authStub, { isAuthenticated: false, restore: vi.fn(), loginRedirect })
  })

  it('카드 머리는 56px primary 마크 위 + "Symphony" 아래 세로 락업이고, 제품 설명 문구가 없다', async () => {
    const w = mount(Host)
    await flushPromises()
    const brand = w.find('[data-test="login-brand"]')
    expect(brand.exists()).toBe(true)
    expect(brand.classes()).toContain('column') // 세로 락업
    const mark = brand.find('[data-test="brand-mark"]')
    expect(mark.attributes('width')).toBe('56')
    expect(mark.classes()).toContain('text-primary')
    expect(brand.element.firstElementChild).toBe(mark.element) // 마크가 먼저
    expect(brand.find('h1').text()).toBe('Symphony')
    expect(w.text()).not.toContain('자동화 도구') // "Netis 플랫폼 작업 분석 자동화 도구" 제거 (요청)
    expect(w.text()).not.toContain('netisMaker')
    w.unmount()
  })

  it('버튼은 아이콘 없는 "Login" 하나, 대문자 변환 없음, 클릭하면 netis-auth로 리다이렉트한다', async () => {
    const w = mount(Host)
    await flushPromises()
    const btn = w.find('[data-test="login-btn"]')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toBe('Login')
    expect(btn.classes()).toContain('q-btn--no-uppercase') // Quasar no-caps → "Login" 그대로
    expect(btn.find('.q-icon').exists()).toBe(false)
    expect(w.findAll('button')).toHaveLength(1)
    await btn.trigger('click')
    expect(loginRedirect).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('브랜드 배경: 워터마크 마크(640px)는 장식(aria-hidden)이고 카드가 그 위에 놓인다', async () => {
    const w = mount(Host)
    await flushPromises()
    const page = w.find('[data-test="login-page"]')
    expect(page.classes()).toContain('login-page')
    const watermark = page.find('[data-test="login-watermark"]')
    expect(watermark.attributes('aria-hidden')).toBe('true')
    expect(watermark.find('[data-test="brand-mark"]').attributes('width')).toBe('640')
    const card = page.find('[data-test="login-card"]')
    expect(card.exists()).toBe(true)
    // 워터마크가 먼저(아래), 카드가 나중(위)
    expect(watermark.element.compareDocumentPosition(card.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    w.unmount()
  })

  it('로그인 페이지는 툴바 없는 blank 레이아웃을 쓴다 (소스 가드)', () => {
    const root = resolve(__dirname, '..')
    const page = readFileSync(resolve(root, 'pages/login.vue'), 'utf8')
    expect(page).toMatch(/definePageMeta\(\{\s*layout:\s*'blank'\s*\}\)/)
    const blank = readFileSync(resolve(root, 'layouts/blank.vue'), 'utf8')
    expect(blank).toContain('<q-page-container>')
    expect(blank).not.toContain('q-header')
    expect(blank).not.toContain('BrandMark')
  })
})
