import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import QuestionDetail from '../pages/questions/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'
import { setViewportWidth } from './mocks/screen'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 헤더 폴링(GET /api/questions/3)과 패널 스냅샷(같은 URL)이 모두 이 응답을 쓴다.
const detail = {
  id: 3,
  title: '인증 흐름 확인',
  githubRepo: 'micthebick84/netis7.0',
  githubBranch: 'main',
  status: '입력대기',
  statusName: 'AWAITING_INPUT',
  model: 'claude-sonnet-5',
  effort: 'medium',
  kind: 'QUESTION',
  totalCostUsd: 0.42,
  contextTokens: 76004,
  contextWindow: 200000,
  turns: [
    {
      seq: 1,
      role: 'assistant',
      kind: 'question',
      content: '**AuthController**입니다',
    },
  ],
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
    expect(w.find('[data-test="context-chip"]').text()).toContain(
      '컨텍스트 38%',
    )
    expect(w.find('[data-test="composer-send"]').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false) // 기본 입력창 대신 슬롯
    expect(
      w.find('[data-test="model-picker"]').attributes('disabled'),
    ).toBeDefined() // ask 모드 = 고정
    expect(w.find('.bubble.assistant strong').text()).toBe('AuthController')
    expect(w.find('[data-test="close-session"]').text()).toContain('세션 종료') // 넓은 화면: 라벨 노출
    expect(w.find('[data-test="open-drawer"]').exists()).toBe(false)
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
        ? Promise.resolve({
            ...detail,
            contextTokens: null,
            contextWindow: null,
          })
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
        ? Promise.reject({
            statusCode: 403,
            data: { message: '권한이 없습니다' },
          })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })

  it('폴링 중 500 등 비403/404 오류는 목록으로 이동하지 않고 마지막 헤더를 유지한다', async () => {
    // useTaskPolling은 전역 스텁(setup.ts) — 실제 컴포저블처럼 감싸 refresh 핸들을 잡아서
    // "다음 폴링 틱"을 테스트에서 직접 발동시킨다 (첫 마운트는 그대로 성공시킨 뒤).
    const originalUseTaskPolling = (globalThis as any).useTaskPolling
    let capturedRefresh: (() => Promise<void>) | undefined
    ;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
      const handle = originalUseTaskPolling(fetcher)
      if (!capturedRefresh) capturedRefresh = handle.refresh
      return handle
    }
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.text()).toContain('인증 흐름 확인')
      expect(w.text()).toContain('누적 비용 0.42')

      useApiMock.mockImplementation((url: string) =>
        url === '/api/questions/3'
          ? Promise.reject({ statusCode: 500 })
          : Promise.resolve({ limits: [] }),
      )
      await capturedRefresh!()
      await flushPromises()

      expect(navigateToMock).not.toHaveBeenCalled()
      expect(w.text()).toContain('인증 흐름 확인')
      expect(w.text()).toContain('누적 비용 0.42')
      w.unmount()
    } finally {
      ;(globalThis as any).useTaskPolling = originalUseTaskPolling
    }
  })

  it('좁은 화면(lt.md)에서는 세션 종료 버튼이 라벨 없이 아이콘만 남기고 aria-label로 이름을 유지한다', async () => {
    // 390px에서 라벨이 두 줄로 꺾여 61px 헤더 밖으로 넘치던 결함(2026-09-06 모바일 QA).
    await setViewportWidth(390)
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.find('[data-test="open-drawer"]').exists()).toBe(true) // 서랍 버튼 = 좁은 화면 분기 진입 확인
      const btn = w.find('[data-test="close-session"]')
      expect(btn.exists()).toBe(true)
      expect(btn.text()).not.toContain('세션 종료')
      expect(btn.attributes('aria-label')).toBe('세션 종료')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('xs 화면(<600px)에서는 (고정된) MCP 도구 버튼도 라벨 없이 아이콘만 남긴다', async () => {
    await setViewportWidth(390)
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      const btn = w.find('[data-test="mcp-button"]')
      expect(btn.exists()).toBe(true)
      expect(btn.text()).not.toContain('MCP 도구')
      expect(btn.attributes('aria-label')).toBe('MCP 도구')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})
