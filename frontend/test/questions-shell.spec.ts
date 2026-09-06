import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, inject, nextTick } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsShell from '../pages/questions.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 자식 라우트 자리 — 렌더 함수 스텁(문자열 템플릿은 런타임 컴파일러가 없어 못 쓴다)
const NuxtPageStub = defineComponent({
  setup: () => () => h('div', { 'data-test': 'child-page' }),
})

const PageWrapper = defineComponent({
  setup: () => () =>
    h(
      QLayout,
      { view: 'hHh lpR fFf' },
      {
        default: () =>
          h(QPageContainer, {}, { default: () => h(QuestionsShell) }),
      },
    ),
})

const today = new Date().toISOString()

describe('pages/questions (셸) — 스펙 2026-09-05 §6', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/api/questions'
          ? [
              {
                id: 3,
                title: '인증 흐름 확인',
                githubRepo: 'a/b',
                githubBranch: 'main',
                repoAlias: null,
                requesterId: 'user1',
                status: '',
                statusName: 'AWAITING_INPUT',
                model: 'claude-opus-5',
                effort: 'high',
                totalCostUsd: 0,
                contextTokens: null,
                contextWindow: null,
                createdAt: today,
                updatedAt: today,
              },
            ]
          : { limits: [] },
      ),
    )
  })

  it('사이드바 + 자식 페이지를 그리고, 라우트 id를 활성 행으로 넘기며, 선택/새 질문이 navigateTo를 부른다', async () => {
    const w = mount(PageWrapper, {
      global: { stubs: { NuxtPage: NuxtPageStub } },
    })
    await flushPromises()
    expect(w.find('[data-test="child-page"]').exists()).toBe(true)
    const row = w.find('[data-test="question-row"]')
    expect(row.classes()).toContain('active')
    await row.trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions/3')
    await w.find('[data-test="new-question"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })

  // 서랍 안 탭이 먹지 않던 결함(2026-09-06 모바일 QA): Quasar CSS는 `.q-dialog__inner { pointer-events:none }`에
  // `.q-dialog__inner > div { pointer-events:all }` 예외만 둔다 — 콘텐츠 루트가 <aside>면 서랍 안 클릭이 전부
  // 백드롭으로 새어 서랍만 닫힌다. vitest는 Quasar CSS를 로드하지 않으므로 DOM 구조(콘텐츠 루트 태그)로 가드한다.
  const NuxtPageStubWithOpener = defineComponent({
    setup() {
      const openDrawer = inject<() => void>('questions:open-drawer', () => {})
      return () =>
        h('button', {
          'data-test': 'open-drawer-stub',
          onClick: () => openDrawer(),
        })
    },
  })

  it('좁은 화면(lt.md)에서는 고정 사이드바 대신 서랍을 쓰고, 서랍 콘텐츠 루트는 div라야 한다(Quasar pointer-events 예외 규칙)', async () => {
    await setViewportWidth(390)
    try {
      const w = mount(PageWrapper, {
        global: { stubs: { NuxtPage: NuxtPageStubWithOpener } },
      })
      await flushPromises()
      expect(w.find('.question-sidebar').exists()).toBe(false) // 고정 사이드바 없음 → 서랍 모드
      await w.find('[data-test="open-drawer-stub"]').trigger('click')
      await flushPromises()
      await nextTick()
      const inner = document.body.querySelector('.q-dialog__inner')
      expect(inner).not.toBeNull()
      const contentRoot = inner!.firstElementChild
      expect(contentRoot?.tagName).toBe('DIV')
      expect(contentRoot?.querySelector('.question-sidebar')).not.toBeNull()
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})
