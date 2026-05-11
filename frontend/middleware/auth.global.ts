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
    return navigateTo('/login')
  }
  // 관리자 전용 경로 검사
  if (to.path.startsWith('/admin') && !auth.isAdmin) {
    return navigateTo('/tasks')
  }
})
