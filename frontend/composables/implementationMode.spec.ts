import { describe, it, expect } from 'vitest'
import {
  DEFAULT_IMPLEMENTATION_MODE,
  IMPLEMENTATION_MODES,
  elapsedLabel,
  implementationModeDescription,
  implementationModeLabel,
  implementationModeText,
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

  it('방식 문구 — 확정 이후에만, 2회째부터 회차를 붙인다', () => {
    expect(implementationModeText({ status: 'IMPLEMENTING', implementationMode: 'REVIEW', implementationAttempt: 1 })).toBe('리뷰')
    expect(implementationModeText({ status: 'IMPLEMENTING', implementationMode: 'SDD', implementationAttempt: 2 })).toBe('SDD · 2회째')
    expect(implementationModeText({ status: 'DEPLOYED', implementationMode: 'DIRECT', implementationAttempt: 3 })).toBe('직접 · 3회째')
    // 확정 전에는 방식·회차 값이 있어도 보여 주지 않는다
    expect(implementationModeText({ status: 'INTERVIEW_REVIEW', implementationMode: 'SDD', implementationAttempt: 2 })).toBeNull()
    expect(implementationModeText({ status: 'AWAITING_APPROVAL', implementationMode: 'REVIEW', implementationAttempt: 1 })).toBeNull()
  })

  it('방식·회차가 응답에 없는 옛 작업은 "직접"(1회째)으로 본다', () => {
    expect(implementationModeText({ status: 'PR_CREATED' })).toBe('직접')
    expect(implementationModeText({ status: 'PR_CREATED', implementationMode: null, implementationAttempt: null })).toBe('직접')
    expect(implementationModeText({ status: 'PR_CREATED', implementationMode: 'UNKNOWN', implementationAttempt: 1 })).toBe('직접')
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

  it('경고 경계 — 정확히 상한의 80%(상한 50분이면 40분)에서 켜지고, 그보다 이르면 꺼져 있다', () => {
    const claimedAt = '2026-10-15T10:00:00Z'
    const task = { status: 'IMPLEMENTING', claimedAt, implementationTimeoutMinutes: 50 }
    const at = (ms: number) => Date.parse(claimedAt) + ms
    expect(elapsedLabel(task, at(39 * 60_000))).toEqual({ text: '39분 경과 / 상한 50분', warn: false })
    expect(elapsedLabel(task, at(40 * 60_000) - 1)).toEqual({ text: '39분 경과 / 상한 50분', warn: false }) // 분은 내림
    expect(elapsedLabel(task, at(40 * 60_000))).toEqual({ text: '40분 경과 / 상한 50분', warn: true })
  })

  it('시계가 어긋나 시작 시각이 미래여도 0분으로 보여 주고 깨지지 않는다', () => {
    const now = Date.parse('2026-10-15T10:00:00Z')
    const task = { status: 'IMPLEMENTING', claimedAt: '2026-10-15T10:00:05Z', implementationTimeoutMinutes: 80 }
    expect(elapsedLabel(task, now)).toEqual({ text: '0분 경과 / 상한 80분', warn: false })
  })

  it('시작 시각을 해석할 수 없으면 경과 줄을 만들지 않는다', () => {
    const now = Date.parse('2026-10-15T10:30:00Z')
    expect(elapsedLabel({ status: 'IMPLEMENTING', claimedAt: 'not-a-date', implementationTimeoutMinutes: 80 }, now)).toBeNull()
  })
})
