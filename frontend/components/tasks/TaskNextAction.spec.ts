import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskNextAction from './TaskNextAction.vue'

describe('TaskNextAction (스펙 2026-09-06 §4.2)', () => {
  const action = {
    text: 'PR 생성됨 — 배포할 수 있습니다',
    primary: { label: '배포', icon: 'rocket_launch', kind: 'deploy' as const },
  }
  it('banner: 문구 + 주 행동 버튼, 클릭 시 act(kind)', async () => {
    const w = mount(TaskNextAction, { props: { action, variant: 'banner' } })
    expect(w.find('[data-test="next-action-text"]').text()).toContain(
      '배포할 수 있습니다',
    )
    await w.find('[data-test="next-action-primary"]').trigger('click')
    expect(w.emitted('act')?.[0]).toEqual(['deploy'])
    expect(w.classes()).toContain('next-action--banner')
    w.unmount()
  })
  it('bar: 주 행동 버튼은 44px 이상, primary 없으면 문구만', () => {
    const w = mount(TaskNextAction, {
      props: { action: { text: '진행 중' }, variant: 'bar' },
    })
    expect(w.classes()).toContain('next-action--bar')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    expect(w.text()).toContain('진행 중')
    w.unmount()
  })
  it('action null이면 아무것도 그리지 않는다', () => {
    const w = mount(TaskNextAction, {
      props: { action: null, variant: 'banner' },
    })
    expect(w.find('[data-test="next-action"]').exists()).toBe(false)
    w.unmount()
  })
})
