import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ChatBubble from './ChatBubble.vue'

describe('ChatBubble', () => {
  it('renders an assistant bubble with the AI avatar', () => {
    const w = mount(ChatBubble, {
      props: { role: 'assistant', content: '안녕하세요' },
    })
    expect(w.text()).toContain('안녕하세요')
    expect(w.find('.bubble.assistant').exists()).toBe(true)
    expect(w.find('.avatar.assistant').text()).toBe('AI')
  })

  it('renders a user bubble on the right with the 나 avatar', () => {
    const w = mount(ChatBubble, {
      props: { role: 'user', content: '네 맞아요' },
    })
    expect(w.find('.bubble.user').exists()).toBe(true)
    expect(w.find('.avatar.user').text()).toBe('나')
    expect(w.find('.bubble-row.user').exists()).toBe(true)
  })

  it('renders a system turn as a centered note without an avatar', () => {
    const w = mount(ChatBubble, {
      props: { role: 'system', content: '인터뷰를 시작합니다' },
    })
    expect(w.find('.system-note').text()).toBe('인터뷰를 시작합니다')
    expect(w.find('.avatar').exists()).toBe(false)
  })

  it('assistant 말풍선은 마크다운을 렌더하고 원시 HTML은 이스케이프한다 (스펙 2026-09-05 §2)', () => {
    const w = mount(ChatBubble, {
      props: {
        role: 'assistant',
        content: '**요약** `AuthController`\n\n<script>x</script>',
      },
    })
    expect(w.find('.bubble.assistant strong').text()).toBe('요약')
    expect(w.find('.bubble.assistant code').text()).toBe('AuthController')
    expect(w.html()).not.toContain('<script>')
    expect(w.text()).toContain('<script>x</script>')
  })

  it('user 말풍선은 마크다운을 해석하지 않고 평문으로 보여준다', () => {
    const w = mount(ChatBubble, {
      props: { role: 'user', content: '**굵게** 아님' },
    })
    expect(w.find('.bubble.user').text()).toBe('**굵게** 아님')
    expect(w.find('.bubble.user strong').exists()).toBe(false)
  })

  // Quasar 2.x는 xs/sm/md/lg/xl(+gt-*/lt-*/*-hide)을 반응형 표시 헬퍼로 예약한다(quasar.css @media 규칙) —
  // 말풍선에 이 이름을 클래스로 쓰면 해당 폭 밖에서 display:none !important가 걸려 답변이 사라진다
  // (2026-09-06 라이브 회귀: 'md' 클래스로 1440px 이상·1024px 미만 화면에서 assistant 답변 전부 비표시).
  const QUASAR_VISIBILITY_CLASS =
    /^(xs|sm|md|lg|xl|gt-(xs|sm|md|lg)|lt-(sm|md|lg|xl)|(xs|sm|md|lg|xl)-hide)$/

  it.each(['assistant', 'user', 'system'] as const)(
    '%s 말풍선의 클래스명은 Quasar 반응형 표시 헬퍼(xs/sm/md/lg/xl 등)와 겹치지 않는다',
    (role) => {
      const w = mount(ChatBubble, { props: { role, content: '**본문**' } })
      const els = [w.element, ...Array.from(w.element.querySelectorAll('*'))]
      const collisions = els
        .flatMap((el) => el.className.split(/\s+/).filter(Boolean))
        .filter((c) => QUASAR_VISIBILITY_CLASS.test(c))
      expect(collisions).toEqual([])
    },
  )

  describe('첨부 칩 (스펙 2026-09-13 §7 — user 말풍선 아래만, 클릭 → download(id))', () => {
    const attachments = [
      { id: 7, fileName: '요구사항.docx', contentType: null, sizeBytes: 1536 },
      { id: 8, fileName: '캡처.png', contentType: 'image/png', sizeBytes: 512 },
    ]

    it('user 말풍선 아래에 첨부 칩(이름·크기)을 그리고, 클릭하면 download(id)를 emit한다', async () => {
      const w = mount(ChatBubble, {
        props: { role: 'user', content: '이 문서 기준으로 봐줘', attachments },
      })
      expect(w.find('.bubble.user').text()).toBe('이 문서 기준으로 봐줘')
      const chips = w.findAll('.bubble-attachments .q-chip')
      expect(chips).toHaveLength(2)
      expect(chips[0]!.text()).toContain('요구사항.docx')
      expect(chips[0]!.text()).toContain('2KB')
      expect(chips[1]!.text()).toContain('512B')
      await chips[1]!.trigger('click')
      expect(w.emitted('download')).toEqual([[8]])
    })

    it('assistant 말풍선은 attachments가 있어도 칩을 그리지 않는다', () => {
      const w = mount(ChatBubble, {
        props: { role: 'assistant', content: '답변', attachments },
      })
      expect(w.find('.bubble-attachments').exists()).toBe(false)
    })

    it('첨부가 없거나 빈 배열이면 칩 줄을 그리지 않는다', () => {
      expect(
        mount(ChatBubble, { props: { role: 'user', content: 'x' } }).find('.bubble-attachments').exists(),
      ).toBe(false)
      expect(
        mount(ChatBubble, { props: { role: 'user', content: 'x', attachments: [] } })
          .find('.bubble-attachments')
          .exists(),
      ).toBe(false)
    })

    it('id 없는(전송 직후 낙관적) 첨부는 클릭할 수 없고 download를 내지 않는다', async () => {
      const w = mount(ChatBubble, {
        props: {
          role: 'user',
          content: '보내는 중',
          attachments: [{ fileName: '메모.txt', sizeBytes: 3 }],
        },
      })
      const chip = w.find('.bubble-attachments .q-chip')
      expect(chip.text()).toContain('메모.txt')
      expect(chip.classes()).not.toContain('q-chip--clickable')
      await chip.trigger('click')
      expect(w.emitted('download')).toBeUndefined()
    })

    it('첨부 칩 줄의 클래스명도 Quasar 반응형 표시 헬퍼와 겹치지 않는다', () => {
      const w = mount(ChatBubble, { props: { role: 'user', content: 'x', attachments } })
      const els = [w.element, ...Array.from(w.element.querySelectorAll('*'))]
      const collisions = els
        .flatMap((el) => el.className.split(/\s+/).filter(Boolean))
        .filter((c) => QUASAR_VISIBILITY_CLASS.test(c))
      expect(collisions).toEqual([])
    })
  })
})
