import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TypingIndicator from './TypingIndicator.vue'

describe('TypingIndicator', () => {
  it('renders three dots with a status role and default aria-label', () => {
    const w = mount(TypingIndicator)
    expect(w.attributes('role')).toBe('status')
    expect(w.attributes('aria-label')).toBe('AI가 응답을 준비 중')
    expect(w.findAll('.dot')).toHaveLength(3)
  })

  it('uses a custom label when provided', () => {
    const w = mount(TypingIndicator, { props: { label: '분석 중' } })
    expect(w.attributes('aria-label')).toBe('분석 중')
  })
})
