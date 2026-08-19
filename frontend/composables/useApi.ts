/**
 * Bearer JWT 자동 첨부 fetch wrapper.
 *
 *   const { data } = await useApi<TaskResponse[]>('/api/tasks')
 *   const { data } = await useApi('/api/tasks', { method: 'POST', body: ... })
 */
// options 를 any 로 두면 fetch 옵션 오타(responseType: 'blobb', mehtod 등)를 타입체크가
// 못 잡는다 — $fetch 의 실제 옵션 타입을 그대로 빌려 좁힌다.
type ApiOptions = Parameters<typeof $fetch<unknown>>[1]

export function useApi<T = unknown>(url: string, options: ApiOptions = {}) {
  const auth = useAuthStore()
  return $fetch<T>(url, {
    ...options,
    headers: {
      ...((options?.headers as Record<string, string>) ?? {}),
      ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
    },
    onResponseError({ response }) {
      if (response.status === 401) {
        auth.logout()
      }
    },
  })
}
