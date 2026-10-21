import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, type Ref } from 'vue'
import { useNowTick } from './useNowTick'

// 실제 컴포저블을 작은 호스트 컴포넌트에 마운트해 onMounted/onUnmounted가 진짜로 도는 상태에서 검증한다
// (스펙 2026-10-15 §7 — 경과 시간은 마운트 뒤에만 그리고 30초마다 다시 계산).
const START = Date.parse('2026-10-15T10:00:00Z')

let tick: Ref<number | null>
let seenInSetup: number | null | undefined

const Host = defineComponent({
  setup() {
    tick = useNowTick()
    seenInSetup = tick.value // setup 직후, onMounted 전
    return () => h('div')
  },
})

describe('useNowTick (스펙 2026-10-15 §7)', () => {
  beforeEach(() => {
    // 인터벌과 Date만 페이크 — Vue 스케줄러(마이크로태스크)는 그대로 둔다.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(START)
    seenInSetup = undefined
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('마운트 전에는 null(SSR·하이드레이션과 같은 값), 마운트 직후에는 현재 시각이다', () => {
    const w = mount(Host)
    expect(seenInSetup).toBeNull()
    expect(tick.value).toBe(START)
    w.unmount()
  })

  it('기본 30초 간격으로 갱신한다 — 29,999ms에는 그대로, 30,000ms에 바뀐다', () => {
    const w = mount(Host)
    vi.advanceTimersByTime(29_999)
    expect(tick.value).toBe(START)
    vi.advanceTimersByTime(1)
    expect(tick.value).toBe(START + 30_000)
    vi.advanceTimersByTime(30_000)
    expect(tick.value).toBe(START + 60_000)
    w.unmount()
  })

  it('언마운트하면 타이머가 남지 않는다', () => {
    const w = mount(Host)
    expect(vi.getTimerCount()).toBe(1) // 인터벌은 하나만
    w.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
