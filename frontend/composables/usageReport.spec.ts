import { describe, it, expect } from 'vitest'
import {
  chipText,
  defaultFeeStart,
  formatRatio,
  formatTokens,
  formatUsd,
  isStale,
  periodLabel,
  shiftDate,
  summaryUrl,
  csvUrl,
  totalTokens,
  type UsageSummary,
} from './usageReport'

const ZERO = { input: 0, output: 0, cacheWrite5m: 0, cacheWrite1h: 0, cacheRead: 0 }

function month(over: Partial<UsageSummary> = {}): UsageSummary {
  return {
    period: 'month',
    date: '2026-11-04',
    start: '2026-11-01T00:00:00+09:00',
    end: '2026-12-01T00:00:00+09:00',
    countFrom: '2026-10-01',
    inProgress: true,
    tokens: ZERO,
    cost: 1234,
    byCategory: [],
    byModel: [],
    feeConfigured: true,
    feeMissing: false,
    feeShare: 200,
    fullPeriodFeeShare: 200,
    ratio: 6.24,
    previous: null,
    series: [],
    coverage: { collectors: [], earliestEventAt: null, unpricedModels: [] },
    ...over,
  }
}

describe('usageReport 표시 규칙', () => {
  it('금액·배수·토큰', () => {
    expect(formatUsd(1234.4)).toBe('$1,234')
    expect(formatUsd(99.994)).toBe('$99.99')
    expect(formatUsd(0)).toBe('$0.00')
    expect(formatUsd(null)).toBe('—')
    expect(formatRatio(6.24)).toBe('6.2배')
    expect(formatRatio(null)).toBeNull()
    expect(formatTokens(1234567)).toBe('1.23M')
    expect(formatTokens(45600)).toBe('45.6K')
    expect(formatTokens(789)).toBe('789')
  })

  it('칩 문구', () => {
    expect(chipText(month({ cost: 1234, ratio: 6.24 }))).toBe('이번 달(지금까지) $1,234 · 구독료의 6.2배')
    expect(chipText(month({ ratio: null, feeConfigured: false }))).toBe('이번 달(지금까지) $1,234 · 구독료를 입력하세요')
    expect(chipText(month({ ratio: null, feeConfigured: true, feeMissing: true }))).toBe(
      '이번 달(지금까지) $1,234 · 구독료 적용 전 기간 포함',
    )
    expect(chipText(month({ cost: 0, ratio: null, feeConfigured: true, feeMissing: false }))).toBe('이번 달(지금까지) $0.00')
    expect(chipText(null)).toBe('Claude 사용량 —')
  })

  it('토큰 합', () => {
    expect(totalTokens({ input: 1, output: 2, cacheWrite5m: 3, cacheWrite1h: 4, cacheRead: 5 })).toBe(15)
    expect(totalTokens(null)).toBe(0)
  })

  it('기간 이름', () => {
    expect(periodLabel(month({ period: 'day' }))).toBe('오늘(지금까지)')
    expect(periodLabel(month({ period: 'week' }))).toBe('이번 주(지금까지)')
    expect(
      periodLabel(
        month({ period: 'week', inProgress: false, start: '2026-10-26T00:00:00+09:00', end: '2026-11-02T00:00:00+09:00' }),
      ),
    ).toBe('2026-10-26 ~ 2026-11-01')
    expect(periodLabel(month({ inProgress: false, start: '2026-10-01T00:00:00+09:00' }))).toBe('2026-10')
    expect(periodLabel(month({ period: 'day', inProgress: false, start: '2026-10-31T00:00:00+09:00' }))).toBe('2026-10-31')
  })

  it('날짜 옮기기는 시간대와 무관', () => {
    expect(shiftDate('month', '2026-03-01', -1)).toBe('2026-02-01')
    expect(shiftDate('month', '2026-12-01', 1)).toBe('2027-01-01')
    expect(shiftDate('week', '2026-11-02', 1)).toBe('2026-11-09')
    expect(shiftDate('day', '2026-03-01', -1)).toBe('2026-02-28')
  })

  it('구독료 시작일 기본값', () => {
    expect(
      defaultFeeStart(month({ coverage: { collectors: [], earliestEventAt: '2026-10-03T09:12:00+09:00', unpricedModels: [] } })),
    ).toBe('2026-10-01')
    expect(defaultFeeStart(month({ countFrom: '2026-10-01' }))).toBe('2026-10-01')
  })

  it('isStale은 분 단위 임계', () => {
    const now = new Date('2026-11-04T12:00:00Z').getTime()
    expect(isStale('2026-11-04T10:00:00Z', now)).toBe(true)
    expect(isStale('2026-11-04T11:30:00Z', now)).toBe(false)
    expect(isStale('2026-11-04T11:30:00Z', now, 20)).toBe(true)
    expect(isStale(null, now)).toBe(false)
  })

  it('URL', () => {
    expect(summaryUrl('month')).toBe('/api/usage/summary?period=month')
    expect(summaryUrl('week', '2026-10-26')).toBe('/api/usage/summary?period=week&date=2026-10-26')
    expect(csvUrl('month', '2026-11-01')).toBe('/api/usage/summary.csv?period=month&date=2026-11-01')
  })
})
