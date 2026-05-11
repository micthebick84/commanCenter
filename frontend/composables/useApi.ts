/**
 * Bearer JWT 자동 첨부 fetch wrapper.
 *
 *   const { data } = await useApi<TaskResponse[]>('/api/tasks')
 *   const { data } = await useApi('/api/tasks', { method: 'POST', body: ... })
 */
export function useApi<T = unknown>(url: string, options: any = {}) {
  const auth = useAuthStore()
  return $fetch<T>(url, {
    ...options,
    headers: {
      ...(options.headers ?? {}),
      ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
    },
    onResponseError({ response }) {
      if (response.status === 401) {
        auth.logout()
      }
    },
  })
}
