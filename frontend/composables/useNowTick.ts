import type { Ref } from 'vue'

/**
 * 마운트된 뒤에만 값이 생기는 현재 시각(ms) — intervalMs마다 갱신 (스펙 2026-10-15 §7 경과 시간).
 * SSR·하이드레이션 동안은 null이라 시간에 따라 바뀌는 문구가 서버 HTML과 어긋나지 않는다.
 */
export function useNowTick(intervalMs = 30_000): Ref<number | null> {
  const now = ref<number | null>(null)
  let timer: ReturnType<typeof setInterval> | null = null
  onMounted(() => {
    now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  })
  onUnmounted(() => {
    if (timer) clearInterval(timer)
  })
  return now
}
