import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { useTaskPolling } from './useTaskPolling'
import { useRuntimeConfigMock } from '../test/mocks/nuxt'

// 실제 컴포저블(전역 스텁이 아님)을 작은 호스트 컴포넌트에 마운트해 onMounted/onUnmounted
// 라이프사이클이 진짜로 도는 상태에서 검증한다 (스펙 파인딩 #2).
describe('useTaskPolling — refresh() 재진입 시 타이머 중복 방지 (실제 컴포저블)', () => {
  beforeEach(() => {
    // setTimeout/clearTimeout만 페이크 — flushPromises()가 쓰는 setImmediate(실제 이벤트 루프)는 그대로 둔다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('요청 진행 중 refresh()를 두 번 호출해도 다음 라운드엔 fetcher가 한 번만 더 불린다', async () => {
    const { pollIntervalMs } = useRuntimeConfigMock().public
    let resolveFetch!: (v: unknown) => void
    const pending = new Promise((resolve) => {
      resolveFetch = resolve
    })
    const fetcher = vi.fn(() => pending)

    const Host = defineComponent({
      setup() {
        return useTaskPolling(fetcher)
      },
      render() {
        return h('div')
      },
    })
    const w = mount(Host)

    expect(fetcher).toHaveBeenCalledTimes(1) // onMounted가 최초 tick 실행, 아직 진행 중(pending)

    ;(w.vm as any).refresh()
    ;(w.vm as any).refresh()
    expect(fetcher).toHaveBeenCalledTimes(1) // 진행 중이라 즉시 재호출되지는 않음(스케줄만 됨)

    resolveFetch(null)
    await flushPromises()

    await vi.advanceTimersByTimeAsync(pollIntervalMs)

    // 타이머가 클리어 안 되고 누적되면 다음 라운드에 fetcher가 2회를 넘겨 불린다
    expect(fetcher).toHaveBeenCalledTimes(2)

    w.unmount()
  })
})
