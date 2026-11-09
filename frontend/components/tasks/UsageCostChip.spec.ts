import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import UsageCostChip from './UsageCostChip.vue'
import { useApiMock } from '../../test/mocks/nuxt'
import { setViewportWidth } from '../../test/mocks/screen'
import type { UsageSummary } from '../../composables/usageReport'

const tokens = { input: 1, output: 1, cacheWrite5m: 0, cacheWrite1h: 0, cacheRead: 0 }
function summary(over: Partial<UsageSummary> = {}): UsageSummary {
  return {
    period: 'month', date: '2026-11-04', start: '2026-11-01T00:00:00+09:00', end: '2026-12-01T00:00:00+09:00',
    countFrom: '2026-10-01', inProgress: true, tokens, cost: 1234, byCategory: [], byModel: [],
    feeConfigured: true, feeMissing: false, feeShare: 200, fullPeriodFeeShare: 1000, ratio: 6.24, previous: null, series: [],
    coverage: { collectors: [], earliestEventAt: null, unpricedModels: [] },
    ...over,
  }
}
const calls = () => useApiMock.mock.calls.map((c) => String(c[0])).filter((u) => u.startsWith('/api/usage/summary?'))
const q = (t: string) => document.body.querySelector(`[data-test="${t}"]`) as HTMLElement | null

function stub(res: (url: string) => unknown) {
  useApiMock.mockImplementation((url: string) => {
    const r = res(String(url))
    return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
  })
}

describe('UsageCostChip', () => {
  beforeEach(() => { vi.useRealTimers() })
  afterEach(async () => { vi.useRealTimers(); await setViewportWidth(1024); document.body.innerHTML = '' })

  it('마운트 때 이번 달 요약을 한 번', async () => {
    stub(() => summary())
    const w = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    expect(calls()).toEqual(['/api/usage/summary?period=month'])
    expect(q('cost-chip')!.textContent).toContain('이번 달(지금까지) $1,234 · 구독료의 6.2배')
    w.unmount()
  })

  it('일·주·월 전환은 다시 부른다', async () => {
    stub((u) => (u.includes('period=day') ? summary({ period: 'day', cost: 12.3, ratio: 0.5 }) : summary()))
    const w = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    const day = Array.from(q('cost-chip-period')!.querySelectorAll('button')).find((b) => b.textContent?.includes('일'))!
    day.click()
    await flushPromises()
    expect(calls().at(-1)).toBe('/api/usage/summary?period=day')
    expect(calls()).toHaveLength(2)
    expect(q('cost-chip')!.textContent).toContain('오늘(지금까지)')
    w.unmount()
  })

  it('자동 갱신하지 않는다', async () => {
    vi.useFakeTimers()
    stub(() => summary())
    const w = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    const n = calls().length
    vi.advanceTimersByTime(600_000)
    await flushPromises()
    expect(calls()).toHaveLength(n)
    w.unmount()
  })

  it('null·실패면 Claude 사용량 —', async () => {
    stub(() => null)
    const w = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    expect(q('cost-chip')!.textContent).toContain('Claude 사용량 —')
    w.unmount()
    stub(() => new Error('boom'))
    const w2 = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    expect(q('cost-chip')!.textContent).toContain('Claude 사용량 —')
    w2.unmount()
  })

  it('누르면 같은 기간으로 상세가 열리고, 구독료 저장 뒤 다시 부른다', async () => {
    stub((u) => (u.startsWith('/api/usage/summary?') ? summary() : []))
    const w = mount(UsageCostChip, { attachTo: document.body })
    await flushPromises()
    const week = Array.from(q('cost-chip-period')!.querySelectorAll('button')).find((b) => b.textContent?.includes('주'))!
    week.click()
    await flushPromises()
    q('cost-chip')!.click()
    await flushPromises()
    expect(q('cost-dialog')).not.toBeNull()
    expect(calls().at(-1)).toBe('/api/usage/summary?period=week')
    const before = calls().length
    w.findComponent({ name: 'UsageCostDialog' }).vm.$emit('fee-saved')
    await flushPromises()
    expect(calls()).toHaveLength(before + 1)
    w.unmount()
  })
})
