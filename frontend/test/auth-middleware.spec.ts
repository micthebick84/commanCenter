import { describe, it, expect, beforeEach, vi } from 'vitest'

// 전역 인증 미들웨어의 첫 로드 리다이렉트 (2026-09-30 SSR 하이드레이션 정리).
// 토큰이 localStorage에만 있어 SSR은 요청 경로를 그대로 그린다 — 하이드레이션 전에 SPA로 다른 페이지로 보내면
// 클라이언트가 다른 페이지로 하이드레이션해 불일치가 나므로(비로그인 /questions → 로그인 카드 정렬 깨짐),
// 첫 로드에서는 문서를 새로 받아(location.replace) 목적지를 SSR로 받는지 본다. 실제 이동·마운트 중단은 Nuxt navigateTo 몫.
const nuxtApp = { isHydrating: false, payload: { serverRendered: true } }
const auth = { isAuthenticated: false, isAdmin: false, restore: vi.fn() }
const navigateToMock = vi.fn((path: string, options?: unknown) => ({ path, options }))
Object.assign(globalThis, {
  defineNuxtRouteMiddleware: (fn: unknown) => fn,
  useNuxtApp: () => nuxtApp,
  useAuthStore: () => auth,
  navigateTo: navigateToMock,
})

const { default: middleware } = (await import('../middleware/auth.global')) as unknown as {
  default: (to: { path: string }) => unknown
}

const HARD = { external: true, replace: true }

beforeEach(() => {
  navigateToMock.mockClear()
  auth.restore.mockClear()
  Object.assign(auth, { isAuthenticated: false, isAdmin: false })
  Object.assign(nuxtApp, { isHydrating: false, payload: { serverRendered: true } })
})

describe('middleware/auth.global — 첫 로드(SSR 하이드레이션 전)', () => {
  beforeEach(() => {
    nuxtApp.isHydrating = true
  })

  it.each(['/', '/tasks', '/tasks/1', '/questions', '/questions/3', '/admin/workers'])(
    '비로그인 %s → /login 문서를 새로 받는다(SPA 이동이면 다른 페이지로 하이드레이션)',
    (path) => {
      middleware({ path })
      expect(auth.restore).toHaveBeenCalled()
      expect(navigateToMock).toHaveBeenCalledExactlyOnceWith('/login', HARD)
    },
  )

  it('비관리자 /admin/* → /tasks 문서를 새로 받는다', () => {
    Object.assign(auth, { isAuthenticated: true, isAdmin: false })
    middleware({ path: '/admin/workers' })
    expect(navigateToMock).toHaveBeenCalledExactlyOnceWith('/tasks', HARD)
  })

  it('로그인 상태면(관리자 /admin 포함) 그대로 통과한다', () => {
    Object.assign(auth, { isAuthenticated: true, isAdmin: true })
    expect(middleware({ path: '/questions' })).toBeUndefined()
    expect(middleware({ path: '/admin/workers' })).toBeUndefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it.each(['/login', '/oauth/callback'])('%s 는 검사하지 않는다', (path) => {
    expect(middleware({ path })).toBeUndefined()
    expect(auth.restore).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('SSR되지 않은 첫 로드(serverRendered=false)는 맞출 서버 마크업이 없으니 기존대로 SPA 이동', () => {
    nuxtApp.payload.serverRendered = false
    middleware({ path: '/questions' })
    expect(navigateToMock).toHaveBeenCalledExactlyOnceWith('/login')
  })
})

describe('middleware/auth.global — 하이드레이션 뒤 앱 안 이동', () => {
  it('비로그인이면 기존대로 SPA로 /login', () => {
    middleware({ path: '/tasks' })
    expect(navigateToMock).toHaveBeenCalledExactlyOnceWith('/login')
  })

  it('비관리자 /admin/* 이면 기존대로 SPA로 /tasks', () => {
    Object.assign(auth, { isAuthenticated: true, isAdmin: false })
    middleware({ path: '/admin/mcp-catalog' })
    expect(navigateToMock).toHaveBeenCalledExactlyOnceWith('/tasks')
  })
})
