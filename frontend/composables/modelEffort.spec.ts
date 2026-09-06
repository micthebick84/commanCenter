import { describe, it, expect } from 'vitest'
import {
  MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort,
  describeModel, describeEffort, shortModelLabel,
} from './modelEffort'

describe('modelEffort', () => {
  it('defaults are opus-5 / high', () => {
    expect(DEFAULT_MODEL).toBe('claude-opus-5')
    expect(DEFAULT_EFFORT).toBe('high')
  })

  it('exposes the three picker models (Fable 제외 — 스펙 2026-09-05 §5.4)', () => {
    expect(MODEL_OPTIONS.map((m) => m.value)).toEqual([
      'claude-opus-5', 'claude-sonnet-5', 'claude-haiku-4-5',
    ])
    expect(MODEL_OPTIONS.find((m) => m.value === 'claude-fable-5')).toBeUndefined()
  })

  it('haiku allows only low/medium/high; others allow all five', () => {
    expect(effortsForModel('claude-haiku-4-5')).toEqual(['low', 'medium', 'high'])
    expect(effortsForModel('claude-opus-5')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
    expect(effortsForModel('claude-sonnet-5')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })

  it('coerceEffort keeps a valid effort, downgrades an invalid one to high', () => {
    expect(coerceEffort('claude-opus-5', 'max')).toBe('max')
    expect(coerceEffort('claude-haiku-4-5', 'max')).toBe('high')
    expect(coerceEffort('claude-haiku-4-5', 'low')).toBe('low')
    // ultracode는 목록에 없다(헤드리스에서 xhigh와 무차이) → 골라도 high로 강등
    expect(coerceEffort('claude-opus-5', 'ultracode')).toBe('high')
  })

  it('unknown model falls back to all five (server validates authoritatively)', () => {
    expect(effortsForModel('whatever')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })
})

// 승인 다이얼로그(2026-09-06 UI/UX 개선)가 쓰는 한 줄 설명 — 고를 수 있는 모든 값에 설명이 있어야 한다.
describe('modelEffort descriptions (승인 다이얼로그)', () => {
  it('every selectable model has a one-line description', () => {
    for (const m of MODEL_OPTIONS) {
      expect(describeModel(m.value).length).toBeGreaterThan(0)
    }
    expect(describeModel('claude-haiku-4-5')).toContain('low')
  })

  it('every effort level has a one-line description and high is marked as the default', () => {
    for (const e of effortsForModel('claude-opus-5')) {
      expect(describeEffort(e).length).toBeGreaterThan(0)
    }
    expect(describeEffort('high')).toContain('기본')
  })

  it('unknown values describe as empty string (no crash)', () => {
    expect(describeModel('whatever')).toBe('')
    expect(describeEffort('ultracode')).toBe('')
  })

  it('shortModelLabel strips the "(기본)" suffix and falls back to the raw value', () => {
    expect(shortModelLabel('claude-opus-5')).toBe('Opus 5')
    expect(shortModelLabel('claude-haiku-4-5')).toBe('Haiku 4.5')
    expect(shortModelLabel('claude-fable-5')).toBe('claude-fable-5')
  })
})
