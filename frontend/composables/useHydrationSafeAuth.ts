// 템플릿용 인증 상태 — 첫 하이드레이션이 끝나기 전에는 서버 렌더와 같은 "비로그인" 값을 돌려준다.
// 토큰은 localStorage에만 있어 SSR은 항상 비로그인으로 그리는데, 클라이언트는 전역 미들웨어가 하이드레이션 전에
// auth.restore()를 불러 로그인 상태로 하이드레이션한다 → 툴바 사용자 블록·로그아웃 버튼·하단 내비·관리자 전용 요소가
// SSR과 어긋나 `Hydration completed but contains mismatches`가 났다(2026-09-30 재현: 로그인된 모든 페이지, 폭 무관).
// - SSR로 그려지는 템플릿의 인증 분기는 스토어 대신 이 값을 쓴다. 하이드레이션이 끝나면 곧바로 실제 값으로 바뀐다.
// - 하이드레이션 뒤 새로 마운트되는 컴포넌트(클라이언트 이동)는 처음부터 실제 값을 받는다(Quasar useHydration).
// - 렌더 게이트일 뿐이다. 토큰·API 호출·리다이렉트 판단은 그대로 useAuthStore()를 쓴다.
import { computed } from 'vue'
import { useHydration } from 'quasar'

export function useHydrationSafeAuth() {
  const auth = useAuthStore()
  const { isHydrated } = useHydration()
  return {
    isAuthenticated: computed(() => isHydrated.value && !!auth.isAuthenticated),
    isAdmin: computed(() => isHydrated.value && !!auth.isAdmin),
    me: computed(() => (isHydrated.value ? (auth.me ?? null) : null)),
  }
}
