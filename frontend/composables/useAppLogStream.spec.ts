import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { useAppLogStream } from './useAppLogStream'
import { FakeEventSource, CLOSED } from '../test/mocks/eventsource'

// 실제 컴포저블을 작은 호스트 컴포넌트에 마운트해 onMounted(visibilitychange 리스너)·onBeforeUnmount(정리)가
// 진짜로 도는 상태에서 본다(useNowTick.spec.ts 선례). 재연결은 setTimeout만 페이크로 돌린다.
let s: ReturnType<typeof useAppLogStream>
let taskId: number
let viewable: boolean
let token: string | null
let visibility: 'visible' | 'hidden'
let wrapper: VueWrapper | null

const Host = defineComponent({
  setup() {
    s = useAppLogStream({ taskId: () => taskId, viewable: () => viewable, token: () => token })
    return () => h('div')
  },
})

function mountHost() {
  wrapper = mount(Host)
  return wrapper
}

const ts = (sec: number) => `2026-11-03T01:00:${String(sec).padStart(2, '0')}.000000000Z`
const line = (sec: number, text = `line ${sec}`, stream: 'out' | 'err' = 'out') => ({ ts: ts(sec), stream, text })
const texts = () => s.lines.value.map((l) => l.text)
const es = (i: number) => FakeEventSource.instances[i]

function setVisibility(v: 'visible' | 'hidden') {
  visibility = v
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useAppLogStream (스펙 2026-11-03 §5 재연결)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    taskId = 7
    viewable = true
    token = 'jwt-1'
    visibility = 'visible'
    wrapper = null
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  })
  afterEach(() => {
    wrapper?.unmount()
    delete (document as { visibilityState?: unknown }).visibilityState
    vi.useRealTimers()
  })

  it('connect: /stream URL로 하나만 연다', () => {
    mountHost()
    expect(FakeEventSource.instances).toHaveLength(0) // 마운트만으로는 열지 않는다(카드가 connect를 부른다)
    expect(s.state.value).toBe('idle')

    s.connect()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(es(0).url).toBe('/stream/tasks/7/app-logs?access_token=jwt-1')
    expect(s.state.value).toBe('connecting')
    es(0).triggerOpen()
    expect(s.state.value).toBe('open')

    // 다시 부르면 이전 연결을 먼저 닫는다 — 열린 것은 늘 하나
    s.connect()
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(es(0).readyState).toBe(CLOSED)
    expect(FakeEventSource.instances.filter((e) => e.readyState !== CLOSED)).toHaveLength(1)
  })

  it('lines는 줄로 붙이고 info는 안내로', () => {
    mountHost()
    s.connect()
    es(0).emit('lines', { lines: [line(1, 'hello'), line(2, 'ERROR boom', 'err')] })
    expect(s.lines.value.map((l) => [l.text, l.stream, l.level])).toEqual([
      ['hello', 'out', null],
      ['ERROR boom', 'err', 'error'],
    ])
    expect(s.lines.value[0].seq).not.toBe(s.lines.value[1].seq)

    es(0).emit('info', { message: 'Error response from daemon: 잠시 뒤 다시' })
    expect(s.infoMessage.value).toBe('Error response from daemon: 잠시 뒤 다시')
    expect(s.lines.value).toHaveLength(2) // 안내는 줄로 넣지 않는다
  })

  it('end(not-allowed-status): 즉시 close, 사유, 10초 지나도 새 연결 없음', () => {
    mountHost()
    s.connect()
    es(0).emit('end', { reason: 'not-allowed-status', message: '지금 상태에서는 앱 로그를 볼 수 없습니다' })
    expect(es(0).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('ended')
    expect(s.endReason.value).toBe('not-allowed-status')
    expect(s.endMessage.value).toBe('지금 상태에서는 앱 로그를 볼 수 없습니다')

    vi.advanceTimersByTime(10_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(es(0).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('ended')
  })

  it('end(time-limit): 보이는 탭이면 바로 다시 연결하고, 다시 받은 tail 중 cutoff 이하는 버린다', () => {
    mountHost()
    s.connect()
    es(0).emit('lines', { lines: [line(1), line(2)] })
    token = 'jwt-2' // 다시 연결할 때마다 토큰을 새로 읽는다
    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })

    expect(es(0).readyState).toBe(CLOSED)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(es(1).url).toBe('/stream/tasks/7/app-logs?access_token=jwt-2')
    expect(s.state.value).toBe('reconnecting')
    expect(s.endReason.value).toBeNull()
    es(1).triggerOpen()
    expect(s.state.value).toBe('open')

    // 서버는 tail부터 다시 보낸다 — 이미 받은 ts(2 이하)는 버리고 새 줄만 붙인다
    es(1).emit('lines', { lines: [line(1), line(2), line(3)] })
    expect(texts()).toEqual(['line 1', 'line 2', 'line 3'])
  })

  it('end(time-limit): 숨은 탭이면 visibilitychange까지 기다린다', () => {
    mountHost()
    s.connect()
    visibility = 'hidden'
    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })
    expect(es(0).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('reconnecting')

    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    setVisibility('hidden') // 숨은 채 온 이벤트는 무시
    expect(FakeEventSource.instances).toHaveLength(1)

    setVisibility('visible')
    expect(FakeEventSource.instances).toHaveLength(2)
    setVisibility('visible') // 한 번만 — 다시 보여도 더 열지 않는다
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('end(container-stopped): 허용 상태면 3초 뒤 최대 2번, viewable false면 안 함', () => {
    mountHost()
    s.connect()
    const stopped = { reason: 'container-stopped', message: '컨테이너가 멈췄습니다' }

    es(0).emit('end', stopped)
    expect(es(0).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('reconnecting')
    vi.advanceTimersByTime(2_999)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeEventSource.instances).toHaveLength(2)

    es(1).emit('end', stopped)
    vi.advanceTimersByTime(3_000)
    expect(FakeEventSource.instances).toHaveLength(3)

    es(2).emit('end', stopped) // 이번 마운트에서 세 번째 — 끝
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(3)
    expect(FakeEventSource.instances.every((e) => e.readyState === CLOSED)).toBe(true)
    expect(s.state.value).toBe('ended')
    expect(s.endReason.value).toBe('container-stopped')
    expect(s.endMessage.value).toBe('컨테이너가 멈췄습니다')
  })

  it('end(container-stopped): viewable false면 다시 연결하지 않고, 기다리는 동안 false가 되면 idle', () => {
    mountHost()
    s.connect()
    viewable = false
    es(0).emit('end', { reason: 'container-stopped', message: '컨테이너가 멈췄습니다' })
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(s.state.value).toBe('ended')

    // 기다리는 사이 허용 밖이 되면 연결하지 않고 멈춘다
    viewable = true
    s.connect()
    es(1).emit('end', { reason: 'container-stopped', message: '컨테이너가 멈췄습니다' })
    viewable = false
    vi.advanceTimersByTime(3_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(s.state.value).toBe('idle')
  })

  it('end(too-many): 즉시 ended, 60초 지나도 instances 1, endMessage = 서버 message', () => {
    // 컨트롤러 판정 [T9]: 자동으로 다시 연결하지 않는다(설계 §5 "그 밖의 end").
    mountHost()
    s.connect()
    es(0).emit('end', { reason: 'too-many', message: '동시에 볼 수 있는 앱 로그 수를 넘었습니다' })
    expect(es(0).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('ended')
    expect(s.endReason.value).toBe('too-many')
    expect(s.endMessage.value).toBe('동시에 볼 수 있는 앱 로그 수를 넘었습니다')

    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(s.state.value).toBe('ended')
  })

  it('cutoff는 받은 ts의 최댓값이고 clear해도 남는다', () => {
    mountHost()
    s.connect()
    // stdout·stderr를 두 스레드가 읽어 받은 순서가 ts 순서가 아니다 — 한 연결 안에서는 둘 다 붙는다
    es(0).emit('lines', { lines: [line(3, 'ts3')] })
    es(0).emit('lines', { lines: [line(1, 'ts1', 'err')] })
    expect(texts()).toEqual(['ts3', 'ts1'])

    s.clear()
    expect(s.lines.value).toEqual([])

    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })
    expect(FakeEventSource.instances).toHaveLength(2)
    // cutoff = 최댓값 ts3(마지막으로 받은 ts1이 아니다) — ts1·ts3은 다시 나오지 않는다
    es(1).emit('lines', { lines: [line(1, 'ts1'), line(3, 'ts3'), line(4, 'ts4')] })
    expect(texts()).toEqual(['ts4'])
    // 연결 중에는 cutoff가 바뀌지 않는다 — 늦게 온 stderr 줄(ts 3.5)도 cutoff(ts3)보다 크면 붙는다
    es(1).emit('lines', { lines: [{ ts: '2026-11-03T01:00:03.500000000Z', stream: 'err', text: 'late' }] })
    expect(texts()).toEqual(['ts4', 'late'])
  })

  it('onerror: 닫고 1·2·4·8초 뒤 다시, 다섯 번째 실패면 connection-lost', () => {
    mountHost()
    s.connect()
    const delays = [1_000, 2_000, 4_000, 8_000]
    for (let i = 0; i < delays.length; i++) {
      es(i).triggerError()
      expect(es(i).readyState).toBe(CLOSED)
      expect(s.state.value).toBe('reconnecting')
      vi.advanceTimersByTime(delays[i] - 1)
      expect(FakeEventSource.instances).toHaveLength(i + 1)
      vi.advanceTimersByTime(1)
      expect(FakeEventSource.instances).toHaveLength(i + 2)
      es(i + 1).triggerOpen() // 열렸다 끊기기를 되풀이해도 횟수는 처음으로 돌아가지 않는다(판정 [T9])
    }
    es(4).triggerError()
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(5)
    expect(FakeEventSource.instances.every((e) => e.readyState === CLOSED)).toBe(true)
    expect(s.state.value).toBe('ended')
    expect(s.endReason.value).toBe('connection-lost')
    expect(s.endMessage.value).toBeNull()

    // 사용자가 다시 연결하면 횟수가 처음부터다
    s.connect()
    expect(FakeEventSource.instances).toHaveLength(6)
    expect(s.state.value).toBe('connecting')
    expect(s.endReason.value).toBeNull()
    es(5).triggerError()
    vi.advanceTimersByTime(1_000)
    expect(FakeEventSource.instances).toHaveLength(7)
  })

  it('lines를 받으면 오류 재시도 횟수가 처음부터', () => {
    mountHost()
    s.connect()
    es(0).triggerError()
    vi.advanceTimersByTime(1_000)
    es(1).triggerError()
    vi.advanceTimersByTime(2_000)
    es(2).triggerError()
    vi.advanceTimersByTime(4_000)
    expect(FakeEventSource.instances).toHaveLength(4)

    es(3).emit('lines', { lines: [line(1)] })
    es(3).triggerError()
    vi.advanceTimersByTime(999)
    expect(FakeEventSource.instances).toHaveLength(4)
    vi.advanceTimersByTime(1) // 8초가 아니라 1초
    expect(FakeEventSource.instances).toHaveLength(5)
  })

  it('토큰이 없으면 연결하지 않고 auth', () => {
    token = null
    mountHost()
    s.connect()
    expect(FakeEventSource.instances).toHaveLength(0)
    expect(s.state.value).toBe('ended')
    expect(s.endReason.value).toBe('auth')
    expect(s.endMessage.value).toBeNull()
  })

  it('허용 상태가 아니면 연결하지 않고 idle', () => {
    viewable = false
    mountHost()
    s.connect()
    expect(FakeEventSource.instances).toHaveLength(0)
    expect(s.state.value).toBe('idle')
  })

  it('멈춤 중 모은 줄은 재개 때 붙고 pendingCount 0', () => {
    mountHost()
    s.connect()
    es(0).emit('lines', { lines: [line(1)] })
    s.pause()
    expect(s.paused.value).toBe(true)
    es(0).emit('lines', { lines: [line(2), line(3)] })
    expect(texts()).toEqual(['line 1'])
    expect(s.pendingCount.value).toBe(2)

    // 멈춘 사이 다시 연결돼도 받을 때의 cutoff로 거른다 — 재개 때 겹치지 않는다
    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })
    es(1).emit('lines', { lines: [line(2), line(3), line(4)] })
    expect(s.pendingCount.value).toBe(3)

    s.resume()
    expect(s.paused.value).toBe(false)
    expect(texts()).toEqual(['line 1', 'line 2', 'line 3', 'line 4'])
    expect(s.pendingCount.value).toBe(0)
  })

  it('멈춤 중 대기 버퍼는 5000줄까지 — 오래된 줄부터 버린다, clear는 대기 버퍼도 비운다', () => {
    mountHost()
    s.connect()
    s.pause()
    const many = Array.from({ length: 5_003 }, (_, i) => ({
      ts: `2026-11-03T01:00:00.${String(i).padStart(9, '0')}Z`,
      stream: 'out' as const,
      text: `n${i}`,
    }))
    es(0).emit('lines', { lines: many })
    expect(s.pendingCount.value).toBe(5_000)
    s.resume()
    expect(s.lines.value).toHaveLength(5_000)
    expect(s.lines.value[0].text).toBe('n3')

    s.pause()
    es(0).emit('lines', { lines: [line(9)] })
    expect(s.pendingCount.value).toBe(1)
    s.clear()
    expect(s.pendingCount.value).toBe(0)
    expect(s.lines.value).toEqual([])
    s.resume()
    expect(s.lines.value).toEqual([])
  })

  it('언마운트하면 닫고 대기 중인 재연결도 취소한다', () => {
    const w = mountHost()
    s.connect()
    es(0).triggerError()
    expect(vi.getTimerCount()).toBe(1)
    w.unmount()
    wrapper = null
    expect(es(0).readyState).toBe(CLOSED)
    expect(vi.getTimerCount()).toBe(0)
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('언마운트하면 열린 연결을 닫고 visibilitychange 리스너도 뗀다', () => {
    const w = mountHost()
    s.connect()
    visibility = 'hidden'
    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })
    w.unmount()
    wrapper = null
    setVisibility('visible')
    expect(FakeEventSource.instances).toHaveLength(1)

    const w2 = mountHost()
    s.connect()
    w2.unmount()
    wrapper = null
    expect(es(1).readyState).toBe(CLOSED)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('close: 연결과 대기 중인 재연결을 끊고 idle', () => {
    mountHost()
    s.connect()
    es(0).emit('end', { reason: 'container-stopped', message: '컨테이너가 멈췄습니다' })
    s.close()
    expect(vi.getTimerCount()).toBe(0)
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(s.state.value).toBe('idle')

    s.connect()
    es(1).emit('end', { reason: 'no-container', message: '컨테이너가 없어 로그를 볼 수 없습니다' })
    expect(s.state.value).toBe('ended')
    s.close()
    expect(s.state.value).toBe('idle')
    expect(s.endReason.value).toBeNull()
    expect(s.endMessage.value).toBeNull()

    s.connect()
    s.close()
    expect(es(2).readyState).toBe(CLOSED)
    expect(s.state.value).toBe('idle')
  })

  it('닫은 연결에서 늦게 온 이벤트는 무시한다', () => {
    mountHost()
    s.connect()
    s.connect()
    es(0).emit('lines', { lines: [line(1)] })
    es(0).emit('end', { reason: 'not-allowed-status', message: 'x' })
    es(0).triggerError()
    expect(s.lines.value).toEqual([])
    expect(s.state.value).toBe('connecting')
    expect(vi.getTimerCount()).toBe(0)
    expect(FakeEventSource.instances).toHaveLength(2)
  })
})
