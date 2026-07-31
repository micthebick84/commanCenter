import { describe, it, expect } from 'vitest'
import {
  MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort,
} from './modelEffort'

describe('modelEffort', () => {
  it('defaults are opus-5 / high', () => {
    expect(DEFAULT_MODEL).toBe('claude-opus-5')
    expect(DEFAULT_EFFORT).toBe('high')
  })

  it('exposes the four models', () => {
    expect(MODEL_OPTIONS.map((m) => m.value)).toEqual([
      'claude-fable-5', 'claude-opus-5', 'claude-sonnet-5', 'claude-haiku-4-5',
    ])
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
