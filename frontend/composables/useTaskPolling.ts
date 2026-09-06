/**
 * 5초 간격으로 fetcher 재실행. mount 시 시작, unmount 시 정리.
 * 폴링 중복 방지: 이전 요청 완료 후에만 다음 시작(겹치는 틱은 건너뛰고 다음 틱만 예약).
 * refresh(): 호출 시점 이후의 데이터를 보장 — 진행 중 요청이 있으면 그 뒤에 한 번 더 가져온다
 * (진행 중에 들어온 재진입은 한 번의 재조회로 합침). 승인 직후 #interview-card로 스크롤하는 경로가 이 보장에 기댄다.
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
  /** 진행 중인 fetch. fetcher 오류는 안에서 잡으므로 이 약속은 reject되지 않는다. */
  let inflight: Promise<void> | null = null
  /** 진행 중에 들어온 refresh() 재진입을 하나로 합친 약속. */
  let queuedRefresh: Promise<void> | null = null

  function run(): Promise<void> {
    loading.value = true
    inflight = (async () => {
      try {
        data.value = await fetcher()
        error.value = null
      } catch (e) {
        error.value = e
      } finally {
        loading.value = false
        inflight = null
        schedule()
      }
    })()
    return inflight
  }

  /** 주기 폴링 틱 — 겹치면 건너뛰고 다음 틱만 예약한다. */
  async function tick() {
    if (stopped) return
    if (inflight) {
      schedule()
      return
    }
    await run()
  }

  /** 명시적 새로고침 — resolve 시점의 data는 반드시 호출 이후에 시작한 fetch의 결과다. */
  function refresh(): Promise<void> {
    if (stopped) return Promise.resolve()
    if (!inflight) return run()
    if (!queuedRefresh) {
      queuedRefresh = inflight.then(() => {
        queuedRefresh = null
        return stopped ? undefined : run()
      })
    }
    return queuedRefresh
  }

  function schedule() {
    if (stopped) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(tick, config.pollIntervalMs)
  }

  onMounted(() => {
    tick()
  })

  onUnmounted(() => {
    stopped = true
    if (timer) clearTimeout(timer)
  })

  return { data, loading, error, refresh }
}
