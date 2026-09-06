import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import QuestionComposer from './QuestionComposer.vue'
import ModelEffortPicker from './ModelEffortPicker.vue'

function mountComposer(props: Record<string, unknown> = {}) {
  return mount(QuestionComposer, {
    props: {
      canSend: true, model: 'claude-opus-5', effort: 'high', modelValue: '질문', ...props,
    },
  })
}

describe('QuestionComposer (스펙 2026-09-05 §3·§6)', () => {
  it('Enter는 send, Shift+Enter는 줄바꿈(전송 아님), IME 조합 중 Enter는 무시', async () => {
    const w = mountComposer()
    const ta = w.find('textarea')
    await ta.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(w.emitted('send')).toHaveLength(1)
    // Safari: IME 조합 완료 Enter가 isComposing=false로 오지만 keyCode=229를 남기는 경우가 있다
    await ta.trigger('keydown', { key: 'Enter', keyCode: 229 })
    expect(w.emitted('send')).toHaveLength(1)
    w.unmount()
  })

  it('canSend=false면 Enter도 버튼도 전송하지 않는다', async () => {
    const w = mountComposer({ canSend: false })
    await w.find('textarea').trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toBeUndefined()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('픽커는 대화 중에도 활성이며 모델을 바꾸면 update:model(+강등된 update:effort)로 올라간다', async () => {
    // 스펙 2026-09-05 §2 개정: 모델·effort는 세션 생성 시 고정이 아니라 다음 질문에 쓸 값이다.
    const w = mountComposer({ effort: 'max' })
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeUndefined()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(w.emitted('update:model')![0]).toEqual(['claude-haiku-4-5'])
    expect(w.emitted('update:effort')![0]).toEqual(['high']) // Haiku는 max 미지원 → 강등
    w.unmount()
  })

  it('disabled(입력 대기 아님)면 입력과 픽커를 함께 막고, sending 중에도 픽커를 잠근다', () => {
    const w = mountComposer({ disabled: true })
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('textarea').attributes('disabled')).toBeDefined()
    w.unmount()
    const w2 = mountComposer({ sending: true })
    expect(w2.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    w2.unmount()
  })

  it('입력은 v-model로 올라가고 hint 문구를 보여준다', async () => {
    const w = mountComposer({ modelValue: '' })
    await w.find('textarea').setValue('새 질문')
    expect(w.emitted('update:modelValue')![0]).toEqual(['새 질문'])
    expect(w.text()).toContain('Enter 전송 · Shift+Enter 줄바꿈')
    w.unmount()
  })
})
