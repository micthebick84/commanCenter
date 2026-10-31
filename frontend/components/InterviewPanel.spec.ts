import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, vi } from 'vitest'
import { h } from 'vue'
import InterviewPanel from './InterviewPanel.vue'
import { FakeEventSource } from '../test/mocks/eventsource'
import { authStub, useApiMock } from '../test/mocks/nuxt'

async function mountPanel(
  sessionId = 5,
  snapshot: any = { statusName: null, turns: [], plan: null },
  props: Record<string, unknown> = {},
) {
  authStub.accessToken = 'jwt'
  useApiMock.mockResolvedValueOnce(snapshot) // onMounted의 GET /{id}가 소비
  const w = mount(InterviewPanel, { props: { sessionId, ...props } })
  await flushPromises() // 마운트 스냅샷 GET 해소 + hydrate 완료
  return w
}

describe('InterviewPanel — transcript', () => {
  it('renders assistant question turns from the stream', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('question', { seq: 1, content: '트리거가 뭔가요?' })
    await flushPromises()
    expect(w.text()).toContain('트리거가 뭔가요?')
    w.unmount()
  })
})

describe('InterviewPanel — design sections', () => {
  it('shows a check icon for approved sections', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('design', {
      key: 'overview',
      title: '개요',
      body: '확정안',
      approved: true,
    })
    await flushPromises()
    expect(w.text()).toContain('개요')
    expect(w.html()).toContain('check_circle')
    w.unmount()
  })
})

describe('InterviewPanel — answer flow', () => {
  it('POSTs the answer with replyToSeq, optimistically appends a user turn, clears input', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('question', { seq: 3, content: '범위는?' })
    await flushPromises()

    await w.find('textarea').setValue('대시보드만 추가합니다')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: '대시보드만 추가합니다', replyToSeq: 3 },
    })
    expect(w.text()).toContain('대시보드만 추가합니다')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    w.unmount()
  })

  it('disables send when not AWAITING_INPUT', async () => {
    const w = await mountPanel(9)
    // Canonical wire payload: bare English enum name.
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    const btn = w.find('[data-test="send-answer"]')
    expect(btn.attributes('disabled')).toBeDefined()
    w.unmount()
  })
})

describe('InterviewPanel — confirm flow', () => {
  it('enables 구현 진행 only at PLAN_READY and emits confirmed with taskId', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()

    // Before plan_ready: button is disabled.
    es.emit('design', { key: 'a', title: 'A', body: 'b', approved: true })
    await flushPromises()
    let reg = w.find('[data-test="confirm"]')
    expect(reg.attributes('disabled')).toBeDefined()

    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    reg = w.find('[data-test="confirm"]')
    expect(reg.attributes('disabled')).toBeUndefined()

    const vm = w.vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    useApiMock.mockResolvedValueOnce({ taskId: 123 }) // 이제 다음 useApi 호출(confirm POST)이 소비
    await reg.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false, implementationMode: 'REVIEW' },
    })
    expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 구현 큐에 진입했습니다' })
    expect(w.emitted('confirmed')?.[0]).toEqual([123])
    notifySpy.mockRestore()
    w.unmount()
  })

  it('sends designRequested: true when the 디자인 단계 포함 toggle is on', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()

    await w.find('.q-toggle').trigger('click')
    await flushPromises()

    const vm = w.vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    useApiMock.mockResolvedValueOnce({ taskId: 124 })
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: true, implementationMode: 'REVIEW' },
    })
    expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 디자인 큐에 진입했습니다' })
    notifySpy.mockRestore()
    w.unmount()
  })

  it('알림은 확정을 보낸 시점의 토글 값을 따른다 — 응답 대기 중 토글을 꺼도 디자인 큐 알림', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()

    await w.find('.q-toggle').trigger('click') // 켬
    await flushPromises()

    const vm = w.vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    let resolveConfirm!: (v: { taskId: number }) => void
    useApiMock.mockReturnValueOnce(new Promise((resolve) => { resolveConfirm = resolve })) // 응답 전까지 대기
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: true, implementationMode: 'REVIEW' },
    })

    await w.find('.q-toggle').trigger('click') // 응답 전에 끔
    await flushPromises()
    resolveConfirm({ taskId: 126 })
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 디자인 큐에 진입했습니다' })
    expect(notifySpy).not.toHaveBeenCalledWith({ type: 'positive', message: '확정 완료 — 구현 큐에 진입했습니다' })
    expect(w.emitted('confirmed')?.[0]).toEqual([126])
    notifySpy.mockRestore()
    w.unmount()
  })

  it('sends the chosen implementation mode (default REVIEW, switchable to SDD)', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    expect(w.find('[data-test="impl-mode-description"]').text()).toContain('리뷰 1회')

    await w.find('[data-test="impl-mode-SDD"]').trigger('click')
    await flushPromises()
    useApiMock.mockResolvedValueOnce({ taskId: 125 })
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false, implementationMode: 'SDD' },
    })
    w.unmount()
  })

  it('hides the mode picker for read-only viewers', async () => {
    const w = await mountPanel(9, { statusName: null, turns: [], plan: null }, { readonly: true })
    expect(w.find('[data-test="impl-mode-picker"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('InterviewPanel — plan_ready design display', () => {
  it('renders plan.designMarkdown when plan_ready arrives', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('plan_ready', {
      designMarkdown: '# 설계본문XYZ',
      planMarkdown: '# 플랜',
      planJson: JSON.stringify([]),
    })
    await flushPromises()
    expect(w.text()).toContain('설계본문XYZ')
    expect(w.text()).toContain('설계 문서')
    w.unmount()
  })
})

describe('InterviewPanel — terminal states', () => {
  it('shows an expired banner from the bare EXPIRED status', async () => {
    const w = await mountPanel(9)
    // Canonical wire payload: bare English enum name.
    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.text()).toContain('만료')
    w.unmount()
  })

  it('renders the Korean badge label (not the raw English enum) for AWAITING_INPUT', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('status', 'AWAITING_INPUT')
    await flushPromises()
    const txt = w.text()
    expect(txt).toContain('입력 대기') // Korean label, display-only
    expect(txt).not.toContain('AWAITING_INPUT') // raw enum name must not surface
    w.unmount()
  })

  it('shows a failed banner from the bare FAILED status', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    expect(w.text()).toContain('인터뷰 실패')
    w.unmount()
  })

  // doFail은 터미널 전이라 FAILED 세션의 마지막 턴은 항상 그 실패 노트다 — 배너는 그걸 그대로 쓴다.
  it('shows the failure reason from the note the server pushes before FAILED', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('note', { seq: 0, content: '인터뷰 실패: OAuth 세션 만료' })
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    expect(w.text()).toContain('OAuth 세션 만료')
    expect(w.text()).not.toContain('알 수 없는 오류')
    w.unmount()
  })

  it('shows the failure reason after a reload (hydrated snapshot, no live events)', async () => {
    const w = await mountPanel(9, {
      statusName: 'FAILED',
      turns: [{ seq: 0, role: 'system', kind: 'note', content: '인터뷰 실패: 디스크 부족' }],
      plan: null,
    })
    expect(w.text()).toContain('디스크 부족')
    expect(w.text()).not.toContain('알 수 없는 오류')
    w.unmount()
  })

  // 배너가 실패 노트를 보여주는 동안 대화 칩까지 같은 문장을 반복하지 않는다 (라이브 검증 2026-09-22에서 발견).
  it('does not repeat the failure note as a transcript chip while the banner shows it', async () => {
    const w = await mountPanel(9, {
      statusName: 'FAILED',
      turns: [
        { seq: 0, role: 'assistant', kind: 'question', content: '범위는?' },
        { seq: 1, role: 'system', kind: 'note', content: '인터뷰 실패: 디스크 부족' },
      ],
      plan: null,
    })
    expect(w.text().split('디스크 부족').length - 1).toBe(1) // 배너 1회만
    expect(w.findAll('.system-note')).toHaveLength(0)
    expect(w.text()).toContain('범위는?') // 나머지 대화는 그대로
    w.unmount()
  })

  it('keeps ordinary system notes (e.g. MCP change) as chips while the session is alive', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('question', { seq: 0, content: '범위는?' })
    FakeEventSource.last().emit('note', { seq: 1, content: 'MCP 변경: obsidian 추가' })
    await flushPromises()
    expect(w.findAll('.system-note')).toHaveLength(1)
    expect(w.text()).toContain('MCP 변경: obsidian 추가')
    w.unmount()
  })

  it('falls back to the generic message when a FAILED session carries no note', async () => {
    const w = await mountPanel(9)
    FakeEventSource.last().emit('question', { seq: 0, content: '범위는?' })
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    expect(w.text()).toContain('알 수 없는 오류')
    w.unmount()
  })
})

describe('InterviewPanel — typing indicator', () => {
  it('shows the typing indicator while RUNNING and hides it once AWAITING_INPUT', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(true)

    FakeEventSource.last().emit('question', { seq: 1, content: '범위는?' })
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    w.unmount()
  })

  it('hides the typing indicator at PLAN_READY and on terminal states', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('plan_ready', {
      designMarkdown: 'd',
      planMarkdown: 'p',
      planJson: JSON.stringify([]),
    })
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)

    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('InterviewPanel — cancel flow', () => {
  // q-dialog는 <body>로 teleport되므로 잔여 노드를 정리한다.
  afterEach(() => {
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('opens a confirm dialog and only cancels after 취소하기', async () => {
    const w = await mountPanel(7)
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()

    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    const confirm = document.querySelector('[data-test="cancel-confirm"]') as HTMLElement | null
    expect(confirm).toBeTruthy()

    confirm!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/7/cancel', { method: 'POST' })
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })

  it('does not cancel when 계속하기 is chosen', async () => {
    const w = await mountPanel(7)
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()

    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    const keep = document.querySelector('[data-test="cancel-keep"]') as HTMLElement | null
    expect(keep).toBeTruthy()

    keep!.click()
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/interviews/7/cancel', { method: 'POST' })
    expect(w.emitted('close')).toBeFalsy()
    w.unmount()
  })
})

describe('InterviewPanel — terminal close', () => {
  it('shows a 닫기 button on FAILED that emits close', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    const close = w.find('[data-test="close-interview"]')
    expect(close.exists()).toBe(true)
    await close.trigger('click')
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
})

describe('InterviewPanel — refresh resume (snapshot hydration)', () => {
  it('fetches GET /{id} on mount and renders prior conversation incl. my answers', async () => {
    const w = await mountPanel(9, {
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: '인증 방식은?' },
        { seq: 2, role: 'user', kind: 'answer', content: 'OAuth2 입니다' },
        { seq: 3, role: 'assistant', kind: 'question', content: '토큰 TTL은?' },
      ],
      plan: null,
    })
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    expect(w.text()).toContain('인증 방식은?')
    expect(w.text()).toContain('OAuth2 입니다')
    expect(w.text()).toContain('토큰 TTL은?')
    expect(w.text()).toContain('입력 대기')
    w.unmount()
  })

  it('shows a non-cancelling 나중에 button on a non-terminal session that emits close', async () => {
    const w = await mountPanel(9, { statusName: 'AWAITING_INPUT', turns: [], plan: null })
    const later = w.find('[data-test="later-interview"]')
    expect(later.exists()).toBe(true)
    await later.trigger('click')
    expect(w.emitted('close')).toBeTruthy()
    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    w.unmount()
  })

  it('still opens the stream and renders live events when the snapshot fetch fails', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockRejectedValueOnce(new Error('500')) // 마운트 스냅샷 GET 실패(비치명)
    const w = mount(InterviewPanel, { props: { sessionId: 9 } })
    await flushPromises()
    // 스냅샷 실패에도 stream.open은 호출되어 라이브 질문을 받는다
    FakeEventSource.last().emit('question', { seq: 1, content: '폴백 질문' })
    await flushPromises()
    expect(w.text()).toContain('폴백 질문')
    w.unmount()
  })

  // 스냅샷이 실패하면 hydrate가 seq를 먼저 채워 주지 않아 replay만으로 판단해야 한다. 서버는 system 턴을 note로
  // replay하고(InterviewApiIntegrationTest가 와이어 고정), 패널은 이를 AI 말풍선이 아닌 노트 칩으로 그리며 입력 대기로 넘기지 않는다.
  it('renders a replayed system note as a note chip without flipping to 입력 대기 when the snapshot fetch fails', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockRejectedValueOnce(new Error('500'))
    const w = mount(InterviewPanel, { props: { sessionId: 9 } })
    await flushPromises()
    FakeEventSource.last().emit('note', { seq: 1, content: '사용자 취소' })
    await flushPromises()
    expect(w.findAll('.system-note').map((n) => n.text())).toEqual(['사용자 취소'])
    expect(w.findAll('.bubble.assistant')).toHaveLength(0)
    expect(w.find('.q-badge').text()).toBe('연결 중')
    expect(w.emitted('status')?.flat()).not.toContain('AWAITING_INPUT')
    w.unmount()
  })
})

describe('InterviewPanel — 확정 / 읽기 전용', () => {
  it('플랜 완료 후 확정하면 /confirm을 호출한다', async () => {
    const w = await mountPanel(9, {
      statusName: 'PLAN_READY',
      turns: [],
      plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: '[]' },
    })

    useApiMock.mockResolvedValueOnce({ taskId: 42 })
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false, implementationMode: 'REVIEW' },
    })
    expect(w.emitted('confirmed')).toBeTruthy()
    w.unmount()
  })

  it('readonly면 답변 입력·확정 버튼을 렌더하지 않는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({
      statusName: 'AWAITING_INPUT',
      turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }],
      plan: null,
    })
    const w = mount(InterviewPanel, { props: { sessionId: 9, readonly: true } })
    await flushPromises()

    expect(w.text()).toContain('범위는?')          // 대화는 보인다
    expect(w.find('textarea').exists()).toBe(false) // 입력창은 없다
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false)
    expect(w.find('[data-test="confirm"]').exists()).toBe(false)
    w.unmount()
  })
})

// 확정 화면의 구현 참조 (작업 스펙 2026-10-24 §7.5) — 작업 상세가 폴링하는 세션 참조(sessionRefRepos)를 서버의 확정 복사 규칙(§4.6)으로 나눈다
describe('InterviewPanel — 확정 화면 참조', () => {
  const PLAN_READY = {
    statusName: 'PLAN_READY',
    turns: [],
    plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: '[]' },
  }
  const PINNED = { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678', fetchedAt: '2026-10-24T01:00:00Z', error: null }
  const PINNED2 = { catalogId: 4, alias: '옛앱2', branch: 'main', sha: 'f0e1d2c3b4a5968778695a4b3c2d1e0f12345678', fetchedAt: '2026-10-24T01:00:00Z', error: null }
  const NO_SHA = { catalogId: 5, alias: '인증 서버', branch: 'develop', sha: null, fetchedAt: null, error: 'fatal: Remote branch develop not found' }
  /** a가 b보다 문서에서 앞에 있는가 */
  const precedes = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

  it.each([false, true])(
    '확정 화면: 구현에 쓸 참조 (이 커밋으로 고정) — 고정 칩 2개 · stale/dropped 경고 줄, readonly에도 보인다 (readonly=%s)',
    async (readonly) => {
      const w = await mountPanel(9, PLAN_READY, { readonly, sessionRefRepos: [PINNED, { ...PINNED2, error: 'x' }, NO_SHA] })
      const box = w.find('[data-test="confirm-refs"]')
      expect(box.exists()).toBe(true)
      expect(box.text()).toContain('구현에 쓸 참조 (이 커밋으로 고정)')
      const chips = box.findAll('[data-test="ref-chip"]')
      expect(chips.map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pinned']) // 마지막 턴 실패도 고정 커밋으로 넘어간다
      expect(chips[0]!.text()).toContain('📚 옛앱 · release/6.5@a1b2c3d')
      expect(chips[1]!.text()).toContain('📚 옛앱2 · main@f0e1d2c')
      expect(box.findAll('.q-chip__icon--remove')).toHaveLength(0) // 읽기 전용
      expect(box.findAll('[data-test="confirm-ref-stale"]').map((n) => n.text())).toEqual([
        '⚠ 옛앱2: 마지막 턴에는 받지 못했습니다 — 고정 커밋으로 구현합니다',
      ])
      expect(box.findAll('[data-test="confirm-ref-dropped"]').map((n) => n.text())).toEqual([
        '⚠ 인증 서버(develop) — 인터뷰에서 받지 못해 구현에 넘기지 않습니다',
      ])
      if (readonly) {
        expect(w.find('[data-test="confirm"]').exists()).toBe(false)
      } else {
        expect(precedes(box.element, w.find('[data-test="confirm"]').element)).toBe(true) // "구현 진행" 위
        // 확정 바디는 그대로 — 참조는 서버가 세션에서 옮긴다
        useApiMock.mockResolvedValueOnce({ taskId: 42 })
        await w.find('[data-test="confirm"]').trigger('click')
        await flushPromises()
        expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
          method: 'POST',
          body: { designRequested: false, implementationMode: 'REVIEW' },
        })
      }
      w.unmount()
    },
  )

  it('고정된 참조가 하나도 없으면 칩 없이 경고 줄만', async () => {
    const w = await mountPanel(9, PLAN_READY, { sessionRefRepos: [NO_SHA] })
    const box = w.find('[data-test="confirm-refs"]')
    expect(box.exists()).toBe(true)
    expect(box.findAll('[data-test="ref-chip"]')).toHaveLength(0)
    expect(box.find('[data-test="ref-repo-chips"]').exists()).toBe(false)
    expect(box.findAll('[data-test="confirm-ref-dropped"]')).toHaveLength(1)
    expect(box.find('[data-test="confirm-ref-stale"]').exists()).toBe(false)
    w.unmount()
  })

  it('sessionRefRepos가 비면 confirm-refs가 없고 /confirm 바디는 그대로', async () => {
    for (const sessionRefRepos of [[], undefined]) {
      useApiMock.mockReset()
      const w = await mountPanel(9, PLAN_READY, { sessionRefRepos })
      expect(w.find('[data-test="confirm-refs"]').exists()).toBe(false)
      useApiMock.mockResolvedValueOnce({ taskId: 42 })
      await w.find('[data-test="confirm"]').trigger('click')
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
        method: 'POST',
        body: { designRequested: false, implementationMode: 'REVIEW' },
      })
      w.unmount()
    }
  })

  it('플랜이 오기 전에는 참조가 있어도 확정 참조 줄을 그리지 않는다', async () => {
    const w = await mountPanel(
      9,
      { statusName: 'AWAITING_INPUT', turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }], plan: null },
      { sessionRefRepos: [PINNED] },
    )
    expect(w.find('[data-test="confirm-refs"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('InterviewPanel — 진행 활동(pending) 버블', () => {
  it('activity 수신 시 PendingBubble을 표시하고 TypingIndicator는 숨긴다', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(true) // 활동 전엔 기존 점 표시
    FakeEventSource.last().emit('activity', {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'src/pages/login.vue' },
        { seq: 2, type: 'text', content: '레포를 먼저 읽겠습니다' },
      ],
    })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(true)
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    expect(w.text()).toContain('src/pages/login.vue')
    expect(w.text()).toContain('레포를 먼저 읽겠습니다')
    w.unmount()
  })

  it('확정 question 도착 시 PendingBubble이 사라지고 확정 말풍선으로 대체된다', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    FakeEventSource.last().emit('activity', {
      events: [{ seq: 1, type: 'text', content: '어떤 인증을' }],
    })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(true)
    FakeEventSource.last().emit('question', { seq: 1, content: '어떤 인증을 쓰나요?' })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(false)
    expect(w.text()).toContain('어떤 인증을 쓰나요?') // ChatBubble 확정 턴
    w.unmount()
  })
})

describe('InterviewPanel — kind=QUESTION (스펙 2026-08-30 §7)', () => {
  afterEach(() => {
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  const answered = {
    statusName: 'AWAITING_INPUT',
    turns: [{ seq: 1, role: 'assistant', kind: 'question', content: 'AuthController에서 처리합니다' }],
    plan: null,
  }

  it('snapshot GET과 SSE를 /api/questions 아래로 연다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9')
    expect(FakeEventSource.last().url).toBe('/api/questions/9/stream?access_token=jwt')
    w.unmount()
  })

  it('설계·플랜 컬럼/탭/확정 버튼을 렌더하지 않고 대화 컬럼만 남긴다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(w.find('.design-col').exists()).toBe(false)
    expect(w.find('.interview-tabs').exists()).toBe(false)
    expect(w.find('[data-test="confirm"]').exists()).toBe(false)
    expect(w.find('.chat-col').exists()).toBe(true)
    expect(w.text()).toContain('AuthController에서 처리합니다')
    w.unmount()
  })

  it('질문 문맥 라벨/버튼: 답변 완료 · 추가 질문 · 세션 종료', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(w.text()).toContain('답변 완료')
    expect(w.find('[data-test="send-answer"]').text()).toContain('추가 질문')
    expect(w.find('[data-test="cancel-interview"]').text()).toContain('세션 종료')
    expect(w.find('textarea').attributes('placeholder')).toBe('추가 질문을 입력하세요…')
    w.unmount()
  })

  it('추가 질문은 POST /api/questions/{id}/ask 로 (replyToSeq = 마지막 답변 seq)', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    await w.find('textarea').setValue('토큰 검증은요?')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9/ask', {
      method: 'POST',
      body: { answer: '토큰 검증은요?', replyToSeq: 1 },
    })
    expect(w.text()).toContain('토큰 검증은요?')
    w.unmount()
  })

  it('composer 슬롯의 send({model, effort})는 QUESTION ask 바디에 모델·effort를 싣는다 (대화 중 변경)', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce(answered)
    const w = mount(InterviewPanel, {
      props: { sessionId: 9, kind: 'QUESTION' },
      slots: {
        composer: ({ setAnswer, send }: any) =>
          h('button', {
            'data-test': 'slot-send',
            onClick: () => {
              setAnswer('이번 건 싸게')
              send({ model: 'claude-haiku-4-5', effort: 'low' })
            },
          }),
      },
    })
    await flushPromises()
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9/ask', {
      method: 'POST',
      body: { answer: '이번 건 싸게', replyToSeq: 1, model: 'claude-haiku-4-5', effort: 'low' },
    })
    w.unmount()
  })

  it('INTERVIEW 세션의 answer 바디는 send(extra)가 와도 model/effort를 싣지 않는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({
      statusName: 'AWAITING_INPUT',
      turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '어떤 DB인가요?' }],
      plan: null,
    })
    const w = mount(InterviewPanel, {
      props: { sessionId: 9 },
      slots: {
        composer: ({ setAnswer, send }: any) =>
          h('button', {
            'data-test': 'slot-send',
            onClick: () => {
              setAnswer('PostgreSQL')
              send({ model: 'claude-haiku-4-5', effort: 'low' })
            },
          }),
      },
    })
    await flushPromises()
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: 'PostgreSQL', replyToSeq: 1 },
    })
    w.unmount()
  })

  // 참조 repo (스펙 2026-10-22 §7.3) — 배열이면 그대로 싣는다. []는 "전부 해제"라 빈 배열도 필드를 남긴다(생략은 "유지").
  it.each([
    ['참조를 바꾸면 그 목록', [{ repoCatalogId: 2, branch: 'release/6.5' }]],
    ['빈 배열은 빈 배열(전부 해제)', []],
  ])('composer 슬롯의 send({refRepos})는 QUESTION ask 바디에 싣는다 — %s', async (_label, refRepos) => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce(answered)
    const w = mount(InterviewPanel, {
      props: { sessionId: 9, kind: 'QUESTION' },
      slots: {
        composer: ({ setAnswer, send }: any) =>
          h('button', {
            'data-test': 'slot-send',
            onClick: () => {
              setAnswer('옛앱 기준으로')
              send({ refRepos })
            },
          }),
      },
    })
    await flushPromises()
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9/ask', {
      method: 'POST',
      body: { answer: '옛앱 기준으로', replyToSeq: 1, refRepos },
    })
    w.unmount()
  })

  // 작업 인터뷰 중 참조 변경 (작업 스펙 2026-10-24 §4.3·§7.4) — INTERVIEW answer도 참조만은 싣는다. 모델·effort는 인터뷰에서 바꾸지 않는다.
  it('INTERVIEW answer 바디도 send({refRepos})를 싣는다 — model/effort는 여전히 안 싣는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({
      statusName: 'AWAITING_INPUT',
      turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '어떤 DB인가요?' }],
      plan: null,
    })
    const w = mount(InterviewPanel, {
      props: { sessionId: 9 },
      slots: {
        composer: ({ setAnswer, send }: any) =>
          h('button', {
            'data-test': 'slot-send',
            onClick: () => {
              setAnswer('PostgreSQL')
              send({ model: 'claude-haiku-4-5', effort: 'low', refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }] })
            },
          }),
      },
    })
    await flushPromises()
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: 'PostgreSQL', replyToSeq: 1, refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }] },
    })
    w.unmount()
  })

  // 작업 상세는 슬롯 없이 기본 입력창을 쓴다 — 바뀐 참조는 prop(answerRefRepos)으로 받아 싣는다(null = 유지라 키 없음, [] = 전부 해제)
  it.each([
    ['배열이면 그 목록', [{ repoCatalogId: 2, branch: 'release/6.5' }]],
    ['빈 배열은 빈 배열(전부 해제)', []],
  ])('answerRefRepos가 배열이면 기본 입력창 전송에 싣는다 — %s', async (_label, refRepos) => {
    const w = await mountPanel(
      9,
      { statusName: 'AWAITING_INPUT', turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }], plan: null },
      { answerRefRepos: refRepos },
    )
    await w.find('textarea').setValue('옛앱 기준으로')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: '옛앱 기준으로', replyToSeq: 1, refRepos },
    })
    w.unmount()
  })

  it.each([null, undefined])('answerRefRepos가 %s면 바디는 { answer, replyToSeq } 그대로 — refRepos 키가 없다', async (answerRefRepos) => {
    const w = await mountPanel(
      9,
      { statusName: 'AWAITING_INPUT', turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }], plan: null },
      { answerRefRepos },
    )
    await w.find('textarea').setValue('그대로')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/interviews/9/answer')
    expect(call![1]).toEqual({ method: 'POST', body: { answer: '그대로', replyToSeq: 1 } })
    expect(call![1].body).not.toHaveProperty('refRepos') // toEqual은 undefined 필드를 못 거른다
    w.unmount()
  })

  it('QUESTION ask는 answerRefRepos prop을 쓰지 않는다 — 슬롯 send가 넘긴 것만', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION', answerRefRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }] })
    await w.find('textarea').setValue('토큰 검증은요?')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/9/ask')
    expect(call![1].body).toEqual({ answer: '토큰 검증은요?', replyToSeq: 1 })
    expect(call![1].body).not.toHaveProperty('refRepos')
    w.unmount()
  })

  it('턴 상한 400은 경고 토스트로 안내하고 입력을 지우지 않는다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    await w.find('textarea').setValue('또?')
    useApiMock.mockRejectedValueOnce({ statusCode: 400, data: { message: '최대 문답 수(10)에 도달했습니다' } })
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('또?')
    expect(document.body.textContent).toContain('최대 문답 수')
    w.unmount()
  })

  it('세션 종료는 확인 다이얼로그 후 POST /api/questions/{id}/close', async () => {
    const w = await mountPanel(7, answered, { kind: 'QUESTION' })
    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    ;(document.querySelector('[data-test="cancel-confirm"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/7/close', { method: 'POST' })
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })

  it('kind 미지정 → 기존 인터뷰 경로/컬럼 유지', async () => {
    const w = await mountPanel(9, answered)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    expect(w.find('.design-col').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').text()).toContain('전송')
    w.unmount()
  })
})

describe('InterviewPanel — 질문 채팅 셸 연동 (스펙 2026-09-05 §2)', () => {
  it('hideStatusBar면 배지·버튼을 숨기되 터미널 배너는 보여준다', async () => {
    const w = await mountPanel(5, { statusName: null, turns: [], plan: null }, { kind: 'QUESTION', hideStatusBar: true })
    expect(w.find('.status-bar').exists()).toBe(false)
    expect(w.find('[data-test="cancel-interview"]').exists()).toBe(false)
    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.text()).toContain('세션이 만료되었습니다')
    w.unmount()
  })

  it('composer 슬롯에 답변 상태/전송 함수를 넘기고 기본 입력창은 렌더하지 않는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({ statusName: null, turns: [], plan: null })
    const w = mount(InterviewPanel, {
      props: { sessionId: 7, kind: 'QUESTION' },
      slots: {
        composer: (p: any) => [
          h('div', { 'data-test': 'slot-awaiting' }, String(p.awaiting)),
          h('button', { 'data-test': 'slot-send', disabled: !p.canSend, onClick: () => p.send() }, 'go'),
          h('input', {
            'data-test': 'slot-input',
            value: p.answer,
            onInput: (e: Event) => p.setAnswer((e.target as HTMLInputElement).value),
          }),
        ],
      },
    })
    await flushPromises()
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false)
    FakeEventSource.last().emit('question', { seq: 1, content: '답변입니다' })
    await flushPromises()
    expect(w.find('[data-test="slot-awaiting"]').text()).toBe('true')
    await w.find('[data-test="slot-input"]').setValue('추가 질문')
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/7/ask', {
      method: 'POST',
      body: { answer: '추가 질문', replyToSeq: 1 },
    })
    w.unmount()
  })

  it('status를 emit하고 requestCancel()이 종료 확인 다이얼로그를 연다', async () => {
    const w = await mountPanel(5, { statusName: 'AWAITING_INPUT', turns: [], plan: null }, { kind: 'QUESTION', hideStatusBar: true })
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    const emitted = w.emitted('status')!
    expect(emitted[emitted.length - 1]).toEqual(['RUNNING'])
    ;(w.vm as any).requestCancel()
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    w.unmount()
  })
})
