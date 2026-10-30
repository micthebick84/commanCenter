import { vi } from 'vitest'
import { ref, type Ref } from 'vue'

// useApi mock: tests override .mockResolvedValueOnce / .mockRejectedValueOnce per case.
export const useApiMock = vi.fn()

// auth store stub: composable reads auth.accessToken to build the SSE URL.
export const authStub = { accessToken: 'test-token-abc', isAdmin: false }
export const useAuthStoreMock = vi.fn(() => authStub)

// runtime config stub: composable reads public.apiBaseUrl for URL construction.
export const useRuntimeConfigMock = vi.fn(() => ({
  public: { apiBaseUrl: '/api', pollIntervalMs: 5000 },
}))

// useCookie stub: 이름별로 같은 ref를 돌려준다(브라우저 쿠키처럼 컴포넌트를 다시 마운트해도 값이 남는다).
// 테스트는 cookieJar에서 값을 미리 넣거나 읽는다. 테스트마다 비운다.
export const cookieJar = new Map<string, Ref<unknown>>()
export const useCookieMock = vi.fn((name: string) => {
  if (!cookieJar.has(name)) cookieJar.set(name, ref<unknown>(null))
  return cookieJar.get(name)!
})

export function resetNuxtMocks() {
  useApiMock.mockReset()
  cookieJar.clear()
  authStub.accessToken = 'test-token-abc'
  authStub.isAdmin = false
  useAuthStoreMock.mockClear()
}
