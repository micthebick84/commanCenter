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

    const r1 = (w.vm as any).refresh()
    const r2 = (w.vm as any).refresh()
    expect(fetcher).toHaveBeenCalledTimes(1) // 진행 중이라 즉시 재호출되지는 않음

    resolveFetch(null)
    await flushPromises()
    await Promise.all([r1, r2])

    // 진행 중 refresh() 재진입 2건은 그 요청이 끝난 뒤 한 번의 재조회로 합쳐진다 (승인 후 스크롤 리뷰 파인딩 1)
    expect(fetcher).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(pollIntervalMs)

    // 타이머가 클리어 안 되고 누적되면 다음 라운드에 fetcher가 한 번을 넘겨 불린다
    expect(fetcher).toHaveBeenCalledTimes(3)

    w.unmount()
  })

  it('진행 중 refresh()는 그 요청이 끝난 뒤 다시 가져와 호출 시점 이후 데이터로 resolve한다', async () => {
    let resolveFirst!: (v: unknown) => void
    const first = new Promise((resolve) => {
      resolveFirst = resolve
    })
    const fetcher = vi
      .fn()
      .mockImplementationOnce(() => first)
      .mockImplementation(() => Promise.resolve('fresh'))

    const Host = defineComponent({
      setup() {
        return useTaskPolling(fetcher)
      },
      render() {
        return h('div')
      },
    })
    const w = mount(Host)
    expect(fetcher).toHaveBeenCalledTimes(1)

    const done = (w.vm as any).refresh() // 첫 요청(stale)이 아직 진행 중
    resolveFirst('stale')
    await done

    expect(fetcher).toHaveBeenCalledTimes(2)
    expect((w.vm as any).data).toBe('fresh')

    w.unmount()
  })
})
