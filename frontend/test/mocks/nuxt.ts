import { vi } from 'vitest'

// useApi mock: tests override .mockResolvedValueOnce / .mockRejectedValueOnce per case.
export const useApiMock = vi.fn()

// auth store stub: composable reads auth.accessToken to build the SSE URL.
export const authStub = { accessToken: 'test-token-abc' }
export const useAuthStoreMock = vi.fn(() => authStub)

// runtime config stub: composable reads public.apiBaseUrl for URL construction.
export const useRuntimeConfigMock = vi.fn(() => ({
  public: { apiBaseUrl: '/api', pollIntervalMs: 5000 },
}))

export function resetNuxtMocks() {
  useApiMock.mockReset()
  authStub.accessToken = 'test-token-abc'
  useAuthStoreMock.mockClear()
}
