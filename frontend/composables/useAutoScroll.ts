import type { Ref } from 'vue'

export interface AutoScroll {
  nearBottom: Ref<boolean>
  unread: Ref<number>
  onScroll: () => void
  scrollToBottom: (behavior?: ScrollBehavior) => void
  notifyNewContent: (opts?: { force?: boolean }) => void
}

// 채팅 트랜스크립트용 스마트 자동 스크롤.
// - 사용자가 하단 근처(threshold px 이내)인지 추적.
// - 새 콘텐츠가 생기면: 하단 근처(또는 force)면 따라 내려가고, 아니면 unread++.
// 순수 DOM 산술이라 가짜 element로 단위 테스트 가능. (ref는 Nuxt 자동 import — 기존 컴포저블 패턴.)
export function useAutoScroll(
  scrollEl: Ref<HTMLElement | null>,
  opts: { threshold?: number } = {},
): AutoScroll {
  const threshold = opts.threshold ?? 80
  const nearBottom = ref(true)
  const unread = ref(0)

  function onScroll() {
    const el = scrollEl.value
    if (!el) return
    nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
    if (nearBottom.value) unread.value = 0
  }

  function scrollToBottom(behavior: ScrollBehavior = 'smooth') {
    const el = scrollEl.value
    if (!el) return
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior })
    } else {
      el.scrollTop = el.scrollHeight // happy-dom 등 scrollTo 미구현 환경 폴백
    }
    nearBottom.value = true
    unread.value = 0
  }

  function notifyNewContent(o: { force?: boolean } = {}) {
    if (o.force || nearBottom.value) {
      scrollToBottom('smooth')
    } else {
      unread.value += 1
    }
  }

  return { nearBottom, unread, onScroll, scrollToBottom, notifyNewContent }
}
