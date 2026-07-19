import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import InterviewPanel from './InterviewPanel.vue'
import { FakeEventSource } from '../test/mocks/eventsource'
import { authStub, useApiMock } from '../test/mocks/nuxt'

async function mountPanel(sessionId = 5, snapshot: any = { statusName: null, turns: [], plan: null }) {
  authStub.accessToken = 'jwt'
  useApiMock.mockResolvedValueOnce(snapshot) // onMounted의 GET /{id}가 소비
  const w = mount(InterviewPanel, { props: { sessionId } })
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

describe('InterviewPanel — register flow', () => {
  it('enables 작업 등록 only at PLAN_READY and emits registered with taskId', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()

    // Before plan_ready: button is disabled.
    es.emit('design', { key: 'a', title: 'A', body: 'b', approved: true })
    await flushPromises()
    let reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeDefined()

    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeUndefined()

    useApiMock.mockResolvedValueOnce({ taskId: 123 }) // 이제 다음 useApi 호출(register POST)이 소비
    await reg.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/register', {
      method: 'POST',
      body: { designRequested: false },
    })
    expect(w.emitted('registered')?.[0]).toEqual([123])
    w.unmount()
  })

  it('sends designRequested: true when the 디자인 단계 포함 toggle is on', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()

    await w.find('.q-toggle').trigger('click')
    await flushPromises()

    useApiMock.mockResolvedValueOnce({ taskId: 124 })
    await w.find('[data-test="register"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/register', {
      method: 'POST',
      body: { designRequested: true },
    })
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
})
