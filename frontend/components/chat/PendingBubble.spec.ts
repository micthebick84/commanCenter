import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import PendingBubble from './PendingBubble.vue'

const base = { activities: [], narration: '', thinking: '' }

describe('PendingBubble', () => {
  it('최신 도구 활동 라인을 표시한다 (label · detail)', () => {
    const w = mount(PendingBubble, {
      props: {
        pending: {
          ...base,
          activities: [
            { label: 'Grep', detail: 'auth' },
            { label: 'Read', detail: 'src/pages/login.vue' },
          ],
        },
      },
    })
    const latest = w.find('[data-test="activity-latest"]')
    expect(latest.text()).toContain('Read')
    expect(latest.text()).toContain('src/pages/login.vue')
    w.unmount()
  })

  it('thinking은 기본 접힘 — 토글 클릭 시 펼침', async () => {
    const w = mount(PendingBubble, {
      props: { pending: { ...base, thinking: 'auth flow부터 확인…' } },
    })
    expect(w.find('[data-test="thinking-toggle"]').exists()).toBe(true)
    expect(w.find('[data-test="thinking-text"]').exists()).toBe(false) // 기본 접힘
    await w.find('[data-test="thinking-toggle"]').trigger('click')
    expect(w.find('[data-test="thinking-text"]').text()).toContain('auth flow부터 확인…')
    w.unmount()
  })

  it('narration 미리보기를 렌더하고, 빈 섹션(thinking/활동/내레이션 없음)은 그리지 않는다', () => {
    const w = mount(PendingBubble, {
      props: { pending: { ...base, narration: '레포를 먼저 읽겠습니다' } },
    })
    expect(w.find('[data-test="narration"]').text()).toBe('레포를 먼저 읽겠습니다')
    expect(w.find('[data-test="thinking-toggle"]').exists()).toBe(false)
    expect(w.find('[data-test="activity-latest"]').exists()).toBe(false)
    w.unmount()
  })
})
