import { mount } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { setViewportWidth } from '~/test/mocks/screen'
import { defineComponent, h, ref } from 'vue'
import ModelEffortPicker from './ModelEffortPicker.vue'

// v-model 2개(model/effort)를 실제로 갱신하는 호스트 — 픽커의 강등 동작을 관찰한다.
function host(initialModel = 'claude-opus-5-5', initialEffort = 'max', disabled = false) {
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
    expect(w.find('[data-test="model-picker"]').text()).toContain('Opus 5.5')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.findAll('button')).toHaveLength(5)
    expect(toggle.text()).toContain('xhigh')
    expect(toggle.text()).toContain('max')
    w.unmount()
  })

  it('Haiku로 바꾸면 xhigh/max가 사라지고 effort는 기본값(medium)으로 강등되며 힌트를 보여준다', async () => {
    const { w, model, effort } = host()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(model.value).toBe('claude-haiku-4-5')
    expect(effort.value).toBe('medium')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.findAll('button')).toHaveLength(3)
    expect(toggle.text()).not.toContain('xhigh')
    expect(w.text()).toContain('Haiku는 low/medium/high만 지원')
    w.unmount()
  })

  it('disabled면 모델·effort 컨트롤 모두 비활성', async () => {
    const { w, model } = host('claude-sonnet-5', 'high', true)
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="effort-toggle"] button').attributes('disabled')).toBeDefined()
    // disabled일 때는 노출된 pickModel을 직접 호출해도(예: 테스트/오작동 경로) 모델이 바뀌면 안 된다
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(model.value).toBe('claude-sonnet-5')
    w.unmount()
  })
})

describe('ModelEffortPicker — 좁은 폭 (2026-11-11 인터페이스 검토)', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
  })

  it('lt.md에서는 Haiku 힌트를 툴바에 그리지 않는다(모델 메뉴 캡션에 같은 안내가 있다)', async () => {
    await setViewportWidth(390)
    const { w } = host('claude-haiku-4-5', 'low')
    await w.vm.$nextTick()
    expect(w.find('[data-test="effort-dropdown"]').exists()).toBe(true)
    expect(w.text()).not.toContain('Haiku는 low/medium/high만 지원')
    w.unmount()
  })
})
