import { describe, it, expect } from 'vitest'
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
