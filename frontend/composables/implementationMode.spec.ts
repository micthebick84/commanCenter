import { describe, it, expect } from 'vitest'
import {
  DEFAULT_IMPLEMENTATION_MODE,
  IMPLEMENTATION_MODES,
  elapsedLabel,
  implementationModeDescription,
  implementationModeLabel,
  showsImplementationMode,
} from './implementationMode'

describe('implementationMode (스펙 2026-10-15 §7)', () => {
  it('방식 목록·이름·설명·기본값', () => {
    expect(IMPLEMENTATION_MODES.map((m) => m.value)).toEqual(['DIRECT', 'REVIEW', 'SDD'])
    expect(DEFAULT_IMPLEMENTATION_MODE).toBe('REVIEW')
    expect(implementationModeLabel('REVIEW')).toBe('리뷰')
    expect(implementationModeLabel('SDD')).toBe('SDD')
    expect(implementationModeLabel(undefined)).toBe('직접')
    expect(implementationModeDescription('SDD')).toContain('최대 4시간')
  })

  it('확정 이후 상태에서만 방식을 보여 준다', () => {
    expect(showsImplementationMode('APPROVED')).toBe(true)
    expect(showsImplementationMode('DESIGN_PENDING')).toBe(true)
    expect(showsImplementationMode('CLOSED')).toBe(true)
    expect(showsImplementationMode('INTERVIEW_REVIEW')).toBe(false)
    expect(showsImplementationMode('AWAITING_APPROVAL')).toBe(false)
  })

  it('경과 문구 — 구현중·시작 시각·마운트 뒤에만, 상한의 80%부터 경고', () => {
    const now = Date.parse('2026-10-15T10:30:00Z')
    const base = { status: 'IMPLEMENTING', claimedAt: '2026-10-15T10:00:00Z', implementationTimeoutMinutes: 80 }
    expect(elapsedLabel(base, now)).toEqual({ text: '30분 경과 / 상한 80분', warn: false })
    expect(elapsedLabel({ ...base, implementationTimeoutMinutes: 36 }, now)?.warn).toBe(true)
    expect(elapsedLabel({ ...base, implementationTimeoutMinutes: 38 }, now)?.warn).toBe(false)
    expect(elapsedLabel(base, null)).toBeNull()
    expect(elapsedLabel({ ...base, status: 'PR_CREATED' }, now)).toBeNull()
    expect(elapsedLabel({ ...base, claimedAt: null }, now)).toBeNull()
  })

  it('상한이 없는 구현중 작업(새 API 전에 시작)은 경과만 보여 준다', () => {
    const now = Date.parse('2026-10-15T10:05:00Z')
    expect(elapsedLabel({ status: 'IMPLEMENTING', claimedAt: '2026-10-15T10:00:00Z', implementationTimeoutMinutes: null }, now))
      .toEqual({ text: '5분 경과', warn: false })
  })
})
