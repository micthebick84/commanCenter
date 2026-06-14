import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ChatBubble from './ChatBubble.vue'

describe('ChatBubble', () => {
  it('renders an assistant bubble with the AI avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'assistant', content: '안녕하세요' } })
    expect(w.text()).toContain('안녕하세요')
    expect(w.find('.bubble.assistant').exists()).toBe(true)
    expect(w.find('.avatar.assistant').text()).toBe('AI')
  })

  it('renders a user bubble on the right with the 나 avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'user', content: '네 맞아요' } })
    expect(w.find('.bubble.user').exists()).toBe(true)
    expect(w.find('.avatar.user').text()).toBe('나')
    expect(w.find('.bubble-row.user').exists()).toBe(true)
  })

  it('renders a system turn as a centered note without an avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'system', content: '인터뷰를 시작합니다' } })
    expect(w.find('.system-note').text()).toBe('인터뷰를 시작합니다')
    expect(w.find('.avatar').exists()).toBe(false)
  })
})
