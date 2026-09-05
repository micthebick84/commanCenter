import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import QuestionDetail from '../pages/questions/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 헤더 폴링(GET /api/questions/3)과 패널 스냅샷(같은 URL)이 모두 이 응답을 쓴다.
const detail = {
  id: 3, title: '인증 흐름 확인', githubRepo: 'micthebick84/netis7.0', githubBranch: 'main', status: '입력대기',
  statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5', effort: 'medium', kind: 'QUESTION',
  totalCostUsd: 0.42, contextTokens: 76004, contextWindow: 200000,
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '**AuthController**입니다' }],
  plan: null,
}

describe('pages/questions/[id] — 대화 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve(detail)
        : url === '/api/usage/claude'
          ? Promise.resolve({ limits: [] })
          : Promise.resolve(null),
    )
  })

  it('헤더(제목·상태·레포·비용·컨텍스트)와 채팅 입력창을 그리고 답변은 마크다운으로 렌더한다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.text()).toContain('인증 흐름 확인')
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 완료')
    expect(w.text()).toContain('micthebick84/netis7.0 · main')
    expect(w.text()).toContain('누적 비용 0.42')
    expect(w.find('[data-test="context-chip"]').text()).toContain('컨텍스트 38%')
    expect(w.find('[data-test="composer-send"]').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false) // 기본 입력창 대신 슬롯
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined() // ask 모드 = 고정
    expect(w.find('.bubble.assistant strong').text()).toBe('AuthController')
    w.unmount()
  })

  it('입력창에서 Enter로 추가 질문을 보낸다 (replyToSeq = 마지막 assistant seq)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    const ta = w.find('textarea')
    await ta.setValue('리프레시 토큰은요?')
    await ta.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
      method: 'POST',
      body: { answer: '리프레시 토큰은요?', replyToSeq: 1 },
    })
    w.unmount()
  })

  it('SSE 상태가 배지에 즉시 반영되고, 세션 종료 버튼은 패널의 종료 확인 다이얼로그를 연다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 중')
    await w.find('[data-test="close-session"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    w.unmount()
  })

  it('컨텍스트 미보고(null)면 칩을 숨긴다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve({ ...detail, contextTokens: null, contextWindow: null })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="context-chip"]').exists()).toBe(false)
    w.unmount()
  })

  it('조회 실패(403/404)면 목록으로 돌려보낸다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.reject({ statusCode: 403, data: { message: '권한이 없습니다' } })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })
})
