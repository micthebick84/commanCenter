import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import ModelEffortPicker from './ModelEffortPicker.vue'

// v-model 2개(model/effort)를 실제로 갱신하는 호스트 — 픽커의 강등 동작을 관찰한다.
function host(initialModel = 'claude-opus-5', initialEffort = 'max', disabled = false) {
  const model = ref(initialModel)
  const effort = ref(initialEffort)
  const Host = defineComponent({
    setup: () => () =>
      h(ModelEffortPicker, {
        model: model.value,
        effort: effort.value,
        disabled,
        'onUpdate:model': (v: string) => (model.value = v),
        'onUpdate:effort': (v: string) => (effort.value = v),
      }),
  })
  return { w: mount(Host), model, effort }
}

describe('ModelEffortPicker (스펙 2026-09-05 §2·§5.4)', () => {
  it('모델 짧은 라벨과 effort 세그먼트 5개를 그린다', () => {
    const { w } = host()
    expect(w.find('[data-test="model-picker"]').text()).toContain('Opus 5')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.findAll('button')).toHaveLength(5)
    expect(toggle.text()).toContain('xhigh')
    expect(toggle.text()).toContain('max')
    w.unmount()
  })

  it('Haiku로 바꾸면 xhigh/max가 사라지고 effort는 high로 강등되며 힌트를 보여준다', async () => {
    const { w, model, effort } = host()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(model.value).toBe('claude-haiku-4-5')
    expect(effort.value).toBe('high')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.findAll('button')).toHaveLength(3)
    expect(toggle.text()).not.toContain('xhigh')
    expect(w.text()).toContain('Haiku는 low/medium/high만 지원')
    w.unmount()
  })

  it('disabled면 모델·effort 컨트롤 모두 비활성', () => {
    const { w } = host('claude-sonnet-5', 'high', true)
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="effort-toggle"] button').attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
