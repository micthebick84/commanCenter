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

  it('assistant 말풍선은 마크다운을 렌더하고 원시 HTML은 이스케이프한다 (스펙 2026-09-05 §2)', () => {
    const w = mount(ChatBubble, {
      props: { role: 'assistant', content: '**요약** `AuthController`\n\n<script>x</script>' },
    })
    expect(w.find('.bubble.assistant strong').text()).toBe('요약')
    expect(w.find('.bubble.assistant code').text()).toBe('AuthController')
    expect(w.html()).not.toContain('<script>')
    expect(w.text()).toContain('<script>x</script>')
  })

  it('user 말풍선은 마크다운을 해석하지 않고 평문으로 보여준다', () => {
    const w = mount(ChatBubble, { props: { role: 'user', content: '**굵게** 아님' } })
    expect(w.find('.bubble.user').text()).toBe('**굵게** 아님')
    expect(w.find('.bubble.user strong').exists()).toBe(false)
  })
})
