import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ImplementationModePicker from './ImplementationModePicker.vue'

describe('ImplementationModePicker (스펙 2026-10-15 §7)', () => {
  it('세 방식을 보여 주고 고른 방식의 설명을 붙인다', () => {
    const w = mount(ImplementationModePicker, { props: { modelValue: 'REVIEW' } })
    for (const v of ['DIRECT', 'REVIEW', 'SDD']) {
      expect(w.find(`[data-test="impl-mode-${v}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="impl-mode-description"]').text()).toContain('Opus 리뷰 1회')
  })

  it('누르면 update:modelValue를 낸다', async () => {
    const w = mount(ImplementationModePicker, { props: { modelValue: 'REVIEW' } })
    await w.find('[data-test="impl-mode-SDD"]').trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['SDD'])
  })
})
