import { describe, it, expect } from 'vitest'
import {
  MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort,
} from './modelEffort'

describe('modelEffort', () => {
  it('defaults are opus-4-8 / high', () => {
    expect(DEFAULT_MODEL).toBe('claude-opus-4-8')
    expect(DEFAULT_EFFORT).toBe('high')
  })

  it('exposes the four models', () => {
    expect(MODEL_OPTIONS.map((m) => m.value)).toEqual([
      'claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5',
    ])
  })

  it('haiku allows only low/medium/high; others allow all five', () => {
    expect(effortsForModel('claude-haiku-4-5')).toEqual(['low', 'medium', 'high'])
    expect(effortsForModel('claude-opus-4-8')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
    expect(effortsForModel('claude-sonnet-4-6')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })

  it('coerceEffort keeps a valid effort, downgrades an invalid one to high', () => {
    expect(coerceEffort('claude-opus-4-8', 'max')).toBe('max')
    expect(coerceEffort('claude-haiku-4-5', 'max')).toBe('high')
    expect(coerceEffort('claude-haiku-4-5', 'low')).toBe('low')
  })

  it('unknown model falls back to all five (server validates authoritatively)', () => {
    expect(effortsForModel('whatever')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })
})
