import { describe, it, expect, vi } from 'vitest'
import { useInterviewStream } from './useInterviewStream'
import { FakeEventSource } from '../test/mocks/eventsource'
import { authStub } from '../test/mocks/nuxt'

describe('useInterviewStream — connect', () => {
  it('opens EventSource at /stream with access_token query', () => {
    authStub.accessToken = 'jwt-xyz'
    const s = useInterviewStream()
    s.open(42)
    const es = FakeEventSource.last()
    expect(es.url).toBe('/api/interviews/42/stream?access_token=jwt-xyz')
    s.close()
  })

  it('does not open when there is no access token', () => {
    authStub.accessToken = null as any
    const s = useInterviewStream()
    s.open(7)
    expect(FakeEventSource.instances.length).toBe(0)
    expect(s.error.value).toBe('인증 토큰이 없습니다')
  })
})

describe('useInterviewStream — question events', () => {
  it('appends an assistant question turn and flips to AWAITING_INPUT', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    FakeEventSource.last().emit('question', {
      seq: 1,
      content: '이 기능의 트리거는 무엇인가요?',
    })
    expect(s.turns.value).toHaveLength(1)
    expect(s.turns.value[0]).toMatchObject({
      seq: 1,
      role: 'assistant',
      kind: 'question',
      content: '이 기능의 트리거는 무엇인가요?',
    })
    expect(s.status.value).toBe('AWAITING_INPUT')
    s.close()
  })

  it('dedups questions by seq across reconnect replay', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('question', { seq: 1, content: 'Q1' })
    es.emit('question', { seq: 1, content: 'Q1' })
    expect(s.turns.value).toHaveLength(1)
    s.close()
  })
})

describe('useInterviewStream — status events', () => {
  it('reads the bare English enum-name status and auto-closes on terminal status', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    // Canonical wire payload: bare InterviewStatus.name() string, NOT a JSON object,
    // NOT the Korean dbValue.
    es.emit('status', 'RUNNING')
    expect(s.status.value).toBe('RUNNING')
    expect(s.connState.value).not.toBe('closed')
    es.emit('status', 'EXPIRED')
    expect(s.status.value).toBe('EXPIRED')
    expect(s.connState.value).toBe('closed')
  })

  it('ignores an unknown / non-enum status payload', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('status', 'RUNNING')
    es.emit('status', '없는상태') // a stray Korean label must NOT clobber state
    expect(s.status.value).toBe('RUNNING')
    s.close()
  })
})

describe('useInterviewStream — design events', () => {
  it('upserts design sections by key and tracks approved', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('design', { key: 'overview', title: '개요', body: '초안', approved: false })
    expect(s.designSections.value).toHaveLength(1)
    es.emit('design', { key: 'overview', title: '개요', body: '확정', approved: true })
    expect(s.designSections.value).toHaveLength(1)
    expect(s.designSections.value[0]).toMatchObject({ body: '확정', approved: true })
    es.emit('design', { key: 'data', title: '데이터', body: 'x', approved: false })
    expect(s.designSections.value).toHaveLength(2)
    s.close()
  })
})

describe('useInterviewStream — plan_ready/done', () => {
  it('captures the plan OBJECT and sets PLAN_READY on plan_ready event', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    // Canonical wire payload: a JSON OBJECT { designMarkdown, planMarkdown, planJson }
    // where planJson is itself a JSON STRING (the SDK service sends JSON.stringify(array)).
    // The composable parses the outer object, then JSON.parses the inner planJson string.
    FakeEventSource.last().emit('plan_ready', {
      designMarkdown: '# 설계',
      planMarkdown: '# 플랜',
      planJson: JSON.stringify([{ title: 'T1' }]), // planJson arrives as a JSON STRING
    })
    expect(s.status.value).toBe('PLAN_READY')
    expect(s.plan.value).toMatchObject({
      designMarkdown: '# 설계',
      planMarkdown: '# 플랜',
    })
    // planJson is parsed from the string into an array on receipt
    expect(Array.isArray(s.plan.value!.planJson)).toBe(true)
    expect(s.plan.value!.planJson).toEqual([{ title: 'T1' }])
    s.close()
  })

  it('closes the stream on done', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('done', {})
    expect(s.connState.value).toBe('closed')
  })
})

describe('useInterviewStream — reconnect', () => {
  it('reconnects after a transient error', () => {
    vi.useFakeTimers()
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    expect(FakeEventSource.instances).toHaveLength(1)
    FakeEventSource.last().triggerError()
    expect(s.connState.value).toBe('reconnecting')
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(2)
    s.close()
    vi.useRealTimers()
  })

  it('stops reconnecting after close()', () => {
    vi.useFakeTimers()
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    FakeEventSource.last().triggerError()
    s.close()
    vi.advanceTimersByTime(30000)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.useRealTimers()
  })
})

describe('useInterviewStream — hydrate (refresh resume)', () => {
  it('seeds turns (incl. user answers), status, and plan from a REST snapshot', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: 'Q1' },
        { seq: 2, role: 'user', kind: 'answer', content: 'A1' },
        { seq: 3, role: 'assistant', kind: 'question', content: 'Q2' },
        { seq: 4, role: 'assistant', kind: 'design', content: '설계초안' },
      ],
      plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: JSON.stringify([{ title: 'T' }]) },
    })
    expect(s.turns.value.map((t) => t.content)).toEqual(['Q1', 'A1', 'Q2'])
    expect(s.turns.value.find((t) => t.role === 'user')).toMatchObject({ content: 'A1' })
    expect(s.designSections.value).toHaveLength(1)
    expect(s.designSections.value[0]).toMatchObject({ key: 'design-4', title: '설계', body: '설계초안' })
    expect(s.status.value).toBe('AWAITING_INPUT')
    expect(s.plan.value).toMatchObject({ designMarkdown: '# 설계', planMarkdown: '# 플랜' })
    expect(s.plan.value!.planJson).toEqual([{ title: 'T' }])
  })

  it('does not duplicate turns/designs when SSE replay re-sends the same seqs after hydrate', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({
      statusName: 'AWAITING_INPUT',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: 'Q1' },
        { seq: 4, role: 'assistant', kind: 'design', content: '설계초안' },
      ],
      plan: null,
    })
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('question', { seq: 1, content: 'Q1' })
    es.emit('design', { key: 'design-4', title: '설계', body: '설계초안', approved: false })
    expect(s.turns.value).toHaveLength(1)
    expect(s.designSections.value).toHaveLength(1)
    s.close()
  })

  it('no-ops on a null/empty snapshot (fresh session)', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate(null as any)
    s.hydrate({})
    expect(s.turns.value).toHaveLength(0)
    expect(s.status.value).toBeNull()
    expect(s.plan.value).toBeNull()
  })

  it('keeps a hydrated PLAN_READY status when SSE replay re-emits prior questions', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({
      statusName: 'PLAN_READY',
      turns: [
        { seq: 1, role: 'assistant', kind: 'question', content: 'Q1' },
        { seq: 2, role: 'user', kind: 'answer', content: 'A1' },
        { seq: 3, role: 'assistant', kind: 'design', content: '설계초안' },
      ],
      plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: '[]' },
    })
    expect(s.status.value).toBe('PLAN_READY')
    s.open(1)
    const es = FakeEventSource.last()
    // open()의 replay가 이미 답변된 이전 질문(seq 1)을 재전송 — status를 덮으면 안 됨
    es.emit('question', { seq: 1, content: 'Q1' })
    expect(s.status.value).toBe('PLAN_READY') // 회귀 가드: AWAITING_INPUT로 떨어지면 안 됨
    expect(s.plan.value).not.toBeNull()
    s.close()
  })

  it('flips to AWAITING_INPUT when a genuinely new question arrives live', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.hydrate({ statusName: 'RUNNING', turns: [], plan: null })
    s.open(1)
    FakeEventSource.last().emit('question', { seq: 1, content: '새 질문' }) // 신규 seq
    expect(s.status.value).toBe('AWAITING_INPUT')
    s.close()
  })
})
