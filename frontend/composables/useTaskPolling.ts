/**
 * 5초 간격으로 fetcher 재실행. mount 시 시작, unmount 시 정리.
 * 폴링 중복 방지: 이전 요청 완료 후에만 다음 시작.
 *
 *   const { data, refresh } = useTaskPolling(() => useApi('/api/tasks'))
 */
export function useTaskPolling<T>(fetcher: () => Promise<T>) {
  const config = useRuntimeConfig().public
  const data = ref<T | null>(null)
  const loading = ref(false)
  const error = ref<unknown>(null)

  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = false

  async function tick() {
    if (stopped) return
    if (loading.value) {
      schedule()
      return
    }
    loading.value = true
    try {
      data.value = await fetcher()
      error.value = null
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
      schedule()
    }
  }

  function schedule() {
    if (stopped) return
    timer = setTimeout(tick, config.pollIntervalMs)
  }

  onMounted(() => {
    tick()
  })

  onUnmounted(() => {
    stopped = true
    if (timer) clearTimeout(timer)
  })

  return { data, loading, error, refresh: tick }
}
