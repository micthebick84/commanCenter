import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import UsageCostDialog from './UsageCostDialog.vue'
import { useApiMock } from '../../test/mocks/nuxt'
import { setViewportWidth } from '../../test/mocks/screen'
import type { UsageSummary } from '../../composables/usageReport'

const tokens = (input: number, output = 0) => ({ input, output, cacheWrite5m: 0, cacheWrite1h: 0, cacheRead: 0 })

function summary(over: Partial<UsageSummary> = {}): UsageSummary {
  return {
    period: 'month',
    date: '2026-11-04',
    start: '2026-11-01T00:00:00+09:00',
    end: '2026-12-01T00:00:00+09:00',
    countFrom: '2026-10-01',
    inProgress: true,
    // 합 1,234,567
    tokens: { input: 1000000, output: 200000, cacheWrite5m: 30000, cacheWrite1h: 4000, cacheRead: 567 },
    cost: 1234,
    byCategory: [
      { category: 'SYMPHONY', tokens: tokens(1), cost: 900 },
      { category: 'DIRECT', tokens: tokens(1), cost: 334 },
    ],
    byModel: [
      { model: 'claude-opus-5-5', tokens: tokens(1000), cost: 1000 },
      { model: 'claude-x-1', tokens: tokens(5), cost: null },
    ],
    feeConfigured: true,
    feeMissing: false,
    feeShare: 200,
    fullPeriodFeeShare: 1000,
    ratio: 6.24,
    previous: { start: '2026-10-01T00:00:00+09:00', until: '2026-10-04T00:00:00+09:00', cost: 500, feeShare: 100, ratio: 5 },
    series: Array.from({ length: 4 }, (_, i) => ({ at: `2026-11-0${i + 1}T00:00:00+09:00`, cost: i * 10 })),
    coverage: {
      collectors: [
        { machine: 'pc-a', lastReportedAt: new Date(Date.now() - 60_000).toISOString(), lastEventAt: null, unreadableLines: 0 },
        { machine: 'pc-b', lastReportedAt: new Date(Date.now() - 2 * 3600_000).toISOString(), lastEventAt: null, unreadableLines: 3 },
        { machine: 'pc-c', lastReportedAt: new Date(Date.now() - 10 * 60_000).toISOString(), lastEventAt: null, unreadableLines: 0 },
      ],
      earliestEventAt: '2026-10-03T09:12:00+09:00',
      unpricedModels: [{ model: 'claude-x-1', tokens: tokens(5) }],
    },
    ...over,
  }
}

const body = () => document.body
const q = (t: string) => body().querySelector(`[data-test="${t}"]`) as HTMLElement | null
const qa = (t: string) => Array.from(body().querySelectorAll(`[data-test="${t}"]`)) as HTMLElement[]

async function open(res: unknown = summary(), initialPeriod: 'day' | 'week' | 'month' = 'month') {
  useApiMock.mockImplementation((url: string) => Promise.resolve(String(url).startsWith('/api/usage/summary?') ? res : []))
  const w = mount(UsageCostDialog, { props: { modelValue: true, initialPeriod }, attachTo: document.body })
  await flushPromises()
  return w
}
const summaryCalls = () => useApiMock.mock.calls.map((c) => String(c[0])).filter((u) => u.startsWith('/api/usage/summary?'))

describe('UsageCostDialog', () => {
  beforeEach(() => useApiMock.mockReset())
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('열면 요약을 한 번 부르고 큰 숫자', async () => {
    const w = await open()
    expect(useApiMock.mock.calls[0][0]).toBe('/api/usage/summary?period=month')
    expect(summaryCalls()).toHaveLength(1)
    expect(q('cost-tokens')!.textContent).toBe('1.23M')
    expect(q('cost-total')!.textContent).toBe('$1,234')
    expect(q('cost-ratio')!.textContent!.trim()).toBe('6.2배')
    expect(q('cost-previous')!.textContent).toContain('직전 같은 구간 $500 · 5.0배')
    expect(body().textContent).toContain('기간 전체 구독료 $1,000')
    w.unmount()
  })

  it('이전·다음은 시작일로 date를 옮긴다', async () => {
    const w = await open()
    expect(q('cost-next')!.hasAttribute('disabled')).toBe(true) // 진행 중
    q('cost-prev')!.click()
    await flushPromises()
    expect(summaryCalls().at(-1)).toBe('/api/usage/summary?period=month&date=2026-10-01')
    w.unmount()
  })

  it('끝난 기간에서는 다음이 열린다', async () => {
    const w = await open(summary({ inProgress: false, start: '2026-10-01T00:00:00+09:00', end: '2026-11-01T00:00:00+09:00' }))
    expect(q('cost-next')!.hasAttribute('disabled')).toBe(false)
    q('cost-next')!.click()
    await flushPromises()
    expect(summaryCalls().at(-1)).toBe('/api/usage/summary?period=month&date=2026-11-01')
    w.unmount()
  })

  it('일·주·월 전환은 date 없이 다시', async () => {
    const w = await open()
    q('cost-prev')!.click()
    await flushPromises()
    const week = Array.from(q('cost-period')!.querySelectorAll('button')).find((b) => b.textContent?.includes('주'))!
    week.click()
    await flushPromises()
    expect(summaryCalls().at(-1)).toBe('/api/usage/summary?period=week')
    w.unmount()
  })

  it('범주 막대·모델 표·기간 막대', async () => {
    const w = await open()
    const segs = qa('cost-category-bar')
    expect(segs).toHaveLength(2)
    expect(segs[0].style.width).toMatch(/%$/)
    expect(qa('cost-model-row')).toHaveLength(2)
    expect(qa('cost-series-bar')).toHaveLength(4)
    w.unmount()
    document.body.innerHTML = ''
    const d = await open(
      summary({ period: 'day', series: Array.from({ length: 24 }, (_, h) => ({ at: `2026-11-04T${String(h).padStart(2, '0')}:00:00+09:00`, cost: h })) }),
      'day',
    )
    expect(qa('cost-series-bar')).toHaveLength(24)
    d.unmount()
  })

  it('미등록 경고·수집 상태·안내 문구', async () => {
    const w = await open()
    expect(q('cost-unpriced')!.textContent).toContain('claude-x-1')
    const rows = qa('cost-collector-row')
    expect(rows).toHaveLength(3)
    expect(rows[1].classList.contains('text-negative')).toBe(true)
    expect(rows[0].classList.contains('text-negative')).toBe(false)
    expect(rows[1].textContent).toContain('못 읽은 줄 3')
    expect(body().textContent).toContain('가장 이른 기록 2026-10-03 09:12')
    expect(body().textContent).toContain('claude.ai 채팅 제외 · 기록은 Claude Code가 약 30일 보관한 것부터')
    w.unmount()
  })

  it('CSV 내려받기는 blob', async () => {
    const created = vi.fn(() => 'blob:x')
    const revoked = vi.fn()
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked })
    const names: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download)
    })
    const w = await open()
    q('cost-csv')!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/usage/summary.csv?period=month&date=2026-11-01', { responseType: 'blob' })
    expect(names).toEqual(['claude-usage-month-2026-11-01.csv'])
    click.mockRestore()
    w.unmount()
  })

  it('구독료 설정은 열 때만 이력을 부른다', async () => {
    const w = await open()
    expect(useApiMock.mock.calls.some((c) => c[0] === '/api/usage/subscription-fees')).toBe(false)
    q('fee-toggle')!.click()
    await flushPromises()
    expect(useApiMock.mock.calls.filter((c) => c[0] === '/api/usage/subscription-fees')).toHaveLength(1)
    w.unmount()
  })

  it('구독료 저장 뒤 요약을 다시 부르고 fee-saved를 낸다', async () => {
    const w = await open()
    q('fee-toggle')!.click()
    await flushPromises()
    q('cost-prev')!.click()
    await flushPromises()
    const before = summaryCalls().length
    const amount = q('fee-amount') as HTMLInputElement
    amount.value = '200'
    amount.dispatchEvent(new Event('input'))
    await flushPromises()
    q('fee-save')!.click()
    await flushPromises()
    expect(summaryCalls().length).toBe(before + 1)
    expect(summaryCalls().at(-1)).toBe('/api/usage/summary?period=month&date=2026-10-01') // 보던 기간 그대로
    expect(w.emitted('fee-saved')).toHaveLength(1)
    w.unmount()
  })

  it('390px에서는 전체 화면, 닫기 바', async () => {
    await setViewportWidth(390)
    const w = await open()
    expect(body().querySelector('.q-dialog__inner--maximized')).not.toBeNull()
    expect(q('cost-close')!.getAttribute('aria-label')).toBe('닫기')
    w.unmount()
  })

  it('null 응답에도 깨지지 않는다', async () => {
    const w = await open(null)
    expect(q('cost-total')!.textContent).toBe('—')
    expect(q('cost-tokens')!.textContent).toBe('0')
    expect(qa('cost-model-row')).toHaveLength(0)
    w.unmount()
  })
})
