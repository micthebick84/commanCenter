import { describe, it, expect } from 'vitest'
import {
  PLAN_FIX_PLACEHOLDER,
  PLAN_FIX_SEND,
  SIMPLE_INTERVIEW_CAPTION,
  SIMPLE_INTERVIEW_LABEL,
  SUPERPOWERS_TOGGLE_LABEL,
  canRequestPlanFix,
  defaultSuperpowers,
  lastSessionSuperpowers,
} from './interviewMode'

describe('interviewMode (스펙 2026-11-03 간단 인터뷰 §6)', () => {
  it('DB_WORK만 끔 — 다른 타입·타입 없음은 켬', () => {
    expect(defaultSuperpowers('DB_WORK')).toBe(false)
    for (const t of ['BUG_FIX', 'NEW_FEATURE', 'FEATURE_CHANGE', 'QA', null, undefined, '']) {
      expect(defaultSuperpowers(t)).toBe(true)
    }
  })

  it('지난 세션 값은 boolean일 때만 — 칸이 없거나 null이거나 행이 없으면 null(타입 기본값으로 간다)', () => {
    expect(lastSessionSuperpowers({ superpowersEnabled: false })).toBe(false)
    expect(lastSessionSuperpowers({ superpowersEnabled: true })).toBe(true)
    expect(lastSessionSuperpowers({})).toBeNull()
    expect(lastSessionSuperpowers({ superpowersEnabled: null })).toBeNull()
    expect(lastSessionSuperpowers(undefined)).toBeNull()
  })

  it('canRequestPlanFix — 플랜완료 + 간단 인터뷰(false) + 질문 세션이 아닐 때만', () => {
    expect(canRequestPlanFix('PLAN_READY', false, false)).toBe(true)
    // 켬·모름(칸 없음·null)은 지금처럼 막힌다
    expect(canRequestPlanFix('PLAN_READY', undefined, false)).toBe(false)
    expect(canRequestPlanFix('PLAN_READY', null, false)).toBe(false)
    expect(canRequestPlanFix('PLAN_READY', true, false)).toBe(false)
    // 다른 상태
    expect(canRequestPlanFix('AWAITING_INPUT', false, false)).toBe(false)
    expect(canRequestPlanFix(undefined, false, false)).toBe(false)
    expect(canRequestPlanFix(null, false, false)).toBe(false)
    // 질문 세션은 이 값과 무관하다
    expect(canRequestPlanFix('PLAN_READY', false, true)).toBe(false)
  })

  it('문구는 스펙 그대로다', () => {
    expect(SUPERPOWERS_TOGGLE_LABEL).toBe('superpowers 인터뷰')
    expect(SIMPLE_INTERVIEW_LABEL).toBe('간단 인터뷰')
    expect(SIMPLE_INTERVIEW_CAPTION).toBe('간단 인터뷰 — 질문을 최대 한 번 모아서 묻고 바로 계획')
    expect(PLAN_FIX_SEND).toBe('계획 고쳐 달라기')
    expect(PLAN_FIX_PLACEHOLDER).toBe('계획에서 고칠 점을 적어 주세요 — 질문 없이 고친 계획을 다시 냅니다')
  })
})
