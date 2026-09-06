import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
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

    useApiMock.mockResolvedValueOnce({ taskId: 123 }) // 이제 다음 useApi 호출(confirm POST)이 소비
    await reg.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false },
    })
    expect(w.emitted('confirmed')?.[0]).toEqual([123])
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
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
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
      body: { designRequested: false },
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
