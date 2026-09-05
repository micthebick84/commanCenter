import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsShell from '../pages/questions.vue'
import { useApiMock } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock, useRoute: () => ({ params: { id: '3' } }) })

// 자식 라우트 자리 — 렌더 함수 스텁(문자열 템플릿은 런타임 컴파일러가 없어 못 쓴다)
const NuxtPageStub = defineComponent({ setup: () => () => h('div', { 'data-test': 'child-page' }) })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, {
      default: () => h(QPageContainer, {}, { default: () => h(QuestionsShell) }),
    }),
})

const today = new Date().toISOString()

describe('pages/questions (셸) — 스펙 2026-09-05 §6', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/api/questions'
          ? [{
              id: 3, title: '인증 흐름 확인', githubRepo: 'a/b', githubBranch: 'main', repoAlias: null, requesterId: 'user1',
              status: '', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0,
              contextTokens: null, contextWindow: null, createdAt: today, updatedAt: today,
            }]
          : { limits: [] },
      ),
    )
  })

  it('사이드바 + 자식 페이지를 그리고, 라우트 id를 활성 행으로 넘기며, 선택/새 질문이 navigateTo를 부른다', async () => {
    const w = mount(PageWrapper, { global: { stubs: { NuxtPage: NuxtPageStub } } })
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
})
