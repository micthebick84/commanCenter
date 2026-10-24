// 다이얼로그 전체화면 기준 (2026-10-24). 화면 내비 분기는 여전히 $q.screen.lt.md(1024px) 하나지만, 다이얼로그는
// 폰(<600px)에서만 전체화면이다 — 태블릿(600–1023px)은 화면이 넓어 짧은 폼까지 전체화면이면 여백만 남는다.
// 다이얼로그 안의 전체화면 장식(상단 닫기 바·하단 액션 바)과 입력 밀도도 이 값을 따른다.
import { useQuasar } from 'quasar'

export function useFullscreenDialog() {
  const $q = useQuasar()
  return computed(() => $q.screen.lt.sm)
}
