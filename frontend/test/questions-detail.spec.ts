import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionDetail from '../pages/questions/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(QuestionDetail) }),
      })
  },
})

const detail = {
  id: 3, title: '인증 흐름', githubRepo: 'a/b', githubBranch: 'main', status: '입력대기',
  statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5', effort: 'medium', kind: 'QUESTION',
  totalCostUsd: 0.42, turns: [{ seq: 1, role: 'assistant', kind: 'question', content: 'AuthController입니다' }],
  plan: null,
}

describe('pages/questions/[id] — 상세 (스펙 2026-08-30 §7)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3' ? Promise.resolve(detail) : Promise.resolve(null),
    )
  })

  it('헤더(제목·레포·비용)를 그리고 InterviewPanel을 kind=QUESTION으로 마운트한다', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    expect(w.text()).toContain('인증 흐름')
    expect(w.text()).toContain('a/b · main')
    expect(w.text()).toContain('0.42')
    const panel = w.findComponent({ name: 'InterviewPanel' })
    expect(panel.exists()).toBe(true)
    expect(panel.props('kind')).toBe('QUESTION')
    expect(panel.props('sessionId')).toBe(3)
    expect(w.text()).toContain('AuthController입니다')
    w.unmount()
  })

  it('조회 실패(403/404)면 목록으로 돌려보낸다', async () => {
    useApiMock.mockImplementation(() => Promise.reject({ statusCode: 403, data: { message: '권한이 없습니다' } }))
    const w = mount(PageWrapper)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })
})
