import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import AppLogCard from './AppLogCard.vue'
import { FakeEventSource, CLOSED } from '../../test/mocks/eventsource'
import { authStub } from '../../test/mocks/nuxt'
import { appLogFileName, formatLogTime } from '../../composables/appLog'

// 실제 useAppLogStream을 그대로 쓰고 FakeEventSource(test/setup.ts가 전역에 설치)로 구동한다 — 카드가 연결·줄·끝남을 어떻게 그리는지만 본다.
// 재연결 타이머와 Date만 페이크로 돌린다(nextTick은 프라미스 마이크로태스크라 그대로 돈다).
let wrapper: VueWrapper | null = null
let visibility: 'visible' | 'hidden'

const ts = (sec: number) => `2026-11-03T01:00:${String(sec).padStart(2, '0')}.000000000Z`
const line = (sec: number, text = `line ${sec}`, stream: 'out' | 'err' = 'out') => ({ ts: ts(sec), stream, text })
const es = (i: number) => FakeEventSource.instances[i]

function mountCard(props: Partial<{ taskId: number; viewable: boolean }> = {}) {
  wrapper = mount(AppLogCard, { props: { taskId: 7, viewable: true, ...props } })
  return wrapper
}
const rows = (w: VueWrapper) => w.findAll('[data-test="app-log-line"]')
const status = (w: VueWrapper) => w.get('[data-test="app-log-status"]').text()

describe('AppLogCard (스펙 2026-11-03 §5)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    visibility = 'visible'
    wrapper = null
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  })
  afterEach(() => {
    wrapper?.unmount()
    delete (document as { visibilityState?: unknown }).visibilityState
    delete (navigator as { clipboard?: unknown }).clipboard
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('마운트하면 연결, 언마운트하면 닫는다', async () => {
    const w = mountCard()
    await nextTick() // onMounted의 connect()가 바꾼 상태가 그려지도록
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(es(0).url).toBe('/stream/tasks/7/app-logs?access_token=test-token-abc')
    expect(status(w)).toContain('연결 중')
    expect(w.get('[data-test="app-log-lines"]').text()).toContain('로그 대기 중')

    w.unmount()
    wrapper = null
    expect(es(0).readyState).toBe(CLOSED)
  })

  it('언마운트하면 대기 중이던 재연결도 취소한다', async () => {
    const w = mountCard()
    es(0).triggerError() // 1초 뒤 다시 연결할 타이머가 걸린다
    await nextTick()
    expect(status(w)).toContain('다시 연결 중')

    w.unmount()
    wrapper = null
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('[시각] 본문, stderr 표식, 오류·경고 클래스', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1, 'hello'), line(2, 'ERROR boom', 'err'), line(3, 'WARN slow'), line(4, 'plain err', 'err')] })
    await nextTick()

    const r = rows(w)
    expect(r).toHaveLength(4)
    expect(r[0].text()).toBe(`[${formatLogTime(ts(1))}] hello`)
    expect(r[1].text()).toBe(`[${formatLogTime(ts(2))}] ERROR boom`)
    const cls = (i: number) => ({
      error: r[i].classes('app-log-line--error'),
      warn: r[i].classes('app-log-line--warn'),
      err: r[i].classes('app-log-line--err'),
    })
    expect(cls(0)).toEqual({ error: false, warn: false, err: false })
    expect(cls(1)).toEqual({ error: true, warn: false, err: true })
    expect(cls(2)).toEqual({ error: false, warn: true, err: false })
    expect(cls(3)).toEqual({ error: false, warn: false, err: true }) // stderr 표식은 강조 등급과 별개
  })

  it('본문은 HTML로 해석하지 않는다', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1, '<img src=x onerror=alert(1)>')] })
    await nextTick()

    expect(w.find('img').exists()).toBe(false)
    expect(rows(w)[0].text()).toBe(`[${formatLogTime(ts(1))}] <img src=x onerror=alert(1)>`)
    expect(w.html()).toContain('&lt;img src=x onerror=alert(1)&gt;')
  })

  it('오류 줄만 토글', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1, 'started'), line(2, 'SQLException: no such column', 'err'), line(3, 'WARN slow')] })
    await nextTick()
    const toggle = w.get('[data-test="app-log-errors-only"]')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    expect(rows(w)).toHaveLength(3)

    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(rows(w).map((r) => r.text())).toEqual([`[${formatLogTime(ts(2))}] SQLException: no such column`])

    es(0).emit('lines', { lines: [line(4, 'fine'), line(5, 'ERROR again')] }) // 걸러 보는 중에도 새 오류 줄은 붙는다
    await nextTick()
    expect(rows(w)).toHaveLength(2)

    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    expect(rows(w)).toHaveLength(5)
  })

  it('위로 스크롤하면 따라가기가 꺼지고, 켜면 맨 아래로', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1), line(2)] })
    await nextTick()
    const box = w.get('[data-test="app-log-lines"]')
    const el = box.element as HTMLElement
    Object.defineProperty(el, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(el, 'clientHeight', { configurable: true, value: 200 })
    const follow = w.get('[data-test="app-log-follow"]')
    expect(follow.attributes('aria-pressed')).toBe('true') // 기본 켬

    // 새 줄이 오면 바로 맨 아래로 내린다
    el.scrollTop = 0
    es(0).emit('lines', { lines: [line(3)] })
    await nextTick()
    await nextTick()
    expect(el.scrollTop).toBe(1000)

    // 바닥에서 80px 이내(800 - 720)면 켠 채로 둔다
    el.scrollTop = 720
    await box.trigger('scroll')
    expect(follow.attributes('aria-pressed')).toBe('true')

    // 80px을 넘게 올라가면 끈다
    el.scrollTop = 100
    await box.trigger('scroll')
    expect(follow.attributes('aria-pressed')).toBe('false')

    // 꺼진 동안은 줄이 와도 따라 내려가지 않는다
    es(0).emit('lines', { lines: [line(4)] })
    await nextTick()
    await nextTick()
    expect(el.scrollTop).toBe(100)

    // 켜면 맨 아래로
    await follow.trigger('click')
    await nextTick()
    expect(follow.attributes('aria-pressed')).toBe('true')
    expect(el.scrollTop).toBe(1000)
  })

  it('멈춤 중에는 줄이 늘지 않고 재개하면 붙는다', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1)] })
    await nextTick()
    const pause = w.get('[data-test="app-log-pause"]')
    expect(pause.text()).toContain('멈춤')

    await pause.trigger('click')
    expect(pause.attributes('aria-pressed')).toBe('true')
    es(0).emit('lines', { lines: [line(2), line(3)] })
    await nextTick()
    expect(rows(w)).toHaveLength(1)
    expect(pause.text()).toContain('재개')
    expect(pause.text()).toContain('2') // 모아 둔 줄 수

    await pause.trigger('click')
    expect(pause.attributes('aria-pressed')).toBe('false')
    expect(rows(w)).toHaveLength(3)
    expect(pause.text()).toContain('멈춤')
  })

  it('지우기: 줄을 비우고, 다시 연결 뒤 받은 tail 중 이미 본 ts는 다시 나오지 않는다', async () => {
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1, 'seen')] })
    await nextTick()
    expect(rows(w)).toHaveLength(1)

    await w.get('[data-test="app-log-clear"]').trigger('click')
    expect(rows(w)).toHaveLength(0)

    es(0).emit('end', { reason: 'time-limit', message: '30분이 지나 연결을 닫았습니다' })
    expect(FakeEventSource.instances).toHaveLength(2)
    es(1).emit('lines', { lines: [line(1, 'seen'), line(2, 'new')] }) // 서버는 tail부터 다시 보낸다
    await nextTick()
    expect(rows(w)).toHaveLength(1)
    expect(rows(w)[0].text()).toBe(`[${formatLogTime(ts(2))}] new`)
  })

  it('상태 표시: 연결됨·다시 연결 중·끝남 — 사유', async () => {
    const w = mountCard()
    await nextTick()
    expect(status(w)).toContain('연결 중')

    es(0).triggerOpen()
    await nextTick()
    expect(status(w)).toContain('연결됨')

    es(0).emit('info', { message: 'Error response from daemon: 잠시 뒤 다시' })
    await nextTick()
    expect(w.get('[data-test="app-log-info"]').text()).toBe('Error response from daemon: 잠시 뒤 다시')
    expect(rows(w)).toHaveLength(0) // 안내는 줄로 넣지 않는다

    es(0).triggerError()
    await nextTick()
    expect(status(w)).toContain('다시 연결 중')
    expect(status(w)).not.toContain('연결됨')

    vi.advanceTimersByTime(1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    es(1).emit('end', { reason: 'not-allowed-status', message: '지금 상태에서는 앱 로그를 볼 수 없습니다' })
    await nextTick()
    expect(status(w)).toBe('끝남 — 지금 상태에서는 앱 로그를 볼 수 없습니다')
    expect(w.find('[data-test="app-log-info"]').exists()).toBe(false) // 새 연결이 시작되면 이전 안내는 지운다
  })

  it('복사는 보이는 줄만, 클립보드가 없으면 경고', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    // navigator.clipboard는 happy-dom에서 getter만 있는 접근자라 defineProperty로 재정의한다(MarkdownViewerDialog.spec.ts).
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const w = mountCard()
    es(0).emit('lines', { lines: [line(1, 'started'), line(2, 'ERROR x', 'err'), line(3, 'WARN y')] })
    await nextTick()
    await w.get('[data-test="app-log-errors-only"]').trigger('click') // 보이는 줄 = 오류 줄 하나
    const notify = vi.spyOn((w.vm as any).$q, 'notify')

    await w.get('[data-test="app-log-copy"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith(`[${ts(2)}] ERROR x\n`)
    expect(notify).toHaveBeenCalledWith({ type: 'positive', message: '복사했습니다' })

    // 클립보드 API가 없으면(LAN의 http 주소) 경고만
    notify.mockClear()
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    await w.get('[data-test="app-log-copy"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(notify).toHaveBeenCalledWith({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })

    // 쓰기가 거절돼도 경고
    notify.mockClear()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true })
    await w.get('[data-test="app-log-copy"]').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })
  })

  it('줄이 없으면 복사·내려받기는 막힌다', async () => {
    const w = mountCard()
    expect(w.get('[data-test="app-log-copy"]').attributes('disabled')).toBeDefined()
    expect(w.get('[data-test="app-log-download"]').attributes('disabled')).toBeDefined()
    es(0).emit('lines', { lines: [line(1)] })
    await nextTick()
    expect(w.get('[data-test="app-log-copy"]').attributes('disabled')).toBeUndefined()
    expect(w.get('[data-test="app-log-download"]').attributes('disabled')).toBeUndefined()
  })

  it('내려받기 파일명과 내용', async () => {
    vi.setSystemTime(new Date(2026, 10, 4, 9, 5, 7))
    let saved: Blob | null = null
    const createObjectURL = vi.fn((b: Blob) => {
      saved = b
      return 'blob:applog'
    })
    const revokeObjectURL = vi.fn()
    const orig = { c: URL.createObjectURL, r: URL.revokeObjectURL }
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    let anchor: HTMLAnchorElement | null = null
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      anchor = this
    })
    try {
      const w = mountCard()
      es(0).emit('lines', { lines: [line(1, 'started'), line(2, 'ERROR x', 'err'), line(3, 'WARN y')] })
      await nextTick()
      await w.get('[data-test="app-log-errors-only"]').trigger('click') // 보이는 줄만 내려받는다

      await w.get('[data-test="app-log-download"]').trigger('click')
      expect(createObjectURL).toHaveBeenCalledTimes(1)
      expect(anchor!.download).toBe('task-7-app-20261104-090507.log')
      expect(anchor!.download).toBe(appLogFileName(7, new Date()))
      expect(await saved!.text()).toBe(`[${ts(2)}] ERROR x\n`)
      vi.advanceTimersByTime(0)
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:applog')
    } finally {
      Object.assign(URL, { createObjectURL: orig.c, revokeObjectURL: orig.r })
    }
  })

  it('끝남 사유와 다시 연결 버튼 — 누르면 새 연결', async () => {
    const w = mountCard()
    expect(w.find('[data-test="app-log-reconnect"]').exists()).toBe(false)
    es(0).emit('lines', { lines: [line(1, 'kept')] })
    es(0).emit('end', { reason: 'no-container', message: '컨테이너가 없어 로그를 볼 수 없습니다' })
    await nextTick()
    expect(status(w)).toBe('끝남 — 컨테이너가 없어 로그를 볼 수 없습니다')
    expect(es(0).readyState).toBe(CLOSED)
    expect(rows(w)).toHaveLength(1) // 끝나도 받은 줄은 남는다

    await w.get('[data-test="app-log-reconnect"]').trigger('click')
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(es(1).url).toBe('/stream/tasks/7/app-logs?access_token=test-token-abc')
    expect(status(w)).toContain('연결 중')
    expect(w.find('[data-test="app-log-reconnect"]').exists()).toBe(false)

    es(1).emit('lines', { lines: [line(1, 'kept'), line(2, 'after')] }) // 이미 본 줄은 다시 나오지 않는다
    await nextTick()
    expect(rows(w)).toHaveLength(2)
  })

  it('클라이언트가 만든 끝남 사유(연결 끊김·로그인 필요)도 문구로 보인다', async () => {
    const w = mountCard()
    for (let i = 0; i < 4; i++) {
      es(i).triggerError()
      vi.advanceTimersByTime(1_000 * 2 ** i)
    }
    es(4).triggerError()
    await nextTick()
    expect(status(w)).toBe('끝남 — 연결이 끊겼습니다')
    wrapper?.unmount()

    authStub.accessToken = null as unknown as string
    const w2 = mountCard()
    await nextTick()
    expect(FakeEventSource.instances).toHaveLength(5) // 토큰이 없으면 연결하지 않는다
    expect(status(w2)).toBe('끝남 — 다시 로그인한 뒤 연결하세요')
    expect(w2.find('[data-test="app-log-reconnect"]').exists()).toBe(true)
  })

  it('소스에 v-html이 없다', () => {
    const src = readFileSync(resolve(__dirname, 'AppLogCard.vue'), 'utf8')
    expect(src).not.toContain('v-html')
  })
})
