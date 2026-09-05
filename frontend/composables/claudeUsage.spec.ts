import { describe, it, expect } from 'vitest'
import {
  LIMIT_LABELS, LIMIT_ORDER, usageColor, usagePercent, formatReset, formatAgo,
} from './claudeUsage'

const NOW = new Date('2026-09-05T02:30:00Z').getTime()

describe('claudeUsage (스펙 2026-09-05 §2·§3)', () => {
  it('라벨/순서 SSOT', () => {
    expect(LIMIT_ORDER).toEqual(['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet'])
    expect(LIMIT_LABELS.five_hour).toBe('현재 세션 (5시간)')
    expect(LIMIT_LABELS.seven_day).toBe('이번 주 (모든 모델)')
  })

  it('임계 색: 75 미만 primary, 75~89 warning, 90 이상 negative', () => {
    expect(usageColor(0)).toBe('primary')
    expect(usageColor(74)).toBe('primary')
    expect(usageColor(75)).toBe('warning')
    expect(usageColor(89)).toBe('warning')
    expect(usageColor(90)).toBe('negative')
    expect(usageColor(100)).toBe('negative')
  })

  it('usagePercent: 분수→정수 %, 0..100 클램프, resetsAt 경과 시 0', () => {
    expect(usagePercent({ utilization: 0.42, resetsAt: '2026-09-05T04:00:00Z' }, NOW)).toBe(42)
    expect(usagePercent({ utilization: 1.7, resetsAt: null }, NOW)).toBe(100)
    expect(usagePercent({ utilization: 0.9, resetsAt: '2026-09-05T02:00:00Z' }, NOW)).toBe(0)
  })

  it('formatReset: 당일은 시각만, 다른 날은 날짜 포함, 경과는 초기화됨, 없음은 빈 문자열 (로컬 시간대·ICU 버전 무관)', () => {
    const localNow = new Date(2026, 8, 5, 3, 0).getTime() // 로컬 2026-09-05 03:00
    expect(formatReset(new Date(2026, 8, 5, 7, 0).toISOString(), localNow)).toBe('오전 7시 초기화')
    const otherDay = formatReset(new Date(2026, 8, 8, 9, 0).toISOString(), localNow)
    expect(otherDay).toContain('9월 8일')
    expect(otherDay).toContain('오전 9시 초기화')
    expect(formatReset(new Date(2026, 8, 5, 2, 0).toISOString(), localNow)).toBe('초기화됨 · 다음 사용 시 갱신')
    expect(formatReset(null, localNow)).toBe('')
    expect(formatReset('garbage', localNow)).toBe('')
  })

  it('formatAgo: 1분 미만 방금, 분/시간/일 단위', () => {
    expect(formatAgo('2026-09-05T02:29:30Z', NOW)).toBe('방금 갱신')
    expect(formatAgo('2026-09-05T02:27:00Z', NOW)).toBe('3분 전 갱신')
    expect(formatAgo('2026-09-05T00:30:00Z', NOW)).toBe('2시간 전 갱신')
    expect(formatAgo('2026-09-03T02:30:00Z', NOW)).toBe('2일 전 갱신')
    expect(formatAgo(null, NOW)).toBe('')
  })
})
