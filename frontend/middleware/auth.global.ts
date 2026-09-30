/**
 * 비로그인 시 /login 강제 리다이렉트.
 * /login, /oauth/callback 은 예외.
 */
export default defineNuxtRouteMiddleware((to) => {
  if (process.server) return

  if (to.path === '/login' || to.path.startsWith('/oauth/')) return

  const auth = useAuthStore()
  auth.restore()
  if (!auth.isAuthenticated) {
    return redirectTo('/login')
  }
  // 관리자 전용 경로 검사
  if (to.path.startsWith('/admin') && !auth.isAdmin) {
    return redirectTo('/tasks')
  }
})

/**
 * 첫 로드(SSR 하이드레이션 전)의 리다이렉트는 문서를 새로 받는다(2026-09-30 SSR 하이드레이션 정리).
 * 토큰이 localStorage에만 있어 SSR은 요청 경로를 그대로 그리는데, 여기서 SPA 이동을 하면 클라이언트가 다른 페이지로
 * 하이드레이션해 `Hydration completed but contains mismatches`가 난다 — 프로덕션에서는 어긋난 클래스가 교정되지 않아
 * 비로그인 /questions·/tasks 진입 시 로그인 카드가 가운데 정렬되지 않고 좌상단에 붙었다.
 * 하이드레이션 뒤로 미루면 보호 페이지가 토큰 없이 마운트돼 API 401 → auth.logout()(IdP 로그아웃)으로 튕기므로,
 * 마운트 전에 location.replace로 이동해 목적지를 SSR로 받는다(Nuxt는 이때 마운트를 멈춘다). 이후 이동은 기존대로 SPA.
 */
function redirectTo(path: string) {
  const nuxtApp = useNuxtApp()
  if (nuxtApp.isHydrating && nuxtApp.payload.serverRendered) {
    return navigateTo(path, { external: true, replace: true })
  }
  return navigateTo(path)
}
