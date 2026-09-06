import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import ModelEffortFields from './ModelEffortFields.vue'

// v-model 2개(model/effort)를 실제로 갱신하는 호스트 — ModelEffortPicker.spec 선례.
function host(
  initialModel = 'claude-opus-5',
  initialEffort = 'high',
  disabled = false,
) {
  const model = ref(initialModel)
  const effort = ref(initialEffort)
  const Host = defineComponent({
    setup: () => () =>
      h(ModelEffortFields, {
        model: model.value,
        effort: effort.value,
        disabled,
        'onUpdate:model': (v: string) => (model.value = v),
        'onUpdate:effort': (v: string) => (effort.value = v),
      }),
  })
  return { w: mount(Host), model, effort }
}

describe('ModelEffortFields (승인 다이얼로그 — 모델 라디오 + 추론 단계 세그먼트 + 설명)', () => {
  it('모델 3개를 이름·한 줄 설명과 함께 라디오 행으로 그리고, 선택된 행을 표시한다', () => {
    const { w } = host()
    const rows = w.findAll('[data-test^="model-row-"]')
    expect(rows.map((r) => r.attributes('data-test'))).toEqual([
      'model-row-claude-opus-5',
      'model-row-claude-sonnet-5',
      'model-row-claude-haiku-4-5',
    ])
    expect(rows[0]!.text()).toContain('Opus 5')
    expect(rows[2]!.text()).toContain('Haiku 4.5')
    expect(rows[2]!.text()).toContain('low')
    expect(rows[0]!.attributes('aria-checked')).toBe('true')
    expect(rows[1]!.attributes('aria-checked')).toBe('false')
    w.unmount()
  })

  it('추론 단계는 세그먼트 5개와 선택값 설명 한 줄을 보여준다', () => {
    const { w } = host()
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.findAll('button')).toHaveLength(5)
    expect(w.find('[data-test="effort-desc"]').text()).toContain('기본')
    w.unmount()
  })

  it('세그먼트를 누르면 effort v-model이 바뀌고 설명이 따라간다', async () => {
    const { w, effort } = host()
    const max = w
      .findAll('[data-test="effort-toggle"] button')
      .find((b) => b.text() === 'max')!
    await max.trigger('click')
    expect(effort.value).toBe('max')
    expect(w.find('[data-test="effort-desc"]').text()).not.toContain('기본')
    w.unmount()
  })

  it('모델 행을 누르면 model v-model이 바뀌고, Haiku면 effort가 high로 강등되며 세그먼트 3개 + 힌트', async () => {
    const { w, model, effort } = host('claude-opus-5', 'max')
    await w.find('[data-test="model-row-claude-haiku-4-5"]').trigger('click')
    expect(model.value).toBe('claude-haiku-4-5')
    expect(effort.value).toBe('high')
    expect(w.findAll('[data-test="effort-toggle"] button')).toHaveLength(3)
    expect(w.text()).toContain('Haiku는 low/medium/high만 지원')
    w.unmount()
  })

  it('라디오 접근성: 선택된 행만 탭 순서에 들어가고 ArrowDown/ArrowUp으로 선택이 이동한다 (리뷰 파인딩 8)', async () => {
    const { w, model } = host()
    const rows = w.findAll('[data-test^="model-row-"]')
    expect(rows[0]!.attributes('tabindex')).toBe('0')
    expect(rows[1]!.attributes('tabindex')).toBe('-1')
    await rows[0]!.trigger('keydown', { key: 'ArrowDown' })
    expect(model.value).toBe('claude-sonnet-5')
    await w.vm.$nextTick()
    const sonnet = w.find('[data-test="model-row-claude-sonnet-5"]')
    expect(sonnet.attributes('tabindex')).toBe('0')
    await sonnet.trigger('keydown', { key: 'ArrowUp' })
    expect(model.value).toBe('claude-opus-5')
    expect(w.find('[data-test="effort-toggle"]').attributes('aria-label')).toBe(
      '추론 단계',
    )
    w.unmount()
  })

  it('disabled면 모델 행 클릭도 세그먼트도 먹지 않는다', async () => {
    const { w, model } = host('claude-sonnet-5', 'high', true)
    await w.find('[data-test="model-row-claude-haiku-4-5"]').trigger('click')
    expect(model.value).toBe('claude-sonnet-5')
    expect(
      w.find('[data-test="effort-toggle"] button').attributes('disabled'),
    ).toBeDefined()
    w.unmount()
  })
})
