import { ref } from 'vue'
import { describe, it, expect, vi } from 'vitest'
import { useAutoScroll } from './useAutoScroll'

// distance = scrollHeight - scrollTop - clientHeight
function fakeEl(
  over: Partial<{ scrollHeight: number; scrollTop: number; clientHeight: number }> = {},
) {
  return {
    scrollHeight: 1000,
    scrollTop: 0,
    clientHeight: 300,
    scrollTo: vi.fn(),
    ...over,
  } as unknown as HTMLElement
}

describe('useAutoScroll', () => {
  it('onScroll marks nearBottom true within threshold and resets unread', () => {
    const el = ref(fakeEl({ scrollTop: 650 })) // distance = 50 <= 80
    const a = useAutoScroll(el)
    a.unread.value = 3
    a.onScroll()
    expect(a.nearBottom.value).toBe(true)
    expect(a.unread.value).toBe(0)
  })

  it('onScroll marks nearBottom false beyond threshold and keeps unread', () => {
    const el = ref(fakeEl({ scrollTop: 0 })) // distance = 700 > 80
    const a = useAutoScroll(el)
    a.unread.value = 2
    a.onScroll()
    expect(a.nearBottom.value).toBe(false)
    expect(a.unread.value).toBe(2)
  })

  it('notifyNewContent scrolls to bottom when near bottom', () => {
    const el = ref(fakeEl({ scrollTop: 700 })) // distance 0 -> near
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent()
    expect((el.value as any).scrollTo).toHaveBeenCalledWith({ top: 1000, behavior: 'smooth' })
    expect(a.unread.value).toBe(0)
  })

  it('notifyNewContent bumps unread when scrolled up', () => {
    const el = ref(fakeEl({ scrollTop: 0 })) // far
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent()
    expect(a.unread.value).toBe(1)
    expect((el.value as any).scrollTo).not.toHaveBeenCalled()
  })

  it('notifyNewContent with force scrolls even when scrolled up', () => {
    const el = ref(fakeEl({ scrollTop: 0 }))
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent({ force: true })
    expect((el.value as any).scrollTo).toHaveBeenCalled()
    expect(a.unread.value).toBe(0)
  })

  it('scrollToBottom uses scrollTop fallback when scrollTo is absent', () => {
    const el = ref({ scrollHeight: 500, scrollTop: 0, clientHeight: 100 } as unknown as HTMLElement)
    const a = useAutoScroll(el)
    a.scrollToBottom('auto')
    expect(el.value!.scrollTop).toBe(500)
  })

  it('is a no-op when the element ref is null', () => {
    const el = ref<HTMLElement | null>(null)
    const a = useAutoScroll(el)
    expect(() => {
      a.onScroll()
      a.scrollToBottom()
      a.notifyNewContent()
    }).not.toThrow()
  })
})
